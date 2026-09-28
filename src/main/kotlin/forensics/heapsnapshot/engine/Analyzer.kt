package forensics.heapsnapshot.engine

import forensics.heapsnapshot.domain.Diagnostic
import forensics.heapsnapshot.domain.EdgeKind
import forensics.heapsnapshot.domain.ObjectRecord
import forensics.heapsnapshot.domain.ParsedSnapshot
import java.security.MessageDigest

class AnalysisBudgetException(val limit: Int, val observed: Int) :
    RuntimeException("资源预算耗尽：边遍历预算 limit=$limit observed=$observed")

object Analyzer {

    private const val VROOT = "__VROOT__"
    private const val MAX_TRAVERSAL_STEPS = 20_000_000

    fun digest(snapshot: ParsedSnapshot): InputDigest {
        val fingerprintSource = snapshot.diagnostics
            .sortedWith(compareBy({ it.line }, { it.code }, { it.column }))
            .joinToString("|") { "${it.code}@${it.line}:${it.column}" }
        val rawSha = sha256(snapshot.rawLines.joinToString("\n"))
        return InputDigest(
            snapshotIdentity = snapshot.meta.identity,
            parserVersion = snapshot.meta.parserVersion,
            rawLineCount = snapshot.rawLines.size,
            rawSha256 = rawSha,
            objectCount = snapshot.objects.size,
            edgeCount = snapshot.edges.size,
            rootCount = snapshot.roots.size,
            incompleteMarkCount = snapshot.incompleteMarks.size,
            diagnosticFingerprint = sha256(fingerprintSource).take(16),
        )
    }

    fun analyze(
        snapshot: ParsedSnapshot,
        params: AnalysisParams,
        parentDependencyIds: List<String>,
    ): AnalysisResult {
        // 等价输入得到稳定顺序：对象按 id 排序建索引，绝不依赖导入/HashSet 顺序。
        val objects: List<ObjectRecord> = snapshot.objects.sortedBy { it.id }
        val objById: Map<String, ObjectRecord> = objects.associateBy { it.id }
        val classById = snapshot.classes.associateBy { it.id }
        val classNameOf: (String) -> String = { classById[it]?.name ?: "__unknown__" }

        fun edgeActive(kind: EdgeKind) = when (kind) {
            EdgeKind.STRONG -> true
            EdgeKind.WEAK -> params.weakEdgesKeepAlive
            EdgeKind.INTERNAL -> params.internalEdgesKeepAlive
        }

        val validEdges = snapshot.edges.filter { it.from in objById && it.to in objById }

        // ---- 1) 可达性：只从 ROOT 沿“激活边”出发。弱引用是否保活由显式策略决定。 ----
        val out = LinkedHashMap<String, MutableList<String>>()
        val outTyped = LinkedHashMap<String, MutableList<Triple<String, EdgeKind, String?>>>()
        objects.forEach { out[it.id] = mutableListOf(); outTyped[it.id] = mutableListOf() }
        validEdges.forEach { e ->
            if (edgeActive(e.kind)) {
                out[e.from]!!.add(e.to)
                outTyped[e.from]!!.add(Triple(e.to, e.kind, e.field))
            }
        }
        out.values.forEach { it.sort() }
        outTyped.values.forEach { it.sortBy { t -> t.first } }

        val reachable = LinkedHashSet<String>()
        val rootRefs = snapshot.roots.map { it.ref }.filter { it in objById }.distinct().sorted()
        var steps = 0
        val queue = ArrayDeque<String>()
        rootRefs.forEach { r -> if (reachable.add(r)) queue.addLast(r) }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (v in out[u]!!) {
                if (++steps > MAX_TRAVERSAL_STEPS) throw AnalysisBudgetException(MAX_TRAVERSAL_STEPS, steps)
                if (reachable.add(v)) queue.addLast(v)
            }
        }

