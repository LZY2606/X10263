package heaplab.analysis

import heaplab.model.BudgetExhaustedException

/**
 * 一条从 GC root 到目标的路径。
 * @param objectIds root -> ... -> target
 * @param edgeIds 与每个跳转对齐，长度 = objectIds.size - 1
 * @param rootKind 路径起点的 root 分类
 */
data class RootPath(
    val objectIds: List<String>,
    val edgeIds: List<String>,
    val rootId: String,
    val length: Int
)

object RootPaths {
    /**
     * 枚举 root -> target 的**全部最短（等长）路径**。
     *
     * 多个等长 root 路径必须全部保留（不能因存在任一可达路径就丢弃其余），
     * 无法唯一决定时这些合法候选整体返回。候选数超过 maxCandidates 属于
     * 资源预算耗尽（与格式损坏/语义无解互斥），而不是悄悄截断。
     */
    fun allShortest(
        target: String,
        reach: ReachabilityResult,
        maxCandidates: Int
    ): List<RootPath> {
        if (target !in reach.reachable) return emptyList()
        val targetDist = reach.distance.getValue(target)
        val rootSet = reach.roots.toSet()

        // 在 BFS 分层 DAG 上回溯：只保留 dist[next] == dist[cur]-1 的活动入边。
        val dist = reach.distance
        // 反向邻接（稳定有序）。
        val rev = LinkedHashMap<String, MutableList<EdgeTarget>>()
        for ((u, outs) in reach.adjacency) {
            for (e in outs) rev.getOrPut(e.to) { mutableListOf() }.add(EdgeTarget(e.edgeId, u, e.kind))
        }
        rev.values.forEach { it.sortWith(compareBy({ it.to }, { it.edgeId })) }

        val results = ArrayList<RootPath>()
        // DFS 自底向上拼路径；objects 反向顺序、edges 反向顺序。
        fun dfs(node: String, accObjects: ArrayDeque<String>, accEdges: ArrayDeque<String>) {
            if (results.size > maxCandidates)
                throw BudgetExhaustedException("maxPathCandidates", results.size.toLong(), maxCandidates.toLong())
            accObjects.addFirst(node)
            if (node in rootSet && dist.getValue(node) == 0) {
                results.add(RootPath(accObjects.toList(), accEdges.toList(), node, targetDist))
                accObjects.removeFirst()
                return
            }
            val d = dist.getValue(node)
            val preds = rev[node].orEmpty().filter { dist[it.to] == d - 1 }
            for (pe in preds) {
                accEdges.addFirst(pe.edgeId)
                dfs(pe.to, accObjects, accEdges)
                accEdges.removeFirst()
            }
            accObjects.removeFirst()
        }
        dfs(target, ArrayDeque(), ArrayDeque())
        // 稳定排序：按对象序列字典序。
        return results.sortedWith(compareBy({ it.rootId }, { it.objectIds.joinToString(",") }))
    }
}
