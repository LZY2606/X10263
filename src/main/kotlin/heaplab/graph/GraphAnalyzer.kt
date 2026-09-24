package heaplab.graph

import heaplab.model.Diagnostic
import heaplab.model.DiagnosticKind
import heaplab.model.ParsedSnapshot

data class CandidateBudget(val maxCandidates: Int)

data class PathOverrun(
    val objectId: String,
    val candidateCount: Int,
    val retainedCount: Int,
)

/**
 * Pure derivation layer. Inputs are immutable parsed snapshots; outputs carry the
 * input sha, parser version and parameter version for provenance.
 */
class GraphAnalyzer(private val candidateBudget: CandidateBudget = CandidateBudget(64)) {

    fun derive(snapshot: ParsedSnapshot, params: DerivationParams): GraphResult {
        val policy = params.policy()
        val warnings = snapshot.diagnostics.filter { it.kind == DiagnosticKind.WARNING }.toMutableList()

        val ids = snapshot.objects.map { it.objectId }.toSortedSet()
        val adjacency = HashMap<String, MutableList<EdgeRef>>()
        for (e in snapshot.edges) {
            if (e.fromObjectId !in ids || e.toObjectId !in ids) continue
            if (e.kind == "WEAK" && policy == WeakPolicy.DROP) continue
            adjacency.getOrPut(e.fromObjectId) { mutableListOf() }
                .add(EdgeRef(e.toObjectId, e.edgeId, e.kind))
        }
        for (list in adjacency.values) {
            list.sortWith(compareBy({ it.toId }, { it.edgeId }))
        }

        val rootTargets = snapshot.roots
            .filter { it.objectId in ids }
            .sortedWith(compareBy({ it.objectId }, { it.rootId }))
        val rootCategory = snapshot.roots.associate { it.rootId to it.category }

        rootCategoryRef = rootCategory
        val reach = bfsShortestPaths(rootTargets.map { it.rootId to it.objectId }, adjacency)
        val overruns = mutableListOf<PathOverrun>()
        val nodes = HashMap<String, NodeDerivation>()

        val shallow = HashMap<String, Long?>()
        for (o in snapshot.objects) {
            val bytes = normalizeBytes(o.sizeRaw, o.unitRaw)
            shallow[o.objectId] = bytes
        }

        val reachableIds = reach.distances.keys.sorted()
        val virtualRoot = "\u0000ROOT\u0000"
        val idom = LengauerTarjan(
            vertexSet = (reachableIds + virtualRoot).toSortedSet(),
            entry = virtualRoot,
            outgoing = { v ->
                if (v == virtualRoot) {
                    rootTargets.map { it.objectId }.distinct().sorted()
                } else {
                    (adjacency[v]?.map { it.toId } ?: emptyList())
                        .filter { it in reach.distances }
                }
            },
        ).idom()

        // retained size: subtree of dominator tree, virtual root excluded
        val children = HashMap<String, MutableList<String>>()
        for (v in reachableIds) {
            val parent = idom[v] ?: continue
            children.getOrPut(parent) { mutableListOf() }.add(v)
        }
        val retained = HashMap<String, Long>()
        fun compute(v: String): Long {
            var total = shallow[v] ?: 0L
            for (c in children[v] ?: emptyList()) total += compute(c)
            retained[v] = total
            return total
        }
        for (child in children[virtualRoot].orEmpty()) compute(child)

        for (id in ids) {
            val dist = reach.distances[id]
            val paths = if (dist == null) emptyList() else {
                val all = reach.pathsById(id)
                if (all.size > candidateBudget.maxCandidates) {
                    overruns.add(PathOverrun(id, all.size, candidateBudget.maxCandidates))
                    warnings.add(
                        Diagnostic(
                            DiagnosticKind.WARNING, "PATH_BUDGET_CAPPED",
                            "object '$id' has ${all.size} shortest root paths; " +
                                "retained first ${candidateBudget.maxCandidates} in stable order",
                            0, 0, 0L, blockId = id,
                        ),
                    )
                    all.take(candidateBudget.maxCandidates)
                } else all
            }
            val sizeOk = snapshot.objects.first { it.objectId == id }.let {
                normalizeBytes(it.sizeRaw, it.unitRaw) != null
            }
            nodes[id] = NodeDerivation(
                objectId = id,
                reachable = dist != null,
                distanceFromRoot = dist,
                rootPaths = paths,
                dominatorParentId = idom[id]?.takeUnless { it == virtualRoot },
                retainedSizeBytes = if (dist != null) retained[id] else null,
                shallowSizeBytes = shallow[id],
                sizeNormalized = sizeOk,
            )
        }

        val total = reachableIds.sumOf { shallow[it] ?: 0L }
        return GraphResult(
            derived = DerivedSnapshot(
                snapshotId = snapshot.snapshotId,
                params = params,
                nodes = nodes.values.sortedBy { it.objectId },
                reachableCount = reachableIds.size,
                totalRetainedBytes = total,
                inputSha256 = snapshot.sourceSha256,
                parserVersion = snapshot.parserVersion,
                warnings = warnings.sortedWith(compareBy({ it.line }, { it.code })),
            ),
            pathOverruns = overruns,
            adjacency = adjacency,
            rootEdges = rootTargets,
            rootCategory = rootCategory,
        )
    }

