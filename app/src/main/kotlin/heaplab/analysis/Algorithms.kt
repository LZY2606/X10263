package heaplab.analysis

import heaplab.model.RawSnapshot

class AnalysisBudget(
    val maxNodes: Int = 200_000,
    val maxEdges: Int = 1_000_000,
    val maxPathEnumeration: Int = 50_000
) {
    fun check(nodes: Int, edges: Int): AnalysisOutcome? = when {
        nodes > maxNodes -> AnalysisOutcome.BudgetExhausted(
            "对象数 $nodes 超过预算 $maxNodes",
            listOf(Located(0, 0, "BUDGET_EXHAUSTED", "节点预算耗尽", ""))
        )
        edges > maxEdges -> AnalysisOutcome.BudgetExhausted(
            "边数 $edges 超过预算 $maxEdges",
            listOf(Located(0, 0, "BUDGET_EXHAUSTED", "边预算耗尽", ""))
        )
        else -> null
    }
}

/**
 * Lengauer–Tarjan 支配（立即支配者）算法。
 * 输入：虚拟根 root 与后继表 succ（仅含可达节点、参与支配的边）。
 * 输出：node -> idom，root 自身映射为 null。
 * 后继顺序按节点身份稳定排序，保证等价输入得到稳定顺序。
 */
class LengauerTarjan(
    private val root: String,
    succIn: Map<String, List<String>>
) {
    private val succ: Map<String, List<String>> =
        succIn.mapValues { (_, v) -> v.sorted() }
    private val pred = LinkedHashMap<String, MutableList<String>>()

    private val dfs = LinkedHashMap<String, Int>()      // vertex -> dfn
    private val vertex = mutableListOf<String?>()       // dfn -> vertex
    private val parent = LinkedHashMap<String, String>()
    private val semi = LinkedHashMap<String, Int>()
    private val idom = LinkedHashMap<String, String?>()
    private val ancestor = LinkedHashMap<String, String?>()
    private val label = LinkedHashMap<String, String>()
    private val bucket = LinkedHashMap<String, MutableList<String>>()

    init {
        for (u in succ.keys) {
            pred[u] = mutableListOf()
            bucket[u] = mutableListOf()
            semi[u] = -1
            idom[u] = null
            ancestor[u] = null
            label[u] = u
        }
        vertex.add(null) // dfn 从 1 开始
        for ((u, vs) in succ) for (v in vs) {
            pred.getOrPut(v) { mutableListOf() }.add(u)
            if (v !in semi) { semi[v] = -1; bucket.getOrPut(v) { mutableListOf() } }
        }
    }

    fun compute(): Map<String, String?> {
        dfsVisit(root)
        val n = vertex.size - 1
        for (i in n downTo 2) {
            val w = vertex[i]!!
            for (v in pred[w].orEmpty()) {
                if (v !in dfs) continue
                val u = eval(v)
                if (semi[u]!! < semi[w]!!) semi[w] = semi[u]
            }
            bucket[vertex[semi[w]!!]]!!.add(w)
            link(parent[w]!!, w)
            for (v in bucket[parent[w]!!]!!.toList()) {
                val u = eval(v)
                idom[v] = if (semi[u]!! < semi[v]!!) u else parent[w]
            }
            bucket[parent[w]!!]!!.clear()
        }
        for (i in 2..n) {
            val w = vertex[i]!!
            if (idom[w] != vertex[semi[w]!!]) idom[w] = idom[idom[w]!!]
        }
        idom[root] = null
        return idom.toMap()
    }

    private fun dfsVisit(start: String) {
        val stack = ArrayDeque<Pair<String, Int>>()
        dfs[start] = vertex.size
        vertex.add(start)
        semi[start] = dfs[start]!!
        stack.addLast(start to 0)
        while (stack.isNotEmpty()) {
            val (u, idx) = stack.removeLast()
            val neighbors = succ[u].orEmpty()
            if (idx < neighbors.size) {
                stack.addLast(u to idx + 1)
                val v = neighbors[idx]
                if (v !in dfs) {
                    parent[v] = u
                    dfs[v] = vertex.size
                    vertex.add(v)
                    semi[v] = dfs[v]!!
                    stack.addLast(v to 0)
                }
            }
        }
    }

    private fun compress(v: String) {
        val a = ancestor[v]
        if (a != null && ancestor[a] != null) {
            compress(a)
            if (semi[label[a]!!]!! < semi[label[v]!!]!!) label[v] = label[a]
            ancestor[v] = ancestor[a]
        }
    }

    private fun eval(v: String): String {
        if (ancestor[v] == null) return label[v]
        compress(v)
        return if (semi[label[ancestor[v]!!]!!]!! >= semi[label[v]!!]!!) label[v]
        else label[ancestor[v]!!]!!
    }

    private fun link(p: String, v: String) { ancestor[v] = p }
}

