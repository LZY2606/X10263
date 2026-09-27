package hsf.analysis

import hsf.model.*

/**
 * Reachability + dominator tree (Lengauer-Tarjan) + retained sizes.
 *
 * Hard rules encoded here:
 *  - the dominator tree is built ONLY over the graph reachable from roots
 *    under the explicit weak-reference policy;
 *  - weak edges keep objects alive only under [WeakPolicy.KEEP_ALIVE];
 *  - when an object has several shortest root paths of equal length, ALL are
 *    retained (no arbitrary winner);
 *  - addresses never participate in identity - graph keys are object ids.
 */
class GraphEngine(private val budget: NodeEdgeBudget = NodeEdgeBudget()) {

    data class NodeEdgeBudget(val maxNodes: Int = 200_000, val maxEdges: Int = 1_000_000)

    class BudgetExceeded(val which: String, val limit: Int) :
        RuntimeException("resource budget exhausted: $which limit $limit")

    data class Graph(
        /** node index -> global identity; 0 is the virtual super-root. */
        val identities: List<String>,
        val indexOf: Map<String, Int>,
        /** adjacency of edges participating in reachability. */
        val adj: List<List<Int>>,
        /** reverse adjacency, rebuilt lazily after reachability is known. */
        val edges: List<EdgeView>,
        val rootNodeKinds: Map<Int, Set<RootKind>>
    )

    data class DomResult(val idom: IntArray, val reachable: Set<Int>)

    fun buildGraph(snap: HeapSnapshot, params: AnalysisParams): Graph {
        if (snap.objects.size > (params.maxNodes.takeIf { it <= budget.maxNodes } ?: budget.maxNodes))
            throw BudgetExceeded("nodes", budget.maxNodes)
        if (snap.edges.size > (params.maxEdges.takeIf { it <= budget.maxEdges } ?: budget.maxEdges))
            throw BudgetExceeded("edges", budget.maxEdges)

        val prefix = snap.snapshotId + "#"
        val indexOf = HashMap<String, Int>()
        val identities = mutableListOf("VIRTUAL_ROOT")
        snap.objects.forEach { o ->
            indexOf[prefix + o.objectId] = identities.size
            identities += prefix + o.objectId
        }
        val keepWeak = params.weakPolicy == WeakPolicy.KEEP_ALIVE
        val adj = Array(identities.size) { mutableListOf<Int>() }
        val edgeViews = mutableListOf<EdgeView>()
        for (e in snap.edges) {
            val a = indexOf[prefix + e.fromObjectId] ?: continue
            val b = indexOf[prefix + e.toObjectId] ?: continue
            val participates = e.kind != EdgeKind.WEAK || keepWeak
            if (participates) adj[a] += b
            edgeViews += EdgeView(
                identity = prefix + e.edgeId, from = identities[a], to = identities[b],
                kind = e.kind, field = e.fieldRaw, participatesInReachability = participates
            )
        }
        val rootKinds = HashMap<Int, MutableSet<RootKind>>()
        for (r in snap.roots) {
            val n = indexOf[prefix + r.objectId] ?: continue
            adj[0] += n
            rootKinds.getOrPut(n) { mutableSetOf() } += r.kind
        }
        return Graph(identities, indexOf, adj.map { it }, edgeViews, rootKinds)
    }

    fun reachableAndDominators(graph: Graph): DomResult {
        val n = graph.identities.size
        val reachable = bfs(graph)
        // Lengauer-Tarjan over reachable nodes only, starting at super-root 0.
        val succReach = graph.adj.map { row -> row.filter { it in reachable } }
        val preds = reverseAdj(succReach, reachable)
        return DomResult(lengauerTarjan(0, n, succReach, preds), reachable)
    }

