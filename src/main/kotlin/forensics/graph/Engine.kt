package forensics.graph

import forensics.model.*
import java.security.MessageDigest

class GraphEngine {

    fun analyze(caseId: String, snapshot: Snapshot, params: AnalysisParams): AnalysisResult {
        val materialSha = sha1(
            snapshot.snapshotId + "|" + snapshot.parserVersion + "|" +
                    snapshot.objects.joinToString(",") { it.objectId + ":" + it.addressRaw + ":" + it.classId + ":" + (it.shallowSize?.raw ?: "") } + "|" +
                    snapshot.edges.sortedBy { it.edgeId }.joinToString(",") { "${it.edgeId}:${it.fromId}>${it.toId}:${it.kind}" } + "|" +
                    snapshot.roots.sortedBy { it.rootId }.joinToString(",") { "${it.rootId}:${it.objectId}:${it.kind}" }
        )
        val analysisId = "an-${sha1("${snapshot.snapshotId}|p${params.paramVersion}|$materialSha").take(12)}"

        val prefix = { oid: String -> "${snapshot.snapshotId}:$oid" }
        val diagnostics = mutableListOf<Diagnostic>()
        val invalidPositions = mutableListOf<String>()

        val nodeMap = LinkedHashMap<String, NodeView>()
        snapshot.objects.sortedBy { it.objectId }.forEach { o ->
            if (o.shallowSize?.value == null && o.shallowSize != null) {
                invalidPositions += "objects#${o.objectId}.shallowSize"
                diagnostics += Diagnostic("W100", "WARNING", "objects#${o.objectId}.shallowSize",
                    "保留大小计算无法使用该对象的浅尺寸（原始值 '${o.shallowSize.raw}' 保留），按 0 byte 计入并标记失效")
            }
            if (o.incomplete) invalidPositions += "objects#${o.objectId}"
            nodeMap[prefix(o.objectId)] = NodeView(
                nodeId = prefix(o.objectId),
                snapshotId = snapshot.snapshotId,
                objectId = o.objectId,
                addressDisplay = o.addressRaw?.let { "$it (${snapshot.addressBasis ?: "地址基准未声明"})" },
                className = o.classId?.let { snapshot.classById[it]?.name },
                classId = o.classId,
                shallowBytes = o.shallowSize?.value?.toLong(),
                shallowRaw = o.shallowSize?.raw,
                unit = o.shallowSize?.unit,
                sizeBasis = o.shallowSize?.basis ?: snapshot.sizeUnitBasis,
                reachable = false,
                isRoot = snapshot.roots.any { r -> r.objectId == o.objectId },
                incomplete = o.incomplete,
            )
        }

        val keepsAlive = { e: RawEdge -> when (e.kind) {
            EdgeKind.STRONG -> true
            EdgeKind.INTERNAL -> params.internalKeepsAlive
            EdgeKind.WEAK -> params.weakKeepsAlive
            EdgeKind.UNKNOWN -> false
        } }

        val edgeViews = ArrayList<EdgeView>()
        val succ = HashMap<String, MutableList<String>>()
        val edgeByPair = HashMap<Pair<String, String>, MutableList<RawEdge>>()
        snapshot.edges.sortedWith(compareBy({ it.fromId }, { it.toId }, { it.edgeId })).forEach { e ->
            val fn = prefix(e.fromId); val tn = prefix(e.toId)
            val ka = keepsAlive(e)
            edgeViews += EdgeView("${snapshot.snapshotId}:${e.edgeId}", fn, tn, e.kind.name, ka, e.field)
            edgeByPair.getOrPut(fn to tn) { mutableListOf() }.add(e)
            if (ka) succ.getOrPut(fn) { mutableListOf() }.add(tn)
        }
        succ.values.forEach { ids -> ids.sort() }

        val rootNodeIds = snapshot.roots.sortedBy { it.rootId }.map { prefix(it.objectId) }.distinct()
        val reachable = bfsReachable(rootNodeIds, succ)

        val nodes = nodeMap.values.map { it.copy(reachable = it.nodeId in reachable) }

        // —— 支配树只在可达图上构建；超级 root 汇聚多个 root ——
        val rPreds = HashMap<String, MutableList<String>>()
        val rSucc = HashMap<String, MutableList<String>>()
        reachable.sorted().forEach { n -> rPreds.getOrPut(n) { mutableListOf() }; rSucc.getOrPut(n) { mutableListOf() } }
        rPreds[SUPER_ROOT] = mutableListOf()
        rootNodeIds.filter { it in reachable }.forEach { r ->
            rPreds.getOrPut(r) { mutableListOf() }.add(SUPER_ROOT)
            rSucc.getOrPut(SUPER_ROOT) { mutableListOf() }.add(r)
        }
        reachable.forEach { n ->
            succ[n].orEmpty().filter { it in reachable }.sorted().forEach { t ->
                rPreds[t]!!.add(n); rSucc[n]!!.add(t)
            }
        }
        val rpo = computeRpo(SUPER_ROOT, rSucc)
        val idom = DominatorComputer(rpo, rPreds.mapValues { it.value.sorted() },
            rSucc.mapValues { it.value.sorted() }, SUPER_ROOT).compute()

        // —— 保留大小：支配子树浅尺寸之和（byte；规范化失败按 0 计并在诊断中说明）——
        val retained = HashMap<String, Long>()
        reachable.forEach { retained[it] = nodeMap[it]!!.shallowBytes ?: 0L }
        retained[SUPER_ROOT] = 0L
        rpo.filter { it != SUPER_ROOT }.reversed().forEach { n ->
            val d = idom[n]
            if (d != null) retained[d] = (retained[d] ?: 0L) + (retained[n] ?: 0L)
        }

        // —— 多个等长 root 路径：枚举全部最短合法候选，受预算截断 ——
        val (rootPaths, truncated) = allShortestRootPaths(
            reachable, rootNodeIds, rSucc, edgeByPair, snapshot, params.maxRootPathsPerObject
        )

        val candidates = reachable.filter { n ->
            (retained[n] ?: 0L) >= params.leakRetainedMinBytes && idom[n] != null && idom[n] != SUPER_ROOT
        }.sortedWith(compareByDescending<String> { retained[it] ?: 0L }.thenBy { it }).mapIndexed { idx, n ->
            val lens = rootPaths[n]?.map { p -> p.lengthEdges }
            LeakCandidate(
                rank = idx + 1, nodeId = n, retainedBytes = retained[n] ?: 0L,
                dominatorNode = idom[n], shortestRootPathLength = lens?.minOrNull(),
                reason = "保留大小 ${retained[n]} byte ≥ 阈值 ${params.leakRetainedMinBytes} byte，且支配父非 super-root（参数版本 ${params.paramVersion}）"
            )
        }

        if (snapshot.incompleteChunks.isNotEmpty()) {
            invalidPositions += snapshot.incompleteChunks.map { "incompleteChunks#${it.chunkId}" }
        }
        val validity = if (invalidPositions.isEmpty()) Validity(true)
        else Validity(
            valid = false,
            invalidPositions = invalidPositions.distinct().sorted(),
            invalidReason = "存在不完整对象/块或不可规范化的量；相关保留大小带 0-byte 回退，证据位置见 diagnostics"
        )

        return AnalysisResult(
            analysisId = analysisId, caseId = caseId, snapshotId = snapshot.snapshotId,
            paramVersion = params.paramVersion,
            inputSummary = InputSummary(
                snapshotId = snapshot.snapshotId, objectCount = snapshot.objects.size,
                edgeCount = snapshot.edges.size, rootCount = snapshot.roots.size,
                incompleteChunkCount = snapshot.incompleteChunks.size,
                parserVersion = snapshot.parserVersion, materialSha = materialSha),
            parentDependencies = listOf("mat-${snapshot.snapshotId}", "params-v${params.paramVersion}"),
            nodes = nodes, edges = edgeViews,
            dominator = idom.filterKeys { it != SUPER_ROOT }.mapValues { (_, v) -> if (v == SUPER_ROOT) null else v },
            retainedBytes = retained.filterKeys { it != SUPER_ROOT },
            rootPaths = rootPaths, rootPathTruncated = truncated,
            leakCandidates = candidates, validity = validity, diagnostics = diagnostics,
        )
    }

