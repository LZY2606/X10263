package heaplab.analysis

import heaplab.model.*

/** 一次可达性遍历的结果：可达集、每个对象的 root 距离、被弱/软边排除的边。 */
data class ReachabilityResult(
    val reachable: Set<String>,
    /** 从任一 root 到对象的最短边数；不可达对象不在 map 中。 */
    val distance: Map<String, Int>,
    val roots: List<String>,
    /** 因弱引用策略而不参与保活遍历的边（保留用于取证展示）。 */
    val excludedEdges: List<String>,
    /** 用于本次遍历的邻接表（仅活动边），稳定有序。 */
    val adjacency: Map<String, List<EdgeTarget>>,
    val budgetUsedNodes: Long,
    val budgetUsedEdges: Long
)

data class EdgeTarget(val edgeId: String, val to: String, val kind: EdgeKind)

object Reachability {
    /**
     * 只在显式策略下决定弱引用是否保活：
     *  - KEEP_ALIVE：STRONG/INTERNAL/SOFT/WEAK 都参与遍历；
     *  - DO_NOT_KEEP_ALIVE：只有 STRONG/INTERNAL 参与，WEAK/SOFT 目标可能不可达。
     * 预算耗尽抛 BudgetExhaustedException（与格式损坏、语义无解三种结果互斥）。
     */
    fun compute(snapshot: Snapshot, params: AnalysisParams): ReachabilityResult {
        val activeKinds = when (params.weakPolicy) {
            WeakPolicy.KEEP_ALIVE -> setOf(EdgeKind.STRONG, EdgeKind.INTERNAL, EdgeKind.WEAK, EdgeKind.SOFT)
            WeakPolicy.DO_NOT_KEEP_ALIVE -> setOf(EdgeKind.STRONG, EdgeKind.INTERNAL)
        }
        val excluded = mutableListOf<String>()
        // 稳定邻接表：按 (to, edgeId) 排序，保证等价输入得到稳定遍历顺序。
        val adjacency = LinkedHashMap<String, MutableList<EdgeTarget>>()
        var usedEdges = 0L
        for (e in snapshot.edges.sortedWith(compareBy({ it.fromObjectId }, { it.toObjectId }, { it.edgeId }))) {
            if (usedEdges++ >= params.edgeBudget)
                throw BudgetExhaustedException("edgeBudget", usedEdges, params.edgeBudget)
            if (e.kind !in activeKinds) {
                excluded += e.edgeId
                continue
            }
            // 悬空边不参与图（诊断已在材料层保留）。
            if (snapshot.objectById[e.fromObjectId] == null || snapshot.objectById[e.toObjectId] == null)
                continue
            adjacency.getOrPut(e.fromObjectId) { mutableListOf() }
                .add(EdgeTarget(e.edgeId, e.toObjectId, e.kind))
        }
        adjacency.values.forEach { it.sortWith(compareBy({ it.to }, { it.edgeId })) }

        val roots = snapshot.objects
            .filter { it.rootKind != RootKind.NON_ROOT && it.rootKind != RootKind.UNKNOWN }
            .map { it.rawObjectId }
            .sorted()

        val distance = LinkedHashMap<String, Int>()
        val queue = ArrayDeque<String>()
        for (r in roots) {
            if (distance.putIfAbsent(r, 0) == null) queue.add(r)
        }
        var usedNodes = 0L
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (usedNodes++ >= params.nodeBudget)
                throw BudgetExhaustedException("nodeBudget", usedNodes, params.nodeBudget)
            val d = distance.getValue(cur)
            for (t in adjacency[cur].orEmpty()) {
                if (distance.putIfAbsent(t.to, d + 1) == null) queue.add(t.to)
            }
        }
        // UNKNOWN root 分类不作为保活 root（语义不确定，留在诊断里）。
        return ReachabilityResult(
            reachable = distance.keys,
            distance = distance,
            roots = roots,
            excludedEdges = excluded.sorted(),
            adjacency = adjacency,
            budgetUsedNodes = usedNodes,
            budgetUsedEdges = usedEdges
        )
    }
}
