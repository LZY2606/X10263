package forensics.heapshadow.engine

import forensics.heapshadow.model.*

/**
 * 内存图分析引擎（纯 Kotlin，业务规则不依赖浏览器）。
 *
 * 关键规则（可重放、可测试）：
 *  1. 支配树只在可达图上构建；不可达对象没有 idom，也不进入保留大小汇总。
 *  2. WEAK 边是否保活由 [AnalysisParameters.weakPolicy] 显式决定。
 *  3. 多个等长 root 路径全部保留；无法唯一决定时暴露全部合法候选与 tieCount。
 *  4. 地址从不参与身份；身份始终是原始 object id。
 *  5. 等价输入 => 稳定顺序（先 ordinal，再 id 字典序）。
 */
class GraphEngine {

    companion object {
        /** 虚拟根：真实 root 对象的 idom。它不计尺寸。 */
        const val SUPER_ROOT = "@super-root"
    }

    fun analyze(material: SnapshotMaterial, params: AnalysisParameters): AnalysisResult {
        val digest = digest(material)
        val main = runView(material, params, params.weakPolicy, digest)
        val alt = if (params.weakPolicy == WeakPolicy.BOTH_VIEWS)
            runView(material, params, WeakPolicy.WEAK_KEEPS_ALIVE, digest)
        else null
        return main.copy(altView = alt)
    }

    internal fun digest(material: SnapshotMaterial): InputDigest = InputDigest(
        snapshotId = material.snapshotId,
        materialSha256 = SnapshotImporter.sha256(
            listOf(
                material.snapshotId, material.parserVersion, material.formatVersion,
                material.classes.joinToString("|") { it.classId + ":" + it.name },
                material.objects.joinToString("|") {
                    it.objectId + ":" + it.address?.raw + ":" + it.shallowSize?.raw + ":" + it.incomplete
                },
                material.edges.joinToString("|") {
                    it.edgeId + ":" + it.fromObjectId + ">" + it.toObjectId + ":" + it.kind
                },
                material.roots.joinToString("|") { it.rootId + ":" + it.objectId + ":" + it.kind },
            ).joinToString("\n")
        ),
        objectCount = material.objects.size,
        edgeCount = material.edges.size,
        rootCount = material.roots.size,
    )

    private fun runView(
        material: SnapshotMaterial, params: AnalysisParameters,
        policy: WeakPolicy, digest: InputDigest,
    ): AnalysisResult {
        val diags = mutableListOf<Diagnostic>()
        val classByName = material.classes.associateBy { it.classId }
        val objs = material.objects.sortedWith(compareBy({ it.ordinal }, { it.objectId }))
        val ids = objs.map { it.objectId }
        val byId = objs.associateBy { it.objectId }
        val idSet = ids.toSet()

        fun edgeKeepsAlive(kind: EdgeKind): Boolean = when (kind) {
            EdgeKind.STRONG -> true
            EdgeKind.INTERNAL -> params.internalKeepsAlive
            EdgeKind.WEAK -> policy == WeakPolicy.WEAK_KEEPS_ALIVE
        }

        val validEdges = material.edges
            .filter { it.fromObjectId in idSet && it.toObjectId in idSet }
            .sortedWith(compareBy({ it.ordinal }, { it.edgeId }))
        val edgeViews = validEdges.map {
            EdgeView(it.edgeId, it.fromObjectId, it.toObjectId, it.kind, edgeKeepsAlive(it.kind), it.label, it.ordinal)
        }

        val liveAdj = HashMap<String, MutableList<HeapEdge>>()
        validEdges.filter { edgeKeepsAlive(it.kind) }.forEach {
            liveAdj.getOrPut(it.fromObjectId) { mutableListOf() }.add(it)
        }
        val validRoots = material.roots.filter { it.objectId in idSet }
            .sortedWith(compareBy({ it.ordinal }, { it.rootId }))
        val rootTargets = validRoots.map { it.objectId }.distinct()

        // 可达性
        val reachableVia = HashMap<String, MutableList<String>>()
        val q = ArrayDeque<String>()
        rootTargets.forEach { rid ->
            reachableVia.getOrPut(rid) { mutableListOf() }.add("@root"); q.add(rid)
        }
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            for (e in liveAdj[cur].orEmpty()) {
                if (reachableVia.putIfAbsent(e.toObjectId, mutableListOf(e.edgeId)) == null) q.add(e.toObjectId)
            }
        }
        val reachableSet = reachableVia.keys
        val reachableOrdered = ids.filter { it in reachableSet }