    private fun bfs(graph: Graph): Set<Int> {
        val seen = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        seen += 0
        queue += 0
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in graph.adj[u]) if (seen.add(v)) queue += v
        }
        return seen
    }

    private fun reverseAdj(succ: List<List<Int>>, reachable: Set<Int>): List<List<Int>> {
        val rev = Array(succ.size) { mutableListOf<Int>() }
        for (u in reachable) for (v in succ[u]) if (v in reachable) rev[v] += u
        return rev.map { it }
    }

    private fun lengauerTarjan(root: Int, n: Int, succ: List<List<Int>>, pred: List<List<Int>>): IntArray {
        val inf = Int.MAX_VALUE
        val parent = IntArray(n) { -1 }
        val semi = IntArray(n) { inf }
        val vertex = IntArray(n) { -1 }
        val label = IntArray(n) { it }
        val ancestor = IntArray(n) { -1 }
        val bucket = Array(n) { mutableListOf<Int>() }
        val idom = IntArray(n) { -1 }

        var dfn = 0
        fun dfs(v: Int) {
            semi[v] = dfn
            vertex[dfn] = v
            label[v] = v
            dfn++
            // Stable order: equivalent inputs must produce equivalent sequence.
            for (w in succ[v].sorted()) {
                if (semi[w] == inf) { parent[w] = v; dfs(w) }
            }
        }
        dfs(root)

        fun compress(v: Int) {
            val a = ancestor[v]
            if (a != -1 && ancestor[a] != -1) {
                compress(a)
                if (semi[label[a]] < semi[label[v]]) label[v] = label[a]
                ancestor[v] = ancestor[a]
            }
        }
        fun eval(v: Int): Int {
            if (ancestor[v] == -1) return v
            compress(v)
            return label[v]
        }

        for (i in dfn - 1 downTo 1) {
            val w = vertex[i]
            for (v in pred[w]) {
                if (semi[v] == inf) continue
                val u = eval(v)
                if (semi[u] < semi[w]) semi[w] = semi[u]
            }
            bucket[vertex[semi[w]]] += w
            val pw = parent[w]
            ancestor[w] = pw
            for (v in bucket[pw]) {
                val u = eval(v)
                idom[v] = if (semi[u] < semi[v]) u else pw
            }
            bucket[pw].clear()
        }
        for (i in 1 until dfn) {
            val w = vertex[i]
            if (idom[w] != vertex[semi[w]]) idom[w] = idom[idom[w]]
        }
        idom[root] = -1
        return idom
    }

    /**
     * ALL shortest root paths for a node. Equal-length ties are all returned.
     */
    fun shortestRootPaths(graph: Graph, target: Int, reachable: Set<Int>): List<RootPath> {
        if (target !in reachable || target == 0) return emptyList()
        val dist = HashMap<Int, Int>()
        val queue = ArrayDeque<Int>()
        dist[0] = 0
        queue += 0
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            if (u == target) break
            val d = dist[u]!!
            for (v in graph.adj[u]) if (v in reachable && v !in dist) {
                dist[v] = d + 1; queue += v
            }
        }
        val td = dist[target] ?: return emptyList()
        // Walk backward enumerating every predecessor at distance d-1.
        val paths = mutableListOf<RootPath>()
        fun walk(node: Int, nodesRev: MutableList<Int>) {
            if (node == 0) {
                val nodes = nodesRev.asReversed().toList()
                val edges = mutableListOf<String>()
                for (i in 0 until nodes.size - 1) edges += edgeKey(nodes[i], nodes[i + 1])
                val rootNode = nodes.first()
                paths += RootPath(
                    rootObject = graph.identities[rootNode],
                    rootKind = graph.rootNodeKinds[rootNode]?.firstOrNull() ?: RootKind.UNCLASSIFIED,
                    edges = edges, length = edges.size
                )
                return
            }
            val d = dist[node]!!
            val preds = mutableListOf<Int>()
            for (u in dist.keys) if (dist[u] == d - 1) {
                if (graph.adj[u].contains(node)) preds += u
            }
            for (p in preds) {
                nodesRev += p
                walk(p, nodesRev)
                nodesRev.removeAt(nodesRev.size - 1)
            }
        }
        walk(target, mutableListOf(target))
        return paths.sortedWith(compareBy({ it.rootObject }, { it.edges.joinToString(",") }))
    }

    private fun edgeKey(a: Int, b: Int) = "edge:$a->$b"

    /** Retained size = sum of shallow sizes of the node's dominated subtree. */
    fun retainedSizes(
        graph: Graph, dom: IntArray, reachable: Set<Int>,
        shallow: Map<String, Long?>
    ): Map<String, Long?> {
        val children = Array(graph.identities.size) { mutableListOf<Int>() }
        for (v in reachable) if (v != 0 && dom[v] >= 0) children[dom[v]] += v
        val result = HashMap<String, Long?>()
        fun subtreeSum(v: Int): Long? {
            var anyUnconvertible = false
            var sum = 0L
            val own = shallow[graph.identities[v]]
            if (v != 0) {
                if (own == null) anyUnconvertible = true else sum += own
            }
            for (c in children[v]) {
                val s = subtreeSum(c)
                if (s == null) anyUnconvertible = true else sum += s
            }
            if (v != 0) result[graph.identities[v]] = if (anyUnconvertible) null else sum
            return if (anyUnconvertible) null else sum
        }
        subtreeSum(0)
        return result
    }
}
