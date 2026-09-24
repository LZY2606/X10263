package heapforensics

import kotlinx.serialization.Serializable

@Serializable
enum class WeakPolicy { DROP, KEEP }

/** 计算参数：每次修订产生新版本号，历史版本结果保留。 */
@Serializable
data class Params(val version: Int, val weakPolicy: WeakPolicy, val maxRootPaths: Int = 256)

@Serializable
enum class ComputeStatus { OK, PARTIAL, RESOURCE_EXHAUSTED, UNSATISFIABLE }

@Serializable
data class ObjectAnalysis(
    val objectId: String,
    val reachable: Boolean,
    val dominatorParent: String?,      // null => 直接挂在虚拟根上
    val retainedBytes: Long,           // 单位：字节
    val retainedComplete: Boolean,     // false => 支配子树含不完整对象
    val rootPaths: List<List<String>>, // 全部最短等长 root 路径均保留
)

@Serializable
data class Judgment(val rule: String, val detail: String, val objects: List<String> = emptyList())

@Serializable
data class AnalysisResult(
    val status: ComputeStatus,
    val diagnostics: List<Diagnostic>,
    val objects: List<ObjectAnalysis>,
    val totalReachableBytes: Long,
    val judgments: List<Judgment>,
)

class ResourceExhausted(message: String) : Exception(message)

object Analyzer {

    /** 保活边集合：强/内部边始终保活；弱引用由显式策略决定。 */
    private fun keepAliveEdges(s: HeapSnapshot, p: Params): List<HeapEdge> =
        s.edges.filter { it.kind != EdgeKind.WEAK || p.weakPolicy == WeakPolicy.KEEP }

