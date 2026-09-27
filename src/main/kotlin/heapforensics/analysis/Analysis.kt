package heapforensics.analysis

import heapforensics.domain.*
import kotlinx.serialization.Serializable

/** 弱引用是否保活由显式策略决定。 */
enum class WeakPolicy { WEAK_DROP, WEAK_KEEP }

@Serializable
data class AnalysisLimits(
    val maxNodes: Int = 10_000,
    val maxEdges: Int = 50_000,
    val maxRootPaths: Int = 64
)

@Serializable
data class AnalysisParams(
    val weakPolicy: WeakPolicy = WeakPolicy.WEAK_DROP,
    val paramVersion: String = "v1-weak-drop",
    val limits: AnalysisLimits = AnalysisLimits()
)

@Serializable
data class AnalysisResult(
    val reachable: List<String>,
    val unreachable: List<String>,
    val idom: Map<String, String>,
    val retainedBytes: Map<String, Long>,
    val ownBytes: Map<String, Long>,
    val rootPaths: Map<String, List<List<String>>>,
    val cycles: List<List<String>>,
    val diagnostics: List<Diagnostic>,
    val complete: Boolean
)

/** 三种失败必须是三种不同结果：预算耗尽 / 语义无解（格式损坏在解析层）。 */
sealed interface AnalysisOutcome {
    data class Ok(val result: AnalysisResult) : AnalysisOutcome
    data class BudgetExhausted(val resource: String, val limit: Int, val position: String) : AnalysisOutcome
    data class SemanticUnsolvable(val diagnostics: List<Diagnostic>) : AnalysisOutcome
}

private class BudgetExceeded(val resource: String, val limit: Int, val position: String) : RuntimeException()

object Engine {
    const val VIRTUAL_ROOT = "@ROOT"