    data class EdgeRef(val toId: String, val edgeId: String, val kind: String)

    data class PathPred(val from: String, val edgeId: String)

    private data class ShortestResult(
        val distances: Map<String, Int>,
        val predecessors: Map<String, List<PathPred>>,
        val rootParents: Map<String, List<String>>,
        val rootCategory: Map<String, String>,
    ) {
        fun pathsById(id: String): List<RootPath> {
            val out = mutableListOf<RootPath>()

            fun walk(cur: String, edges: List<String>, nodes: List<String>, rootId: String?) {
                val rp = rootParents[cur]
                val inner = predecessors[cur]
                if (rootId != null && rootId.isNotEmpty()) {
                    // cur is a direct root target reached via this root record
                    out.add(
                        RootPath(
                            rootId = rootId,
                            rootCategory = rootCategory[rootId].orEmpty(),
                            edgeIds = edges,
                            objectIds = listOf(cur) + nodes,
                            length = edges.size,
                        ),
                    )
                    return
                }
                if (!rp.isNullOrEmpty()) {
                    for (rid in rp) {
                        out.add(
                            RootPath(
                                rootId = rid,
                                rootCategory = rootCategory[rid].orEmpty(),
                                edgeIds = edges,
                                objectIds = listOf(cur) + nodes,
                                length = edges.size,
                            ),
                        )
                    }
                    return
                }
                if (inner.isNullOrEmpty()) return
                for (pred in inner) {
                    walk(pred.from, edges + pred.edgeId, listOf(cur) + nodes, null)
                }
            }

            walk(id, emptyList(), emptyList(), null)
            return out.distinctBy { it.rootId to it.edgeIds.joinToString(",") }
                .sortedWith(compareBy({ it.rootId }, { it.edgeIds.joinToString(",") }))
        }
    }

    private fun bfsShortestPaths(
        roots: List<Pair<String, String>>,
        adjacency: Map<String, List<GraphAnalyzer.EdgeRef>>,
    ): ShortestResult {
        val dist = HashMap<String, Int>()
        val preds = HashMap<String, MutableList<PathPred>>()
        val rootParents = HashMap<String, MutableList<String>>()
        val category = HashMap<String, String>()
        roots.toMap()
        val queue = ArrayDeque<String>()

        // roots is (rootRecordId, objectId)
        val rootRecordCategory = rootCategoryRef
        for ((rootRecordId, objectId) in roots) {
            category[rootRecordId] = rootRecordCategory[rootRecordId].orEmpty()
            rootParents.getOrPut(objectId) { mutableListOf() }.add(rootRecordId)
            if (objectId !in dist) {
                dist[objectId] = 0
                queue.add(objectId)
            }
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val d = dist[cur]!!
            for (edge in adjacency[cur].orEmpty()) {
                val nd = d + 1
                val known = dist[edge.toId]
                when {
                    known == null -> {
                        dist[edge.toId] = nd
                        preds[edge.toId] = mutableListOf(PathPred(cur, edge.edgeId))
                        queue.add(edge.toId)
                    }
                    known == nd -> preds[edge.toId]!!.add(PathPred(cur, edge.edgeId))
                }
            }
        }
        val cleanPreds = preds.mapValues { (_, list) ->
            list.distinctBy { it.from to it.edgeId }
                .sortedWith(compareBy({ it.from }, { it.edgeId }))
        }
        val cleanRoots = rootParents.mapValues { (_, list) -> list.distinct().sorted() }
        return ShortestResult(dist, cleanPreds, cleanRoots, category)
    }

    private var rootCategoryRef: Map<String, String> = emptyMap()

    companion object {
        const val ROOT_MARKER = "\u0000ROOTMARK\u0000"

        fun normalizeBytes(sizeRaw: String, unitRaw: String): Long? {
            val n = sizeRaw.toLongOrNull() ?: return null
            return when (unitRaw.lowercase()) {
                "", "b", "bytes", "byte" -> n
                "kb", "kib" -> n * 1024
                "mb", "mib" -> n * 1024 * 1024
                else -> null
            }
        }
    }
}

data class GraphResult(
    val derived: DerivedSnapshot,
    val pathOverruns: List<GraphAnalyzer.PathOverrun>,
    val adjacency: Map<String, List<GraphAnalyzer.EdgeRef>>,
    val rootEdges: List<Pair<String, String>>,
    val rootCategory: Map<String, String>,
)
