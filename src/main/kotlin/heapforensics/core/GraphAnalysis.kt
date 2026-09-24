package heapforensics.core

import kotlinx.serialization.Serializable

/**
 * 对象图分析。判定依据（机器可读地体现在结果结构中）：
 *  - 支配树只在可达图上构建：不可达对象没有支配父级，保留大小不参与任何支配子树。
 *  - 弱引用/内部边是否保活由 [AnalysisParams] 显式策略决定，不是解析器暗箱行为。
 *  - 多个等长 root 路径全部保留（[RootPath.alternatives] / 每对象路径列表），不任意裁剪。
 *  - 地址不是稳定身份：身份一律为 objectId；地址冲突只作为 [AddressCollision] 诊断呈现。
 */
object GraphAnalysis {

    const val SUPER_ROOT = "<gc-roots>"

    @Serializable
    data class Reachability(
        val reachable: List<String>,
        val unreachable: List<String>,
        val keptEdges: List<HeapEdge>,
        val droppedEdges: List<HeapEdge>,
        val visitOrder: List<String>,
    )

    @Serializable
    data class PathStep(
        val fromId: String,
        val toId: String,
        val kind: EdgeKind,
        val fieldName: String? = null,
    )

    @Serializable
    data class RootPath(
        val rootKind: RootKind,
        val steps: List<PathStep>,
    ) {
        val depth: Int get() = steps.size
        val endObject: String get() = steps.lastOrNull()?.toId ?: ""
    }

    @Serializable
    data class ObjectPaths(
        val objectId: String,
        val shortestDepth: Int,
        val paths: List<RootPath>,
        val truncated: Boolean,
    )

    @Serializable
    data class RetainedSize(
        val objectId: String,
        val dominated: List<String>,
        val lowerBoundBytes: Long,
        val exactBytes: Long?,
        val invalidReasons: List<String>,
    ) {
        val valid: Boolean get() = exactBytes != null
    }

    @Serializable
    data class DominatorInfo(
        val idom: Map<String, String?>,
        val retained: Map<String, RetainedSize>,
    )

    @Serializable
    data class AddressCollision(
        val address: String,
        val objectIds: List<String>,
        val note: String = "地址相同但身份不同：地址不是稳定身份，分析一律使用 objectId",
    )

    @Serializable
    data class LeakCandidate(
        val objectId: String,
        val retainedLowerBound: Long,
        val retainedExact: Long?,
        val dominatedCount: Int,
        val invalidReasons: List<String>,
        val shortestRootDepth: Int,
    )

    @Serializable
    data class FullAnalysis(
        val snapshotId: String,
        val params: AnalysisParams,
        val reachability: Reachability,
        val dominators: DominatorInfo,
        val paths: Map<String, ObjectPaths>,
        val addressCollisions: List<AddressCollision>,
        val leakCandidates: List<LeakCandidate>,
    )

    private fun kept(kind: EdgeKind, params: AnalysisParams): Boolean = when (kind) {
        EdgeKind.STRONG -> true
        EdgeKind.WEAK -> params.weakKeepAlive
        EdgeKind.INTERNAL -> params.internalKeepAlive
    }

    private fun sortedEdges(edges: List<HeapEdge>): List<HeapEdge> =
        edges.sortedWith(compareBy({ it.fromId }, { it.kind.ordinal }, { it.toId }, { it.fieldName ?: "" }))