    private fun bfsReachable(roots: List<String>, succ: Map<String, List<String>>): Set<String> {
        val seen = LinkedHashSet<String>(); val q = ArrayDeque<String>()
        roots.forEach { if (seen.add(it)) q.addLast(it) }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            succ[n].orEmpty().forEach { if (seen.add(it)) q.addLast(it) }
        }
        return seen
    }

    /** 确定性逆后序：邻接已按 id 排序，得到稳定结果。 */
    private fun computeRpo(entry: String, succ: Map<String, List<String>>): List<String> {
        val visited = HashSet<String>(); val order = ArrayList<String>()
        fun dfs(n: String) {
            if (!visited.add(n)) return
            succ[n].orEmpty().forEach { dfs(it) }
            order.add(n)
        }
        dfs(entry)
        return order.reversed()
    }

    /**
     * 以 super-root 视角 BFS 出层，再回溯每个节点的全部最短路径（等长候选全部保留）。
     * root 边（super-root -> root）用合成 edgeId "root:<rootId>"，root 路径从真实 root 开始。
     */
    private fun allShortestRootPaths(
        reachable: Set<String>,
        roots: List<String>,
        succ: Map<String, List<String>>,
        edgeByPair: Map<Pair<String, String>, List<RawEdge>>,
        snapshot: Snapshot,
        maxPerObject: Int,
    ): Pair<Map<String, List<RootPath>>, Boolean> {
        val dist = HashMap<String, Int>()
        val preds = HashMap<String, MutableList<String>>()
        val q = ArrayDeque<String>()
        roots.forEach { r -> if (r in reachable && dist.putIfAbsent(r, 0) == null) { preds[r] = mutableListOf(SUPER_ROOT); q.addLast(r) } }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val d = dist[n]!!
            succ[n].orEmpty().filter { it in reachable }.forEach { t ->
                val known = dist[t]
                when {
                    known == null -> { dist[t] = d + 1; preds[t] = mutableListOf(n); q.addLast(t) }
                    known == d + 1 -> preds[t]!!.add(n)
                }
            }
        }
        val rootKindByNode = snapshot.roots.associate { "${snapshot.snapshotId}:${it.objectId}" to it.kind.name }
        val result = LinkedHashMap<String, List<RootPath>>()
        var truncated = false
        fun edgeIdsBetween(from: String, to: String): List<String> {
            if (from == SUPER_ROOT) {
                val rootId = snapshot.roots.first { "${snapshot.snapshotId}:${it.objectId}" == to }.rootId
                return listOf("${snapshot.snapshotId}:root:$rootId")
            }
            return edgeByPair[from to to].orEmpty().sortedBy { it.edgeId }.map { "${snapshot.snapshotId}:${it.edgeId}" }
        }
        val memo = HashMap<String, List<Pair<List<String>, List<String>>>>()
        fun enumerate(n: String): List<Pair<List<String>, List<String>>> {
            memo[n]?.let { return it }
            if (n in roots) {
                val base = edgeIdsBetween(SUPER_ROOT, n).map { listOf(n) to listOf(it) }
                memo[n] = base
                return base
            }
            val paths = mutableListOf<Pair<List<String>, List<String>>>()
            for (p in preds[n].orEmpty().sorted()) {
                for ((pNodes, pEdges) in enumerate(p)) {
                    for (eid in edgeIdsBetween(p, n)) paths.add((pNodes + n) to (pEdges + eid))
                    if (paths.size > maxPerObject) { memo[n] = paths; return paths }
                }
            }
            memo[n] = paths
            return paths
        }
        dist.keys.sorted().forEach { n ->
            val all = enumerate(n)
            val kept = if (all.size > maxPerObject) { truncated = true; all.take(maxPerObject) } else all
            result[n] = kept.mapIndexed { i, (ns, es) ->
                val kind = rootKindByNode[ns.first()] ?: "UNKNOWN"
                RootPath("${snapshot.snapshotId}:path:${n.substringAfter(':')}#$i", ns, es, kind, es.size)
            }
        }
        return result to truncated
    }

    companion object {
        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
