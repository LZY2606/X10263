package lab.heap.engine

import lab.heap.domain.*

/** 内存图节点（稳定身份永远走 objectId，地址仅用于展示/冲突报告）。 */
class GNode(
    val ordinal: Int,
    val objectId: String,
    val obj: RawObject,
    /** 出边，按 (ordinal, edgeId) 稳定排序。 */
    val out: MutableList<GEdge> = mutableListOf(),
    val incoming: MutableList<GEdge> = mutableListOf()
) {
    val stableId get() = obj.let { "" } // placeholder, see stableOf
}

data class GEdge(val edge: RawEdge, val from: GNode, val to: GNode)

data class MemoryGraph(
    val material: SnapshotMaterial,
    val nodes: Map<String, GNode>,
    /** 重复地址（地址不是稳定身份；出现重复时给出诊断性标记）。 */
    val duplicateAddresses: Set<String>
)

fun stableIdOf(snapshotId: String, objectId: String) = "$snapshotId#$objectId"

object GraphBuilder {
    fun build(material: SnapshotMaterial): MemoryGraph {
        val nodes = LinkedHashMap<String, GNode>()
        material.objects.forEach { o ->
            nodes[o.objectId] = GNode(o.ordinal, o.objectId, o)
        }
        val byId = nodes
        material.edges.forEach { e ->
            val f = byId[e.fromObjectId]
            val t = byId[e.toObjectId]
            if (f != null && t != null) {
                val ge = GEdge(e, f, t)
                f.out += ge
                t.incoming += ge
            }
        }
        nodes.values.forEach {
            it.out.sortWith(compareBy({ it.edge.ordinal }, { it.edge.edgeId }))
            it.incoming.sortWith(compareBy({ it.edge.ordinal }, { it.edge.edgeId }))
        }
        val dup = material.objects.groupingBy { it.address }
            .eachCount().filterValues { it > 1 }.keys
        return MemoryGraph(material, nodes, dup)
    }
}

/** 可达性：弱/内部边是否参与由显式策略决定。策略必须由调用方提供。 */
object Reachability {
    data class Result(val reachable: Set<String>, val weaklyReachableOnly: Set<String>)

    fun reachable(graph: MemoryGraph, params: AnalysisParams): Result {
        val rootIds = graph.material.roots
            .filter { it.kind in params.rootKinds }
            .map { it.objectId }
            .filter { graph.nodes.containsKey(it) }
            .toSortedSet()

        val strong = HashSet<String>()
        val dq = ArrayDeque<String>()
        rootIds.forEach { if (strong.add(it)) dq += it }
        while (dq.isNotEmpty()) {
            val id = dq.removeFirst()
            for (e in graph.nodes.getValue(id).out) {
                val traversable = when (e.edge.kind) {
                    EdgeKind.STRONG -> true
                    EdgeKind.WEAK -> params.weakPolicy == WeakPolicy.KEEP_ALIVE
                    EdgeKind.INTERNAL -> true
                }
                if (traversable && strong.add(e.to.objectId)) dq += e.to.objectId
            }
        }
        // 仅经弱引用可达：强策略下标记，供“是否保活”的差异审查
        val weakOnly = HashSet<String>()
        if (params.weakPolicy == WeakPolicy.DROP_FOR_REACHABILITY) {
            val seeded = HashSet(strong)
            val q2 = ArrayDeque<String>()
            seeded.forEach { q2 += it }
            while (q2.isNotEmpty()) {
                val id = q2.removeFirst()
                for (e in graph.nodes.getValue(id).out) {
                    if (e.edge.kind == EdgeKind.WEAK || e.edge.kind == EdgeKind.INTERNAL) {
                        if (e.to.objectId !in strong && weakOnly.add(e.to.objectId)) q2 += e.to.objectId
                    }
                }
            }
        }
        return Result(strong, weakOnly)
    }
}