        // 支配树（仅可达图）
        val pred = HashMap<String, MutableSet<String>>()
        rootTargets.forEach { pred.getOrPut(it) { mutableSetOf() }.add(SUPER_ROOT) }
        validEdges.filter { edgeKeepsAlive(it.kind) && it.toObjectId in reachableSet }
            .forEach { pred.getOrPut(it.toObjectId) { mutableSetOf() }.add(it.fromObjectId) }
        val rpo = reversePostOrder(rootTargets, liveAdj, reachableSet)
        val idom = computeDominators(rpo, pred, rootTargets.toSet())

        // 保留大小（后序聚合，未知尺寸不猜测）
        val shallow = objs.associate { it.objectId to it.shallowSize?.normalizedBytes }
        val children = HashMap<String, MutableList<String>>()
        idom.forEach { (child, dom) -> children.getOrPut(dom) { mutableListOf() }.add(child) }
        val retained = HashMap<String, Long?>()
        fun compute(node: String) {
            var acc: Long? = shallow[node] ?: 0L
            for (c in children[node].orEmpty()) { compute(c); val rv = retained[c]; acc = if (acc == null || rv == null) null else acc + rv }
            retained[node] = acc
        }
        children[SUPER_ROOT].orEmpty().sortedWith(compareBy({ byId[it]?.ordinal ?: -1 }, { it })).forEach(::compute)

        val pathSets = enumerateRootPaths(reachableOrdered, liveAdj, validRoots, params, diags)

        val domViews = reachableOrdered.map { oid ->
            DominatorView(
                objectId = oid, immediateDominator = idom[oid] ?: SUPER_ROOT,
                dominatedIds = children[oid].orEmpty().sortedWith(compareBy({ byId[it]?.ordinal ?: -1 }, { it })),
                retainedBytes = retained[oid],
                retainedUnknownReason = if (retained[oid] == null)
                    "支配子树含未知/不可换算尺寸，保留大小不可计算（原始表示保留）" else null,
            )
        }

        val nodeViews = objs.map { o ->
            NodeView(
                objectId = o.objectId, klassId = o.klassId,
                className = o.klassId?.let { classByName[it]?.name },
                shallowBytes = o.shallowSize?.normalizedBytes, shallowRaw = o.shallowSize?.raw,
                reachable = o.objectId in reachableSet, reachableVia = reachableVia[o.objectId].orEmpty(),
                ordinal = o.ordinal, incomplete = o.incomplete, addressRaw = o.address?.raw,
            )
        }

        val pathById = pathSets.associateBy { it.objectId }
        val candidates = domViews.mapNotNull { d ->
            val ps = pathById[d.objectId]
            val overThreshold = d.retainedBytes != null && params.leakRetainedThresholdBytes != null &&
                d.retainedBytes >= params.leakRetainedThresholdBytes
            val longUniqueChain = ps != null && ps.tieCount == 1 && ps.shortestLength >= 3
            val unknownLongChain = d.retainedBytes == null && longUniqueChain
            if (overThreshold || longUniqueChain || unknownLongChain) LeakCandidate(
                objectId = d.objectId, retainedBytes = d.retainedBytes,
                shortestPathLength = ps?.shortestLength ?: -1, rootPathTieCount = ps?.tieCount ?: 0,
                reason = when {
                    overThreshold -> "retained>=阈值(${params.leakRetainedThresholdBytes}B)"
                    unknownLongChain -> "单一长链且尺寸未知，需人工核证"
                    else -> "单一长链可达(${ps!!.shortestLength}跳)"
                },
            ) else null
        }.sortedWith(compareByDescending<LeakCandidate> { it.retainedBytes ?: -1L }.thenBy { byId[it.objectId]!!.ordinal })