        // “仅经弱边可达”集合：从 root 仅沿 strong/internal 不可达、但在完整策略下可达。
        val stronglyReachable = LinkedHashSet<String>()
        val strongQ = ArrayDeque<String>()
        rootRefs.forEach { if (stronglyReachable.add(it)) strongQ.addLast(it) }
        val strongOut = LinkedHashMap<String, List<String>>()
        validEdges.forEach { e ->
            if (e.kind != EdgeKind.WEAK && edgeActive(e.kind)) {
                strongOut.getOrPut(e.from) { mutableListOf() }.let { (it as MutableList).add(e.to) }
            }
        }
        while (strongQ.isNotEmpty()) {
            val u = strongQ.removeFirst()
            strongOut[u].orEmpty().sorted().forEach { if (stronglyReachable.add(it)) strongQ.addLast(it) }
        }
        val weakOnly = reachable - stronglyReachable

        // ---- 2) 支配树：只在可达图上构建（不可达对象索引为 -1，不参与）。 ----
        val reachableList = reachable.sorted()
        val indexMap = HashMap<String, Int>()
        reachableList.forEachIndexed { i, id -> indexMap[id] = i + 1 } // 0 = VROOT
        val n = reachableList.size + 1
        val succ: Array<MutableList<Int>> = Array(n) { mutableListOf() }
        for (r in rootRefs) { if (r in indexMap) succ[0].add(indexMap[r]!!) }
        for (e in validEdges) {
            if (!edgeActive(e.kind)) continue
            val a = indexMap[e.from]; val b = indexMap[e.to]
            if (a != null && b != null) succ[a].add(b)
        }
        for (list in succ) list.sort()
        val idom = DominatorTree.build(succ.map { it.toList() }.toTypedArray())
        val dominatorOf: Map<String, String?> = reachableList.associateWith { id ->
            val d = idom[indexMap[id]!!]
            if (d == 0) null else reachableList[d - 1]
        }

        // ---- 3) 保留大小：支配子树求和（仅可转换大小进入数值聚合）。 ----
        val selfBytes: Map<String, Long?> = objects.associate { it.id to it.size.valueBytes }
        val children: Map<String?, List<String>> = reachableList.groupBy { dominatorOf[it] }
        val retained = HashMap<String, Long?>()
        val retainedStatus = HashMap<String, String>()
        fun computeSubtree(root: String?) {
            val kids = children[root].orEmpty().sorted()
            kids.forEach { computeSubtree(it) }
            if (root != null) {
                var allKnown = true
                var sum = 0L
                fun walk(u: String) {
                    val own = selfBytes[u]
                    if (own == null) allKnown = false else sum += own
                    children[u].orEmpty().forEach { walk(it) }
                }
                walk(root)
                retained[root] = if (allKnown) sum else null
                retainedStatus[root] = when {
                    !allKnown && (selfBytes[root] == null) -> "UNCONVERTIBLE_SIZE_IN_SUBTREE"
                    !allKnown -> "PARTIAL_UNCONVERTIBLE_DESCENDANT"
                    !objById[root]!!.complete -> "COMPUTED_FROM_INCOMPLETE_OBJECT"
                    else -> "EXACT"
                }
            }
        }
        children[null].orEmpty().sorted().forEach { computeSubtree(it) }

        // ---- 4) root 路径：保留全部“最短（等长）”候选；超过预算裁剪且记账。 ----
        val rootPaths = HashMap<String, List<RootPath>>()
        val truncated = mutableSetOf<String>()
        for (target in reachableList) {
            val paths = enumerateShortestPaths(
                target = target,
                rootRefs = rootRefs.toSet(),
                rootKindOf = snapshot.roots.associate { it.ref to it.kind },
                outTyped = outTyped,
                maxCandidates = params.maxPathCandidatesPerObject,
                truncatedSink = truncated,
            )
            rootPaths[target] = paths
        }