    fun reachability(snap: HeapSnapshot, params: AnalysisParams): AnalysisResult<Reachability> {
        val keptEdges = sortedEdges(snap.edges.filter { kept(it.kind, params) })
        val droppedEdges = sortedEdges(snap.edges.filterNot { kept(it.kind, params) })
        val adj = keptEdges.groupBy { it.fromId }
        val known = snap.objectIndex
        val rootIds = snap.roots.map { it.objectId }.sorted()
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        for (r in rootIds) if (known.containsKey(r) && visited.add(r)) queue.add(r)
        var visits = 0
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            visits++
            if (visits > params.maxVisits) {
                return AnalysisResult.BudgetExhausted("maxVisits=${params.maxVisits}", "可达性遍历超出访问预算")
            }
            for (e in adj[cur].orEmpty()) {
                if (known.containsKey(e.toId) && visited.add(e.toId)) queue.add(e.toId)
            }
        }
        val reachable = visited.toList()
        val unreachable = snap.objects.map { it.objectId }.filter { it !in visited }.sorted()
        return AnalysisResult.Ok(Reachability(reachable, unreachable, keptEdges, droppedEdges, reachable))
    }

    /** 支配树只在可达图上构建（虚拟超级根 -> 各 root）。迭代数据流，顺序确定。 */
    fun dominators(snap: HeapSnapshot, params: AnalysisParams, reach: Reachability): AnalysisResult<DominatorInfo> {
        if (reach.reachable.isEmpty()) {
            return AnalysisResult.SemanticallyUnsolvable(
                listOf("可达集为空：没有可用 root 或 root 指向未知对象，支配树无定义")
            )
        }
        val nodes = reach.reachable.toSet()
        val roots = snap.roots.map { it.objectId }.filter { it in nodes }.sorted()
        val succ = mutableMapOf<String, MutableList<String>>()
        val pred = mutableMapOf<String, MutableList<String>>()
        fun link(a: String, b: String) {
            succ.getOrPut(a) { mutableListOf() }.add(b)
            pred.getOrPut(b) { mutableListOf() }.add(a)
        }
        for (r in roots) link(SUPER_ROOT, r)
        for (e in reach.keptEdges) {
            if (e.fromId in nodes && e.toId in nodes) link(e.fromId, e.toId)
        }
        // 反向逆后序（RPO），确定性顺序
        val allNodes = (listOf(SUPER_ROOT) + reach.reachable).toList()
        val index = allNodes.withIndex().associate { it.value to it.index }
        val sortedSucc = succ.mapValues { (_, v) -> v.distinct().sortedBy { index[it] ?: Int.MAX_VALUE } }
        val sortedPred = pred.mapValues { (_, v) -> v.distinct().sortedBy { index[it] ?: Int.MAX_VALUE } }

        val rpo = mutableListOf<String>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<Pair<String, Iterator<String>>>()
        seen.add(SUPER_ROOT)
        stack.add(SUPER_ROOT to (sortedSucc[SUPER_ROOT] ?: emptyList()).iterator())
        while (stack.isNotEmpty()) {
            val (node, it) = stack.last()
            if (it.hasNext()) {
                val nxt = it.next()
                if (seen.add(nxt)) stack.add(nxt to (sortedSucc[nxt] ?: emptyList()).iterator())
            } else {
                rpo.add(node)
                stack.removeLast()
            }
        }
        rpo.reverse()
        val rpoIndex = rpo.withIndex().associate { it.value to it.index }

        val idom = HashMap<String, String?>()
        idom[SUPER_ROOT] = null
        fun intersect(a0: String, b0: String): String {
            var a = a0; var b = b0
            while (a != b) {
                while ((rpoIndex[a] ?: Int.MAX_VALUE) > (rpoIndex[b] ?: Int.MAX_VALUE)) a = idom[a] ?: SUPER_ROOT
                while ((rpoIndex[b] ?: Int.MAX_VALUE) > (rpoIndex[a] ?: Int.MAX_VALUE)) b = idom[b] ?: SUPER_ROOT
            }
            return a
        }
        var changed = true
        while (changed) {
            changed = false
            for (n in rpo) {
                if (n == SUPER_ROOT) continue
                val ps = sortedPred[n].orEmpty().filter { it == SUPER_ROOT || idom.containsKey(it) }
                if (ps.isEmpty()) continue
                var newIdom = ps.first()
                for (p in ps.drop(1)) newIdom = intersect(newIdom, p)
                if (idom[n] != newIdom) { idom[n] = newIdom; changed = true }
            }
        }

        // 保留大小：支配子树字节和；不完整对象/未知类 -> 有效区间失效并附原因
        val children = HashMap<String, MutableList<String>>()
        for ((n, p) in idom) if (p != null) children.getOrPut(p) { mutableListOf() }.add(n)
        val retained = HashMap<String, RetainedSize>()
        fun compute(n: String): RetainedSize {
            retained[n]?.let { return it }
            val dominated = mutableListOf<String>()
            val reasons = mutableListOf<String>()
            var lower = 0L
            var exact = true
            fun visit(x: String) {
                if (x != SUPER_ROOT) {
                    dominated += x
                    val obj = snap.objectIndex[x]
                    when {
                        obj == null -> { exact = false; reasons += "对象 $x 缺少材料记录" }
                        obj.incomplete -> {
                            exact = false
                            lower += obj.sizeBytes
                            reasons += "对象 $x 标记为不完整（原始块保留于快照），保留大小为下界"
                        }
                        snap.classIndex[obj.classId] == null -> {
                            exact = false
                            lower += obj.sizeBytes
                            reasons += "对象 $x 的类 ${obj.classId} 元数据缺失，尺寸按原始记录计入下界"
                        }
                        else -> lower += obj.sizeBytes
                    }
                }
                for (c in children[x].orEmpty().sorted()) visit(c)
            }
            visit(n)
            val rs = RetainedSize(
                objectId = n,
                dominated = dominated.sorted(),
                lowerBoundBytes = lower,
                exactBytes = if (exact) lower else null,
                invalidReasons = reasons.distinct().sorted(),
            )
            retained[n] = rs
            return rs
        }
        for (n in rpo) compute(n)
        val idomOut = idom.filterKeys { it != SUPER_ROOT }.toSortedMap()
        val retainedOut = retained.filterKeys { it != SUPER_ROOT }
        return AnalysisResult.Ok(DominatorInfo(idomOut, retainedOut))
    }

    /** 全部最短 root 路径（等长全保留），每对象上限 [AnalysisParams.maxPathsPerObject]。 */
    fun rootPaths(snap: HeapSnapshot, params: AnalysisParams, reach: Reachability): AnalysisResult<Map<String, ObjectPaths>> {
        val nodes = reach.reachable.toSet()
        if (nodes.isEmpty()) {
            return AnalysisResult.SemanticallyUnsolvable(listOf("可达集为空，root 路径无定义"))
        }
        val adj = reach.keptEdges.filter { it.fromId in nodes && it.toId in nodes }.groupBy { it.fromId }
        val rootKindOf = snap.roots.associate { it.objectId to it.kind }
        val roots = snap.roots.map { it.objectId }.filter { it in nodes }.sorted()

        val depth = HashMap<String, Int>()
        val paths = HashMap<String, MutableList<RootPath>>()
        val truncated = HashSet<String>()
        val queue = ArrayDeque<String>()
        for (r in roots) {
            depth[r] = 0
            paths.getOrPut(r) { mutableListOf() }
            queue.add(r)
        }
        var steps = 0
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = depth[cur] ?: continue
            if (d + 1 > params.maxPathDepth) {
                return AnalysisResult.BudgetExhausted("maxPathDepth=${params.maxPathDepth}", "root 路径深度超出预算")
            }
            for (e in adj[cur].orEmpty()) {
                steps++
                if (steps > params.maxVisits * 4) {
                    return AnalysisResult.BudgetExhausted("maxVisits=${params.maxVisits}", "路径枚举步数超出预算")
                }
                val tgt = e.toId
                val existing = depth[tgt]
                if (existing == null) {
                    depth[tgt] = d + 1
                    queue.add(tgt)
                } else if (existing != d + 1) {
                    continue // 只保留等长（最短）路径
                }
                val list = paths.getOrPut(tgt) { mutableListOf() }
                val parents = paths[cur].orEmpty()
                val candidates: List<RootPath> = if (parents.isEmpty()) {
                    listOf(RootPath(rootKindOf[cur] ?: RootKind.OTHER, listOf(PathStep(cur, tgt, e.kind, e.fieldName))))
                } else {
                    parents.map { RootPath(it.rootKind, it.steps + PathStep(cur, tgt, e.kind, e.fieldName)) }
                }
                for (c in candidates) {
                    if (list.size >= params.maxPathsPerObject) { truncated.add(tgt); break }
                    if (list.none { it.steps == c.steps && it.rootKind == c.rootKind }) list.add(c)
                }
            }
        }
        val out = depth.keys.sorted().associateWith { id ->
            val ps = (paths[id] ?: mutableListOf()).sortedWith(
                compareBy({ it.steps.size }, { it.rootKind.ordinal }, { it.steps.joinToString(",") { s -> s.fromId } })
            )
            ObjectPaths(id, depth[id] ?: 0, ps, id in truncated)
        }
        return AnalysisResult.Ok(out)
    }

    fun addressCollisions(snap: HeapSnapshot): List<AddressCollision> =
        snap.objects.groupBy { it.address }
            .filterValues { it.size > 1 }
            .toSortedMap()
            .map { (addr, objs) -> AddressCollision(addr, objs.map { it.objectId }.sorted()) }

    fun leakCandidates(
        params: AnalysisParams,
        dom: DominatorInfo,
        paths: Map<String, ObjectPaths>,
        roots: List<RootRef>,
    ): List<LeakCandidate> {
        val rootIds = roots.map { it.objectId }.toSet()
        return dom.retained.values
            .filter { it.objectId !in rootIds && it.lowerBoundBytes >= params.leakThresholdBytes && it.dominated.size >= 2 }
            .map {
                LeakCandidate(
                    objectId = it.objectId,
                    retainedLowerBound = it.lowerBoundBytes,
                    retainedExact = it.exactBytes,
                    dominatedCount = it.dominated.size,
                    invalidReasons = it.invalidReasons,
                    shortestRootDepth = paths[it.objectId]?.shortestDepth ?: -1,
                )
            }
            .sortedWith(compareByDescending<LeakCandidate> { it.retainedLowerBound }.thenBy { it.objectId })
    }

    fun analyze(snap: HeapSnapshot, params: AnalysisParams): AnalysisResult<FullAnalysis> {
        return reachability(snap, params).flatMap { reach ->
            dominators(snap, params, reach).flatMap { dom ->
                rootPaths(snap, params, reach).map { paths ->
                    FullAnalysis(
                        snapshotId = snap.snapshotId,
                        params = params,
                        reachability = reach,
                        dominators = dom,
                        paths = paths,
                        addressCollisions = addressCollisions(snap),
                        leakCandidates = leakCandidates(params, dom, paths, snap.roots),
                    )
                }
            }
        }
    }
}