        return AnalysisResult(
            parameterVersion = params.parameterVersion, weakPolicy = policy, inputDigest = digest,
            nodes = nodeViews, edges = edgeViews, reachableIds = reachableOrdered,
            dominators = domViews, rootPaths = pathSets, leakCandidates = candidates,
            diagnostics = diags, altView = null,
        )
    }

    private fun reversePostOrder(
        roots: Collection<String>, adj: Map<String, List<HeapEdge>>, reachable: Set<String>,
    ): List<String> {
        val visited = HashSet<String>()
        val order = ArrayDeque<String>()
        fun dfs(start: String) {
            val stack = ArrayDeque<Pair<String, Int>>()
            visited.add(start); stack.add(start to 0)
            while (stack.isNotEmpty()) {
                val (node, idx) = stack.last()
                val next = adj[node].orEmpty().filter { it.toObjectId in reachable }
                if (idx < next.size) {
                    stack[stack.lastIndex] = node to (idx + 1)
                    val t = next[idx].toObjectId
                    if (visited.add(t)) stack.add(t to 0)
                } else { stack.removeLast(); order.addFirst(node) }
            }
        }
        roots.sorted().forEach(::dfs)
        return order.toList()
    }

    /**
     * Cooper–Harvey–Kennedy 迭代支配算法。
     * 多个真实 root 统一由虚拟根 [SUPER_ROOT] 支配，保证森林有唯一入口；
     * 支配树严格只在可达节点集合上构建。
     */
    private fun computeDominators(
        rpo: List<String>, pred: Map<String, Set<String>>, roots: Set<String>,
    ): Map<String, String> {
        val seq = listOf(SUPER_ROOT) + rpo
        val index = seq.withIndex().associate { (i, v) -> v to i }
        val idom = HashMap<String, String>()
        idom[SUPER_ROOT] = SUPER_ROOT
        rpo.forEach { idom[it] = if (it in roots) SUPER_ROOT else SUPER_ROOT }

        fun intersect(aIn: String, bIn: String): String {
            var finger1 = aIn; var finger2 = bIn
            while (finger1 != finger2) {
                while (index[finger1]!! > index[finger2]!!) finger1 = idom[finger1]!!
                while (index[finger2]!! > index[finger1]!!) finger2 = idom[finger2]!!
            }
            return finger1
        }

        var changed = true
        while (changed) {
            changed = false
            for (node in rpo) {
                if (node in roots) continue
                val preds = pred[node].orEmpty()
                    .filter { it == SUPER_ROOT || (idom[it] != null) }
                if (preds.isEmpty()) continue
                var newIdom: String? = null
                for (p in preds) newIdom = if (newIdom == null) p else intersect(p, newIdom)
                if (newIdom != null && idom[node] != newIdom) { idom[node] = newIdom!!; changed = true }
            }
        }
        return rpo.associateWith { idom[it]!! }
    }

    /** 全部等长最短 root 路径；预算耗尽是独立诊断，绝不静默截断为“单一路径”。 */
    private fun enumerateRootPaths(
        reachable: List<String>, adj: Map<String, List<HeapEdge>>, roots: List<RootRecord>,
        params: AnalysisParameters, diags: MutableList<Diagnostic>,
    ): List<RootPathSet> {
        val rootKinds = roots.groupBy({ it.objectId }, { it.kind })
        val rootSet = rootKinds.keys

        val dist = HashMap<String, Int>()
        val preds = HashMap<String, MutableList<HeapEdge>>()
        val bfs = ArrayDeque<String>()
        rootSet.forEach { dist[it] = 0; bfs.add(it) }
        while (bfs.isNotEmpty()) {
            val n = bfs.removeFirst()
            for (e in adj[n].orEmpty()) {
                val cur = dist[e.toObjectId]; val nd = dist[n]!! + 1
                when {
                    cur == null -> { dist[e.toObjectId] = nd; preds[e.toObjectId] = mutableListOf(e); bfs.add(e.toObjectId) }
                    nd < cur -> { dist[e.toObjectId] = nd; preds[e.toObjectId] = mutableListOf(e) }
                    nd == cur -> {
                        val pl = preds.getOrPut(e.toObjectId) { mutableListOf() }
                        if (pl.none { it.edgeId == e.edgeId }) pl.add(e)
                    }
                }
            }
        }

        return reachable.map { oid ->
            val shortest = dist[oid] ?: return@map RootPathSet(oid, -1, emptyList(), 0, false, null)
            val paths = mutableListOf<RootPath>()
            var truncated = false

            fun expand(node: String, objs: ArrayDeque<String>, edgeAcc: ArrayDeque<String>) {
                objs.addFirst(node)
                if (node in rootSet) {
                    paths.add(RootPath(objs.toList(), edgeAcc.toList(), edgeAcc.size, rootKinds[node].orEmpty(), true))
                    if (paths.size >= params.maxRootPathsPerObject || paths.size >= params.maxTotalPaths) truncated = true
                } else {
                    val pl = preds[node].orEmpty().sortedWith(compareBy({ it.ordinal }, { it.edgeId }))
                    for (e in pl) {
                        edgeAcc.addFirst(e.edgeId)
                        expand(e.fromObjectId, objs, edgeAcc)
                        edgeAcc.removeFirst()
                        if (truncated) break
                    }
                }
                objs.removeFirst()
            }
            expand(oid, ArrayDeque(), ArrayDeque())

            var diagId: String? = null
            if (truncated) {
                diagId = "d-budget-" + SnapshotImporter.sha256(oid + params.parameterVersion).take(10)
                diags.add(
                    Diagnostic(
                        diagId, DiagnosticCategory.BUDGET_EXHAUSTED, Severity.WARNING,
                        "PATH-BUDGET-EXHAUSTED",
                        "对象 $oid 的 root 路径枚举超过预算（每对象 ${params.maxRootPathsPerObject} / " +
                            "总 ${params.maxTotalPaths}）；输入合法，预算内全部等长候选保留，超出部分标记截断。",
                        null, listOf(oid)
                    )
                )
            }
            RootPathSet(oid, shortest, paths, paths.size, truncated, diagId)
        }
    }
}