    fun analyze(snapshot: RawSnapshot, params: AnalysisParams): AnalysisOutcome {
        if (snapshot.objects.size > params.limits.maxNodes)
            return AnalysisOutcome.BudgetExhausted("nodes", params.limits.maxNodes, "objects")
        if (snapshot.edges.size > params.limits.maxEdges)
            return AnalysisOutcome.BudgetExhausted("edges", params.limits.maxEdges, "edges")

        // 语义校验：失败即“语义无解”，不得用默认值伪装成功
        val semantic = mutableListOf<Diagnostic>()
        val seen = HashSet<String>()
        snapshot.objects.forEachIndexed { i, o ->
            if (!seen.add(o.id)) semantic += Diagnostic(
                Severity.ERROR, "object.id.duplicate", "对象 id 重复: '${o.id}'", "objects[$i].id"
            )
        }
        val classIds = snapshot.classes.map { it.id }.toSet()
        snapshot.objects.forEachIndexed { i, o ->
            if (o.classId !in classIds) semantic += Diagnostic(
                Severity.ERROR, "class.unknown", "对象 '${o.id}' 引用未知类 '${o.classId}'", "objects[$i].classId"
            )
        }
        val objIds = snapshot.objects.map { it.id }.toSet()
        snapshot.roots.forEachIndexed { i, r ->
            if (r.objectId !in objIds) semantic += Diagnostic(
                Severity.ERROR, "root.missing", "root 指向不存在的对象 '${r.objectId}'", "roots[$i].objectId"
            )
        }
        if (semantic.isNotEmpty()) return AnalysisOutcome.SemanticUnsolvable(semantic)

        // 派生层：单位规范化；无法转换的值留在原位置并附诊断
        val diagnostics = mutableListOf<Diagnostic>()
        val ownBytes = mutableMapOf<String, Long>()
        var complete = true
        snapshot.objects.forEachIndexed { i, o ->
            val (bytes, diag) = parseSize(o.sizeRaw, "objects[$i].size (id=${o.id})")
            if (diag != null) {
                diagnostics += diag
                if (diag.severity == Severity.ERROR) complete = false
            }
            if (bytes != null) ownBytes[o.id] = bytes
        }
        // 重复地址：地址不当稳定身份，仅记录信息诊断
        snapshot.objects.groupBy { it.address }.filter { it.value.size > 1 }.forEach { (addr, objs) ->
            diagnostics += Diagnostic(
                Severity.INFO, "address.duplicate",
                "地址 $addr 被多个对象使用 (${objs.joinToString(", ") { it.id }})；地址不作为稳定身份",
                "objects.address"
            )
        }
        // 不完整对象：边引用缺失对象 → 带位置诊断，分析继续
        val adjacency = mutableMapOf<String, MutableList<String>>()
        snapshot.edges.forEachIndexed { i, e ->
            val missing = mutableListOf<String>()
            if (e.from !in objIds) missing += e.from
            if (e.to !in objIds) missing += e.to
            if (missing.isNotEmpty()) {
                diagnostics += Diagnostic(
                    Severity.WARNING, "object.incomplete",
                    "边 ${e.from} -> ${e.to} (${e.kind.name.lowercase()}) 引用缺失对象 ${missing.joinToString(", ")}；该边不参与图计算",
                    "edges[$i]"
                )
                complete = false
                return@forEachIndexed
            }
            val allowed = e.kind != EdgeKind.WEAK || params.weakPolicy == WeakPolicy.WEAK_KEEP
            if (allowed) adjacency.getOrPut(e.from) { mutableListOf() }.add(e.to)
        }

        // 可达性（支配树只在可达图上构建）
        val roots = snapshot.roots.map { it.objectId }.distinct().sorted()
        val reachable = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        roots.forEach { if (reachable.add(it)) queue.add(it) }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in adjacency[u].orEmpty()) if (reachable.add(v)) queue.add(v)
        }
        val unreachable = objIds.filter { it !in reachable }.sorted()

        val cycles = findCycles(adjacency, reachable)
        val idom = dominators(adjacency, roots, reachable)
        val retained = retainedSizes(idom, ownBytes)
        val rootPaths = try {
            shortestRootPaths(adjacency, roots, reachable, params.limits.maxRootPaths)
        } catch (b: BudgetExceeded) {
            return AnalysisOutcome.BudgetExhausted(b.resource, b.limit, b.position)
        }

        return AnalysisOutcome.Ok(
            AnalysisResult(
                reachable = reachable.sorted(),
                unreachable = unreachable,
                idom = idom.toSortedMap(),
                retainedBytes = retained.toSortedMap(),
                ownBytes = ownBytes.toSortedMap(),
                rootPaths = rootPaths.toSortedMap(),
                cycles = cycles,
                diagnostics = diagnostics.sortedBy { it.position },
                complete = complete
            )
        )
    }

    /** Cooper–Harvey–Kennedy 迭代支配树，仅在可达子图上构建；虚拟根连接所有 root。 */
    private fun dominators(
        adjacency: Map<String, List<String>>,
        roots: List<String>,
        reachable: Set<String>
    ): Map<String, String> {
        val start = VIRTUAL_ROOT
        val succ = mutableMapOf<String, List<String>>()
        succ[start] = roots
        for (u in reachable) succ[u] = adjacency[u].orEmpty().filter { it in reachable }
        val preds = mutableMapOf<String, MutableList<String>>()
        for ((u, vs) in succ) for (v in vs) preds.getOrPut(v) { mutableListOf() }.add(u)

        // DFS 后序 → 逆后序编号
        val postorder = mutableListOf<String>()
        val visited = HashSet<String>()
        val stack = ArrayDeque<Pair<String, Iterator<String>>>()
        visited += start
        stack.addLast(start to succ[start]!!.iterator())
        while (stack.isNotEmpty()) {
            val (node, it) = stack.last()
            if (it.hasNext()) {
                val next = it.next()
                if (visited.add(next)) stack.addLast(next to succ[next].orEmpty().iterator())
            } else {
                postorder += node
                stack.removeLast()
            }
        }
        val rpo = postorder.asReversed()
        val rpoNum = rpo.withIndex().associate { it.value to it.index }

        val idom = HashMap<String, String>()
        idom[start] = start
        fun intersect(a: String, b: String): String {
            var f1 = a
            var f2 = b
            while (f1 != f2) {
                while (rpoNum.getValue(f1) > rpoNum.getValue(f2)) f1 = idom.getValue(f1)
                while (rpoNum.getValue(f2) > rpoNum.getValue(f1)) f2 = idom.getValue(f2)
            }
            return f1
        }
        var changed = true
        while (changed) {
            changed = false
            for (b in rpo) {
                if (b == start) continue
                val processed = preds[b].orEmpty().filter { idom.containsKey(it) }
                if (processed.isEmpty()) continue
                var newIdom = processed.first()
                for (p in processed.drop(1)) newIdom = intersect(p, newIdom)
                if (idom[b] != newIdom) {
                    idom[b] = newIdom
                    changed = true
                }
            }
        }
        // root 的直接支配者记为虚拟根 @ROOT；不可达对象不出现在支配树中
        return reachable.associateWith { idom[it] ?: start }
    }

    private fun retainedSizes(idom: Map<String, String>, ownBytes: Map<String, Long>): Map<String, Long> {
        val children = mutableMapOf<String, MutableList<String>>()
        for ((node, parent) in idom) children.getOrPut(parent) { mutableListOf() }.add(node)
        val memo = HashMap<String, Long>()
        fun subtree(node: String): Long = memo.getOrPut(node) {
            val total = (ownBytes[node] ?: 0L) + children[node].orEmpty().sumOf { subtree(it) }
            memo[node] = total
            total
        }
        return idom.keys.associateWith { subtree(it) }
    }

    /** 多个等长 root 路径全部保留；超过预算则整体判定为预算耗尽。 */
    private fun shortestRootPaths(
        adjacency: Map<String, List<String>>,
        roots: List<String>,
        reachable: Set<String>,
        cap: Int
    ): Map<String, List<List<String>>> {
        val depth = HashMap<String, Int>()
        val paths = HashMap<String, MutableList<List<String>>>()
        val queue = ArrayDeque<String>()
        for (r in roots) {
            depth[r] = 0
            paths[r] = mutableListOf(listOf(r))
            queue.add(r)
        }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            val du = depth.getValue(u)
            for (v in adjacency[u].orEmpty()) {
                if (v !in reachable) continue
                val dv = depth[v]
                if (dv == null) {
                    depth[v] = du + 1
                    val inherited = paths.getValue(u).map { it + v }
                    if (inherited.size > cap) throw BudgetExceeded("rootPaths", cap, "object '$v'")
                    paths[v] = inherited.toMutableList()
                    queue.add(v)
                } else if (dv == du + 1) {
                    val list = paths.getValue(v)
                    for (p in paths.getValue(u)) {
                        if (list.size >= cap) throw BudgetExceeded("rootPaths", cap, "object '$v'")
                        list.add(p + v)
                    }
                }
            }
        }
        return paths.mapValues { it.value.toList() }
    }

    /** Tarjan SCC：环（含自环）以排序后的对象 id 列表报告。 */
    private fun findCycles(adjacency: Map<String, List<String>>, reachable: Set<String>): List<List<String>> {
        val index = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val onStack = HashSet<String>()
        val stack = ArrayDeque<String>()
        var counter = 0
        val sccs = mutableListOf<List<String>>()

        fun strongconnect(v: String) {
            index[v] = counter
            low[v] = counter
            counter++
            stack.addLast(v)
            onStack += v
            for (w in adjacency[v].orEmpty()) {
                if (w !in reachable) continue
                if (w !in index) {
                    strongconnect(w)
                    low[v] = minOf(low.getValue(v), low.getValue(w))
                } else if (w in onStack) {
                    low[v] = minOf(low.getValue(v), index.getValue(w))
                }
            }
            if (low.getValue(v) == index.getValue(v)) {
                val scc = mutableListOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack -= w
                    scc += w
                    if (w == v) break
                }
                if (scc.size > 1 || adjacency[v].orEmpty().contains(v)) sccs += scc.sorted()
            }
        }
        for (v in reachable.sorted()) if (v !in index) strongconnect(v)
        return sccs.sortedBy { it.first() }
    }
}
