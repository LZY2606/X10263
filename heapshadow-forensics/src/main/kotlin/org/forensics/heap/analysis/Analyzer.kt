package org.forensics.heap.analysis

import org.forensics.heap.model.*
import org.forensics.heap.parser.HspParser
import java.util.UUID

/**
 * 派生层：可达性 → 支配树 → 保留大小 → root 路径 → 泄漏候选 → 类群统计。
 *
 * 关键不变量：
 *  - 支配树只在“可达图”上构建；不可达对象绝不进入支配计算。
 *  - 弱引用是否保活由 [AnalysisParams.weakPolicy] 显式决定，不接受隐式默认。
 *  - 多个等长 root 路径全部保留；无法唯一决定时保留全部合法候选。
 *  - 地址不参与任何身份/排序，稳定身份只有原始对象 id。
 *  - 预算耗尽 / 格式损坏 / 语义无解是三种不同结果：格式损坏在解析层抛出，
 *    预算耗尽返回 [ResultStatus.BUDGET_EXHAUSTED]，语义问题返回 [ResultStatus.SEMANTICALLY_AMBIGUOUS]。
 */
class Analyzer(
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    fun analyze(snapshot: RawSnapshot, params: AnalysisParams): AnalysisResult {
        val started = clock()
        val diags = snapshot.diagnostics.filter { it.severity == Severity.ERROR }.toMutableList()
        val warns = snapshot.diagnostics.filter { it.severity != Severity.ERROR }.toMutableList()

        val objects = snapshot.objects.sortedBy { it.objectId }
        if (objects.size > params.maxNodes) {
            return exhausted(snapshot, params, started,
                Diagnostic(Diagnostic.BUDGET_NODES, Severity.ERROR,
                    "对象数 ${objects.size} 超过节点预算 ${params.maxNodes}"))
        }
        if (snapshot.edges.size > params.maxEdges) {
            return exhausted(snapshot, params, started,
                Diagnostic(Diagnostic.BUDGET_EDGES, Severity.ERROR,
                    "边数 ${snapshot.edges.size} 超过边预算 ${params.maxEdges}"))
        }

        val index = HashMap<String, Int>()
        objects.forEachIndexed { i, o -> index[o.objectId] = i }
        val n = objects.size
        val shallow = LongArray(n)
        val shallowKnown = BooleanArray(n)
        for (i in objects.indices) {
            val v = HspParser.parseSize(objects[i].shallowSizeRaw)
            if (v == null) shallowKnown[i] = false else { shallow[i] = v; shallowKnown[i] = true }
        }

        // 1) 边是否保活（显式弱策略）；悬空边不进入图但保留在材料层
        val edgeViews = mutableListOf<EdgeView>()
        val adj = Array(n) { ArrayList<Int>() }
        val pred = Array(n) { ArrayList<Int>() }
        for (e in snapshot.edges.sortedBy { it.edgeId }) {
            val from = index[e.fromObjectId]
            val to = index[e.toObjectId]
            val keepsAlive = keepsAlive(e.kind, params.weakPolicy)
            edgeViews += EdgeView(e.edgeId, e.fromObjectId, e.toObjectId, e.kind, keepsAlive, e.label)
            if (from != null && to != null && keepsAlive) {
                adj[from].add(to)
                pred[to].add(from)
            }
        }
        for (list in adj) list.sort()
        for (list in pred) list.sort()

        val deadline = started + params.budgetMillis

        // 2) 可达性（多源 root BFS，只走保活边）
        val rootIndices = sortedSetOf<Int>()
        for (r in snapshot.roots) {
            index[r.objectId]?.let { rootIndices.add(it) }
        }
        val reachable = BooleanArray(n)
        val queue = ArrayDeque<Int>()
        for (r in rootIndices) { if (!reachable[r]) { reachable[r] = true; queue.add(r) } }
        while (queue.isNotEmpty()) {
            if (clock() > deadline) {
                return exhausted(snapshot, params, started,
                    Diagnostic(Diagnostic.BUDGET_NODES, Severity.ERROR, "可达性计算超出时间预算 ${params.budgetMillis}ms"))
            }
            val u = queue.removeFirst()
            for (v in adj[u]) if (!reachable[v]) { reachable[v] = true; queue.add(v) }
        }

        // 3) 可达图上的支配树（虚拟 super-root 连接全部真实 root）
        val reachList = (0 until n).filter { reachable[it] }
        val idom = dominatorTree(reachList, rootIndices, adj, pred, n, deadline, clock)
            ?: return exhausted(snapshot, params, started,
                Diagnostic(Diagnostic.BUDGET_NODES, Severity.ERROR, "支配树迭代超出时间预算 ${params.budgetMillis}ms"))

        // 4) 多源最短 root 距离 + 全部等长 root 路径
        val dist = IntArray(n) { -1 }
        val q = ArrayDeque<Int>()
        for (r in rootIndices) { if (reachable[r]) { dist[r] = 0; q.add(r) } }
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            for (v in adj[u]) if (reachable[v] && dist[v] == -1) { dist[v] = dist[u] + 1; q.add(v) }
        }

        val pathMemo = HashMap<Int, List<List<Int>>>()
        fun paths(node: Int): List<List<Int>> = pathMemo.getOrPut(node) {
            if (node in rootIndices) listOf(listOf(node))
            else pred[node].filter { reachable[it] && dist[it] == dist[node] - 1 }
                .flatMap { p -> paths(p).map { it + node } }
        }

        // 5) 支配子树与保留大小（子树内有任何未知 shallow 尺寸 => 精确保留大小不可计算）
        val children = HashMap<Int, MutableList<Int>>()
        for ((node, dom) in idom) children.getOrPut(dom) { mutableListOf() }.add(node)
        children.values.forEach { it.sort() }
        val retained = LongArray(n)
        val retainedKnown = BooleanArray(n) { reachable[it] && shallowKnown[it] }
        // 按支配树后序（idom 父在 root 侧；用距离逆序保证子先于父）
        val postOrder = reachList.sortedWith(compareBy({ -domDepth(it, idom) }, { it }))
        for (u in postOrder) {
            val kids = children[u].orEmpty()
            var known = shallowKnown[u]
            var sum = if (shallowKnown[u]) shallow[u] else 0L
            for (k in kids) {
                if (!retainedKnown[k]) known = false
                sum += if (retainedKnown[k]) retained[k] else 0L
            }
            retainedKnown[u] = known
            retained[u] = sum
        }

        if (clock() > deadline) {
            return exhausted(snapshot, params, started,
                Diagnostic(Diagnostic.BUDGET_NODES, Severity.ERROR, "保留大小计算超出时间预算 ${params.budgetMillis}ms"))
        }

        val results = objects.mapIndexed { i, o ->
            val pathsText = if (reachable[i]) paths(i).map { chain -> chain.map { objects[it].objectId } } else emptyList()
            val isRoot = i in rootIndices
            val retainedBytes = if (reachable[i] && retainedKnown[i]) retained[i] else null
            val leak = reachable[i] && !isRoot && retainedBytes != null && retainedBytes >= params.leakThresholdBytes
            val codes = mutableListOf<String>()
            codes += o.diagnosticCodes
            if (reachable[i] && !retainedKnown[i]) codes += Diagnostic.UNPARSEABLE_SIZE
            ObjectResult(
                objectId = o.objectId,
                reachable = reachable[i],
                shallowBytes = if (shallowKnown[i]) shallow[i] else null,
                retainedBytes = retainedBytes,
                dominatorId = idom[i]?.let { d -> if (d == SUPER_ROOT) null else objects[d].objectId },
                rootPaths = pathsText,
                isLeakCandidate = leak,
                diagnosticCodes = codes.distinct()
            )
        }

        // 6) 类群（稳定 key 是 classId，名字只展示）
        val byClass = HashMap<String, MutableList<Int>>()
        for (i in objects.indices) byClass.getOrPut(objects[i].classId) { mutableListOf() }.add(i)
        val classGroups = snapshot.classes.sortedBy { it.classId }.map { c ->
            val memberIdx = byClass[c.classId].orEmpty()
            ClassGroup(
                classId = c.classId,
                className = c.rawName,
                instanceCount = memberIdx.size,
                shallowBytes = memberIdx.sumOf { if (shallowKnown[it]) shallow[it] else 0L },
                retainedBytes = memberIdx.sumOf { idx ->
                    if (reachable[idx] && retainedKnown[idx]) retained[idx] else 0L
                },
                leakInstanceCount = memberIdx.count { results[it].isLeakCandidate }
            )
        }

        val status = when {
            diags.isNotEmpty() -> ResultStatus.SEMANTICALLY_AMBIGUOUS
            reachList.any { !retainedKnown[it] } -> ResultStatus.SEMANTICALLY_AMBIGUOUS
            else -> ResultStatus.OK
        }
        val finished = clock()
        return AnalysisResult(
            analysisId = idGenerator(),
            snapshotId = snapshot.snapshotId,
            inputChecksum = snapshot.checksumSha256,
            params = params,
            status = status,
            objects = results,
            edges = edgeViews.sortedWith(compareBy({ it.edgeId })),
            classGroups = classGroups,
            diagnostics = (diags + warns).sortedWith(compareBy({ it.line ?: 0 }, { it.code })),
            reachableCount = reachList.size,
            totalShallowBytes = (0 until n).sumOf { if (shallowKnown[it]) shallow[it] else 0L },
            startedAtEpochMillis = started,
            finishedAtEpochMillis = finished,
            invalidReason = if (status == ResultStatus.SEMANTICALLY_AMBIGUOUS)
                "存在阻断完整计算的语义诊断或不可规范化尺寸；受影响对象的保留大小标记为不可计算" else null,
            parentDependency = DependencyRef(snapshot.snapshotId, snapshot.checksumSha256, snapshot.parserVersion, params.paramsVersion)
        )
    }

    private fun exhausted(
        snapshot: RawSnapshot, params: AnalysisParams, started: Long, d: Diagnostic
    ): AnalysisResult = AnalysisResult(
        analysisId = idGenerator(),
        snapshotId = snapshot.snapshotId,
        inputChecksum = snapshot.checksumSha256,
        params = params,
        status = ResultStatus.BUDGET_EXHAUSTED,
        objects = emptyList(),
        edges = emptyList(),
        classGroups = emptyList(),
        diagnostics = listOf(d),
        reachableCount = 0,
        totalShallowBytes = 0L,
        startedAtEpochMillis = started,
        finishedAtEpochMillis = clock(),
        invalidReason = "资源预算耗尽：${d.message}",
        parentDependency = DependencyRef(snapshot.snapshotId, snapshot.checksumSha256, snapshot.parserVersion, params.paramsVersion)
    )

    companion object {
        private const val SUPER_ROOT = -1

        fun keepsAlive(kind: EdgeKind, policy: WeakPolicy): Boolean = when (kind) {
            EdgeKind.STRONG, EdgeKind.INTERNAL -> true
            EdgeKind.SOFT -> policy != WeakPolicy.EXCLUDE_WEAK
            EdgeKind.WEAK -> policy == WeakPolicy.INCLUDE_ALL
        }

        private fun domDepth(node: Int, idom: Map<Int, Int>): Int {
            var d = 0
            var cur = node
            val guard = HashSet<Int>()
            while (true) {
                val p = idom[cur] ?: return d
                if (p == SUPER_ROOT) return d
                if (!guard.add(cur)) return d
                cur = p; d++
            }
        }

        /**
         * Cooper-Harvey-Kennedy 迭代支配算法，虚拟 super-root → 每个真实 root。
         * 返回 node -> immediate dominator（真实 root 的 idom 为 [SUPER_ROOT]）。
         * 时间预算耗尽时返回 null。
         */
        internal fun dominatorTree(
            reachNodes: List<Int>,
            roots: Set<Int>,
            adj: Array<ArrayList<Int>>,
            pred: Array<ArrayList<Int>>,
            n: Int,
            deadline: Long,
            clock: () -> Long
        ): Map<Int, Int>? {
            if (reachNodes.isEmpty()) return emptyMap()
            val reach = BooleanArray(n)
            reachNodes.forEach { reach[it] = true }

            // 逆后序：从全部真实 root（虚拟 super-root）出发做 DFS，按确定顺序保证等价输入稳定
            val visited = BooleanArray(n)
            val order = ArrayList<Int>()
            fun dfs(u: Int) {
                visited[u] = true
                for (v in adj[u]) if (reach[v] && !visited[v]) dfs(v)
                order.add(u)
            }
            val sortedRoots = roots.sorted().filter { reach[it] }
            for (r in sortedRoots) if (!visited[r]) dfs(r)
            for (v in reachNodes.sorted()) if (!visited[v]) dfs(v)
            order.reverse()
            val rpo = HashMap<Int, Int>()
            order.forEachIndexed { idxv, v -> rpo[v] = idxv }

            val idom = HashMap<Int, Int>()
            for (r in sortedRoots) idom[r] = SUPER_ROOT

            fun intersect(biIn: Int, aiIn: Int): Int {
                var finger1 = biIn
                var finger2 = aiIn
                while (finger1 != finger2) {
                    while (rpo[finger1]!! > rpo[finger2]!!) finger1 = idom[finger1]!!
                    while (rpo[finger2]!! > rpo[finger1]!!) finger2 = idom[finger2]!!
                }
                return finger1
            }

            var changed = true
            while (changed) {
                if (clock() > deadline) return null
                changed = false
                for (b in order) {
                    if (b in roots) continue
                    val processed = pred[b].filter { reach[it] && idom.containsKey(it) }
                    if (processed.isEmpty()) continue
                    val newIdom = processed.drop(1).fold(processed.first() as Int?) { acc, p ->
                        if (acc == null) p else intersect(acc, p)
                    }
                    if (newIdom != null && idom[b] != newIdom) {
                        idom[b] = newIdom
                        changed = true
                    }
                }
            }
            return idom
        }
    }
}