        // ---- 5) 泄漏候选：保留全部满足规则的对象，保留全部无法唯一判定时的候选。 ----
        val leakCandidates = reachableList.mapNotNull { id ->
            val reasons = mutableListOf<String>()
            val dom = dominatorOf[id]
            if (dom != null && retained[id] != null && retained[id]!! > 0 && children[id].orEmpty().isNotEmpty()) {
                reasons.add("DOMINATES_RETAINED_SUBTREE")
            }
            if (!objById[id]!!.complete) reasons.add("INCOMPLETE_OBJECT_REACHABLE")
            if (id in weakOnly) reasons.add("REACHABLE_ONLY_VIA_WEAK_EDGE_POLICY_V${'$'}{params.version}")
            if (rootPaths[id]!!.size > 1) reasons.add("MULTIPLE_EQUAL_LENGTH_ROOT_PATHS")
            if (id in truncated) reasons.add("ROOT_PATH_CANDIDATES_TRUNCATED")
            if (retainedStatus[id] != "EXACT") reasons.add(retainedStatus[id]!!)
            if (dom == null && id !in rootRefs) {
                // 没有支配父且非 root —— 理论上不会发生，记录为语义信号
                reasons.add("NO_DOMINATOR_NON_ROOT")
            }
            if (reasons.isEmpty()) null
            else LeakCandidate(
                id = "cand-${snapshot.meta.identity}-$id",
                objectId = id,
                reasonCodes = reasons,
                retainedSizeBytes = retained[id],
                dominator = dom,
                shortestPathLength = rootPaths[id]!!.firstOrNull()?.hops ?: -1,
                rootPathCount = rootPaths[id]!!.size,
            )
        }.sortedWith(compareByDescending<LeakCandidate> { it.retainedSizeBytes ?: -1L }.thenBy { it.objectId })

        // ---- 6) 确定性布局坐标（单位/坐标基准对外暴露：svg-pixel@layout-v1）。 ----
        val positions = layoutPositions(reachableList, succ, indexMap)

        val notes = buildList {
            add("支配树仅在 ${reachableList.size} 个 root 可达对象上构建；${objects.size - reachableList.size} 个不可达对象被排除。")
            add("弱引用保活策略 weakEdgesKeepAlive=${params.weakEdgesKeepAlive}（参数版本 v${params.version}）；仅经弱边可达对象=${weakOnly.size} 个。")
            if (truncated.isNotEmpty()) add("${truncated.size} 个对象的等长 root 路径候选达到预算 ${params.maxPathCandidatesPerObject}，已标记 ROOT_PATH_CANDIDATES_TRUNCATED。")
            add("地址不是稳定身份：对象身份采用原始对象 id；重复地址保留为不同节点。")
        }

        val nodes = objects.map { o ->
            NodeView(
                id = o.id,
                classId = o.classId,
                className = classNameOf(o.classId),
                addressRaw = o.addressRaw,
                x = positions[o.id]?.first ?: -1,
                y = positions[o.id]?.second ?: -1,
                complete = o.complete,
                reachable = o.id in reachable,
                selfSizeBytes = o.size.valueBytes,
                selfSizeRaw = o.size.raw,
                retainedSizeBytes = retained[o.id],
                retainedStatus = retainedStatus[o.id] ?: "UNREACHABLE_EXCLUDED",
                dominator = dominatorOf[o.id],
                rootPathCount = rootPaths[o.id]?.size ?: 0,
                allocationSiteId = o.allocationSiteId,
            )
        }
        val edges = validEdges.map { e ->
            EdgeView(e.from, e.to, e.kind, edgeActive(e.kind),
                treeEdge = dominatorOf[e.to] == e.from && edgeActive(e.kind), e.field)
        }.sortedWith(compareBy({ it.from }, { it.to }, { it.kind.name }))