    fun analyze(s: HeapSnapshot, p: Params): AnalysisResult {
        val diags = mutableListOf<Diagnostic>()
        val judgments = mutableListOf<Judgment>()
        val objById = s.objects.associateBy { it.id }
        val edges = keepAliveEdges(s, p)
        val succ = HashMap<String, MutableList<String>>()
        val pred = HashMap<String, MutableList<String>>()
        for (e in edges) {
            succ.getOrPut(e.from) { mutableListOf() }.add(e.to)
            pred.getOrPut(e.to) { mutableListOf() }.add(e.from)
        }
        val rootIds = s.roots.map { it.objectId }.distinct().sorted()

        // 可达集：仅从 root 出发沿保活边
        val reachable = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        rootIds.forEach { if (reachable.add(it)) queue.add(it) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            for (m in succ[n].orEmpty()) if (reachable.add(m)) queue.add(m)
        }
        judgments += Judgment(
            "dominator-reachable-only",
            "支配树仅在可达图上构建：可达 ${reachable.size} / 共 ${s.objects.size} 个对象；弱引用策略=${p.weakPolicy}",
            reachable.sorted(),
        )
        judgments += Judgment(
            "weak-policy-explicit",
            "弱引用是否保活由显式策略决定：weakPolicy=${p.weakPolicy}（参数版本 v${p.version}）",
        )

        // 地址不作稳定身份：重复地址仅提示，不合并
        val byAddr = s.objects.groupBy { it.address }.filter { it.value.size > 1 }
        byAddr.forEach { (addr, list) ->
            judgments += Judgment(
                "address-not-identity",
                "地址 $addr 被 ${list.size} 个对象共享；稳定身份使用 snapshotId:objectId，不合并",
                list.map { it.id }.sorted(),
            )
        }

        // 支配树（Cooper-Harvey-Kennedy，虚拟根）
        val idom = dominators(reachable, succ, rootIds)

        // root 最短路径（全部等长路径保留，预算耗尽为独立结果）
        val depth = HashMap<String, Int>()
        val bfsQ = ArrayDeque<String>()
        rootIds.forEach { depth[it] = 0; bfsQ.add(it) }
        while (bfsQ.isNotEmpty()) {
            val n = bfsQ.removeFirst()
            for (m in succ[n].orEmpty()) if (m !in depth) { depth[m] = depth[n]!! + 1; bfsQ.add(m) }
        }
        var pathBudget = p.maxRootPaths
        val pathMemo = HashMap<String, List<List<String>>>()
        fun pathsTo(n: String): List<List<String>> {
            pathMemo[n]?.let { return it }
            if (n in rootIds) return listOf(listOf(n))
            val d = depth[n] ?: return emptyList()
            val res = mutableListOf<List<String>>()
            for (pr in pred[n].orEmpty().sorted()) {
                if (depth[pr] == d - 1) {
                    for (prefix in pathsTo(pr)) {
                        res.add(prefix + n)
                        pathBudget--
                        if (pathBudget < 0) throw ResourceExhausted("root 路径数量超过预算 maxRootPaths=${p.maxRootPaths}")
                    }
                }
            }
            pathMemo[n] = res
            return res
        }

        val analyses = mutableListOf<ObjectAnalysis>()
        try {
            val children = HashMap<String, MutableList<String>>()
            for (n in reachable) {
                val par = idom[n]
                if (par != null) children.getOrPut(par) { mutableListOf() }.add(n)
            }
            val retained = HashMap<String, Long>()
            val complete = HashMap<String, Boolean>()
            fun compute(n: String): Pair<Long, Boolean> {
                retained[n]?.let { return it to complete[n]!! }
                var sum = objById[n]?.sizeBytes ?: 0L
                var ok = objById[n]?.sizeBytes != null
                for (ch in children[n].orEmpty().sorted()) {
                    val (cs, cok) = compute(ch)
                    sum += cs; ok = ok && cok
                }
                retained[n] = sum; complete[n] = ok
                return sum to ok
            }
            for (n in reachable.sorted()) {
                compute(n)
                pathsTo(n)
            }
            for (n in s.objects.map { it.id }.sorted()) {
                val r = n in reachable
                val paths = if (r) pathMemo[n] ?: pathsTo(n) else emptyList()
                if (paths.size > 1) {
                    judgments += Judgment(
                        "equal-root-paths-kept",
                        "对象 $n 存在 ${paths.size} 条等长最短 root 路径，全部保留",
                        listOf(n),
                    )
                }
                analyses += ObjectAnalysis(
                    objectId = n,
                    reachable = r,
                    dominatorParent = if (r) idom[n] else null,
                    retainedBytes = if (r) retained[n] ?: 0L else 0L,
                    retainedComplete = if (r) complete[n] ?: true else true,
                    rootPaths = paths,
                )
            }
        } catch (ex: ResourceExhausted) {
            return AnalysisResult(
                ComputeStatus.RESOURCE_EXHAUSTED,
                diags + Diagnostic("$.roots", ex.message!!),
                emptyList(), 0L, judgments,
            )
        }

        // 不完整对象诊断（保留在原位置，不以默认值伪装成功）
        var partial = false
        for (o in s.objects) {
            if (o.incomplete && o.id in reachable) {
                partial = true
                if (o.sizeBytes == null) diags += Diagnostic("$.objects[${o.id}].sizeBytes", "不完整对象：sizeBytes 缺失，保留大小标记为不完全", severity = "warning")
                if (o.classId == null) diags += Diagnostic("$.objects[${o.id}].classId", "不完整对象：classId 缺失", severity = "warning")
            }
        }
        for (b in s.incompleteBlocks) {
            partial = true
            diags += Diagnostic("$.incompleteBlocks", "不完整块保留：$b", severity = "warning")
        }
        val total = analyses.filter { it.reachable && it.dominatorParent == null }.sumOf { it.retainedBytes }
        return AnalysisResult(
            if (partial) ComputeStatus.PARTIAL else ComputeStatus.OK,
            diags, analyses, total, judgments,
        )
    }