/** 支配树的父->子展开为自顶向下顺序。 */
fun topologicalFromRoots(children: Map<String, List<String>>, root: String): List<String> {
    val out = mutableListOf<String>()
    val stack = ArrayDeque<String>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        out.add(n)
        children[n].orEmpty().sortedDescending().forEach { stack.addLast(it) }
    }
    return out
}

/**
 * 多源 BFS 求每个可达节点到“最近 root”的【全部等长】路径。
 * 环图安全：按距离分层，只记录距离相等的前驱，不重复展开。
 * 路径枚举设预算；超预算返回 BudgetExhausted（第三种结果），而不是静默截断。
 */
internal fun shortestRootPaths(
    activeRoots: List<heaplab.model.RawRoot>,
    reachAdj: Map<String, List<String>>,
    graphEdges: List<GraphEdge>,
    snap: RawSnapshot,
    budget: AnalysisBudget
): Map<String, List<RootPath>> {
    data class Pred(val node: String, val edgeUid: String)

    val dist = HashMap<String, Int>()
    val preds = HashMap<String, MutableList<Pred>>()
    val rootOf = HashMap<String, heaplab.model.RawRoot>()
    val dq = ArrayDeque<String>()
    for (r in activeRoots) {
        if (r.objId !in dist) {
            dist[r.objId] = 0
            rootOf[r.objId] = r
            dq.add(r.objId)
        }
    }
    val edgeIndex = HashMap<String, MutableList<GraphEdge>>()
    for (ge in graphEdges) {
        if (!ge.reach) continue
        edgeIndex.getOrPut(ge.fromId) { mutableListOf() }.add(ge)
    }

    while (dq.isNotEmpty()) {
        val u = dq.removeFirst()
        val du = dist[u]!!
        for (ge in edgeIndex[u].orEmpty()) {
            val v = ge.toId
            val nd = du + 1
            when {
                v !in dist -> {
                    dist[v] = nd
                    preds[v] = mutableListOf(Pred(u, ge.edgeUid))
                    rootOf[v] = rootOf[u]
                    dq.add(v)
                }
                dist[v] == nd -> preds[v]!!.add(Pred(u, ge.edgeUid))
            }
        }
    }

    val result = HashMap<String, List<RootPath>>()
    var enumerated = 0
    fun enumerate(node: String): List<RootPath> {
        if (node in result) return result[node]!!
        val paths = if (dist[node] == 0) {
            val r = rootOf[node]!!
            listOf(RootPath(r.objId, r.category, listOf(node), emptyList(), 0))
        } else {
            val acc = mutableListOf<RootPath>()
            for (p in preds[node].orEmpty()) {
                for (sub in enumerate(p.node)) {
                    acc.add(RootPath(sub.rootObjId, sub.rootCategory,
                        sub.objectIds + node, sub.edgeUids + p.edgeUid, sub.length + 1))
                    enumerated++
                    if (enumerated > budget.maxPathEnumeration) {
                        throw PathBudgetExceeded()
                    }
                }
            }
            acc
        }
        result[node] = paths
        return paths
    }

    for (node in dist.keys) enumerate(node)
    return result
}

internal class PathBudgetExceeded : RuntimeException()