        return AnalysisResult(
            snapshotIdentity = snapshot.meta.identity,
            paramsVersion = params.version,
            parentDependencyIds = parentDependencyIds,
            inputDigest = digest(snapshot),
            reachableCount = reachableList.size,
            nodes = nodes,
            edges = edges,
            rootPaths = rootPaths.filterValues { it.isNotEmpty() }.toSortedMap(),
            leakCandidates = leakCandidates,
            notes = notes,
        )
    }

    /**
     * BFS 分层枚举“最短”root 路径：长度相同的路径全部保留（多 root 等长路径场景）。
     * 反向记录前驱集合，再回溯全部候选；超过预算停止扩展并标记。
     */
    private fun enumerateShortestPaths(
        target: String,
        rootRefs: Set<String>,
        rootKindOf: Map<String, String>,
        outTyped: Map<String, List<Triple<String, EdgeKind, String?>>>,
        maxCandidates: Int,
        truncatedSink: MutableSet<String>,
    ): List<RootPath> {
        val dist = HashMap<String, Int>()
        val prevSets = HashMap<String, MutableSet<Triple<String, EdgeKind, String?>>>()
        val queue = ArrayDeque<String>()
        for (r in rootRefs.sorted()) {
            if (dist.putIfAbsent(r, 0) == null) queue.addLast(r)
        }
        var budget = MAX_TRAVERSAL_STEPS
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            if (u == target) continue
            for (edge in outTyped[u].orEmpty()) {
                val (v, kind, field) = edge
                if (--budget < 0) throw AnalysisBudgetException(MAX_TRAVERSAL_STEPS, MAX_TRAVERSAL_STEPS)
                val nd = dist[u]!! + 1
                when (dist[v]) {
                    null -> {
                        dist[v] = nd
                        prevSets.getOrPut(v) { mutableSetOf() }.add(Triple(u, kind, field))
                        queue.addLast(v)
                    }
                    nd -> prevSets.getOrPut(v) { mutableSetOf() }.add(Triple(u, kind, field))
                }
            }
        }
        if (target !in dist) return emptyList()

        val paths = mutableListOf<RootPath>()

        fun backtrack(node: String, chain: List<String>, kinds: List<EdgeKind>) {
            if (paths.size >= maxCandidates) {
                truncatedSink.add(target)
                return
            }
            if (node in rootRefs) {
                paths.add(
                    RootPath(
                        rootRef = node,
                        rootKind = rootKindOf[node] ?: "ROOT",
                        hops = chain.size - 1,
                        objectIds = chain.reversed(),
                        edgeKinds = kinds.reversed(),
                    ),
                )
                return
            }
            prevSets[node].orEmpty().sortedBy { it.first }.forEach { (p, kind, _) ->
                backtrack(p, chain + p, kinds + kind)
            }
        }
        backtrack(target, listOf(target), emptyList())
        return paths.sortedWith(compareBy({ it.rootRef }, { it.objectIds.joinToString(",") }))
    }

    private fun layoutPositions(
        reachableList: List<String>,
        succ: Array<MutableList<Int>>,
        indexMap: Map<String, Int>,
    ): Map<String, Pair<Int, Int>> {
        // 从 VROOT 的 BFS 分层；同层按稳定 id 排序。
        val layer = HashMap<String, Int>()
        val q = ArrayDeque<Int>()
        succ[0].forEach { q.addLast(it) }
        succ[0].sorted().forEach { layer[reachableList[it - 1]] = 0 }
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val u = reachableList[i - 1]
            for (j in succ[i]) {
                val v = reachableList[j - 1]
                if (v !in layer) { layer[v] = layer[u]!! + 1; q.addLast(j) }
            }
        }
        val byLayer = reachableList.filter { it in layer }.sortedWith(compareBy({ layer[it] }, { it }))
        val counts = HashMap<Int, Int>()
        return byLayer.associateWith {
            val l = layer[it]!!
            val row = counts.getOrDefault(l, 0)
            counts[l] = row + 1
            80 + l * 220 to 70 + row * 110
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