    /** CHK 迭代支配算法；返回 节点->支配父（虚拟根的直接子节点映射为 null）。 */
    fun dominators(reachable: Set<String>, succ: Map<String, List<String>>, roots: List<String>): Map<String, String?> {
        val ROOT = "\u0000root"
        val allSucc = HashMap<String, List<String>>()
        for (n in reachable) allSucc[n] = succ[n].orEmpty().filter { it in reachable }.sorted()
        allSucc[ROOT] = roots.filter { it in reachable }.sorted()
        val pred = HashMap<String, MutableList<String>>()
        for ((f, ts) in allSucc) for (t in ts) pred.getOrPut(t) { mutableListOf() }.add(f)

        // 迭代 DFS 求后序
        val post = mutableListOf<String>()
        val visited = HashSet<String>()
        val stack = ArrayDeque<Pair<String, Iterator<String>>>()
        visited.add(ROOT)
        stack.addLast(ROOT to allSucc[ROOT]!!.iterator())
        while (stack.isNotEmpty()) {
            val (_, it) = stack.last()
            if (it.hasNext()) {
                val m = it.next()
                if (visited.add(m)) stack.addLast(m to allSucc[m].orEmpty().iterator())
            } else {
                post.add(stack.last().first); stack.removeLast()
            }
        }
        val index = post.withIndex().associate { it.value to it.index }
        val idom = HashMap<String, String>()
        idom[ROOT] = ROOT
        fun intersect(a0: String, b0: String): String {
            var a = a0; var b = b0
            while (a != b) {
                while (index[a]!! < index[b]!!) a = idom[a]!!
                while (index[b]!! < index[a]!!) b = idom[b]!!
            }
            return a
        }
        var changed = true
        while (changed) {
            changed = false
            for (n in post.asReversed()) {
                if (n == ROOT) continue
                var newIdom: String? = null
                for (p in pred[n].orEmpty().sorted()) {
                    if (idom.containsKey(p)) newIdom = if (newIdom == null) p else intersect(newIdom!!, p)
                }
                if (newIdom != null && idom[n] != newIdom) { idom[n] = newIdom; changed = true }
            }
        }
        val out = LinkedHashMap<String, String?>()
        for (n in reachable.sorted()) {
            val p = idom[n]
            out[n] = if (p == ROOT || p == null) null else p
        }
        return out
    }
}

@Serializable
data class ClassGroup(val classId: String, val name: String?, val count: Int, val bytes: Long, val unknownSizes: Int)

@Serializable
data class ClassDiffEntry(
    val classId: String,
    val nameA: String?, val nameB: String?,
    val countA: Int, val countB: Int,
    val bytesA: Long, val bytesB: Long,
    val renamed: Boolean,           // 类改名但身份（classId）一致
    val onlyInA: Boolean = false, val onlyInB: Boolean = false,
)

@Serializable
data class ClassDiff(val snapshotA: String, val snapshotB: String, val entries: List<ClassDiffEntry>)

object ClassDiffer {
    fun groups(s: HeapSnapshot): List<ClassGroup> {
        val names = s.classes.associate { it.id to it.name }
        return s.objects.groupBy { it.classId ?: "<incomplete>" }.map { (cid, objs) ->
            ClassGroup(cid, names[cid], objs.size, objs.sumOf { it.sizeBytes ?: 0L }, objs.count { it.sizeBytes == null })
        }.sortedBy { it.classId }
    }

    /** 类群按 classId（稳定身份）对齐；改名不拆分组。 */
    fun diff(a: HeapSnapshot, b: HeapSnapshot): ClassDiff {
        val ga = groups(a).associateBy { it.classId }
        val gb = groups(b).associateBy { it.classId }
        val ids = (ga.keys + gb.keys).sorted()
        val entries = ids.map { id ->
            val x = ga[id]; val y = gb[id]
            ClassDiffEntry(
                classId = id,
                nameA = x?.name, nameB = y?.name,
                countA = x?.count ?: 0, countB = y?.count ?: 0,
                bytesA = x?.bytes ?: 0L, bytesB = y?.bytes ?: 0L,
                renamed = x != null && y != null && x.name != y.name,
                onlyInA = y == null, onlyInB = x == null,
            )
        }
        return ClassDiff(a.snapshotId, b.snapshotId, entries)
    }
}
