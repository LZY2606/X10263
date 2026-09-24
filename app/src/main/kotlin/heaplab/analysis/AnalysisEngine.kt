@file:OptIn(ExperimentalStdlibApi::class)
package heaplab.analysis

import heaplab.model.EdgeStrength
import heaplab.model.RawSnapshot
import heaplab.parse.RootClassifier
import java.security.MessageDigest

/**
 * 内存分析引擎（业务规则只在服务端/存储层，可被测试直接调用）。
 *
 * 关键不变量：
 *  1. 支配树只在可达图上构建；不可达对象不进入支配树，也不被任何对象保留。
 *  2. 弱引用是否保活由 Parameters.weakPolicy 显式决定。
 *  3. 多个等长 root 路径全部保留；地址从不作为稳定身份（仅用原始 objId）。
 *  4. 无法规范化的 shallowBytes 保持 null，保留大小按“可计字节”单独汇总并给 note。
 */
class AnalysisEngine(private val budget: AnalysisBudget = AnalysisBudget()) {

    fun analyze(
        snap: RawSnapshot,
        params: Parameters,
        runId: String,
        parentRunId: String? = null
    ): AnalysisOutcome {
        val digest = digest(snap)

        val corrupt = snap.diagnostics.filter { it.code == "CORRUPT_FORMAT" || it.code == "BUDGET_EXHAUSTED" }
        if (corrupt.any { it.code == "BUDGET_EXHAUSTED" }) {
            return AnalysisOutcome.BudgetExhausted(
                "解析资源预算耗尽，拒绝在不完整材料上构图",
                corrupt.map { Located(it.line, it.col, it.code, it.message, it.rawText) }
            )
        }
        if (corrupt.isNotEmpty()) {
            return AnalysisOutcome.FormatCorrupt(
                "快照存在结构性损坏，导入未形成完整材料",
                corrupt.map { Located(it.line, it.col, it.code, it.message, it.rawText) }
            )
        }
        val unresolved = snap.diagnostics.filter { it.code == "DANGLING_REF" && it.severity == "ERROR" }
        if (unresolved.isNotEmpty()) {
            return AnalysisOutcome.SemanticUnresolved(
                "存在无法解析的对象引用，保留路径语义无解",
                unresolved.map { Located(it.line, it.col, it.code, it.message, it.rawText) }
            )
        }

        val nodeIds = snap.objects.keys.sortedWith(stableObjectOrder(snap))

        val activeRoots = snap.roots
            .filter { it.resolved }
            .filter { params.rootCategoriesIncluded.isEmpty() || it.category in params.rootCategoriesIncluded }
            .distinctBy { it.objId }
            .sortedWith(compareBy({ it.category }, { it.objId }))

        // 边分类（可达 / 支配）
        val graphEdges = snap.edges.filter { it.resolved }.map { e ->
            val reach = when (e.strength) {
                EdgeStrength.STRONG, EdgeStrength.INTERNAL -> true
                EdgeStrength.WEAK -> when (params.weakPolicy) {
                    WeakPolicy.KEEP_WEAK -> true
                    WeakPolicy.DROP_WEAK -> false
                    WeakPolicy.TRAVERSE_NO_DOMINATE -> true
                }
            }
            val dominates = reach && when (e.strength) {
                EdgeStrength.WEAK -> params.weakPolicy == WeakPolicy.KEEP_WEAK
                EdgeStrength.INTERNAL -> params.internalEdgesCount
                EdgeStrength.STRONG -> true
            }
            GraphEdge(e.edgeUid, e.fromId, e.toId, e.strength, e.label, reach, dominates)
        }

        budget.check(nodeIds.size, graphEdges.size)?.let { return it }

        val reachAdj = LinkedHashMap<String, MutableList<String>>()
        val domAdj = LinkedHashMap<String, MutableList<String>>()
        val strongAdj = LinkedHashMap<String, MutableList<String>>()
        for (id in nodeIds) {
            reachAdj[id] = mutableListOf(); domAdj[id] = mutableListOf(); strongAdj[id] = mutableListOf()
        }
        for (ge in graphEdges) {
            if (ge.reach) reachAdj[ge.fromId]!!.add(ge.toId)
            if (ge.dominates) domAdj[ge.fromId]!!.add(ge.toId)
            if (ge.strength != EdgeStrength.WEAK) strongAdj[ge.fromId]!!.add(ge.toId)
        }

        // ---- 可达性（多源 BFS），并区分“仅经弱边可达” ----
        val reachable = HashSet<String>()
        val reachViaWeakOnly = HashSet<String>()
        run {
            val dq = ArrayDeque<String>()
            for (r in activeRoots) {
                if (reachable.add(r.objId)) dq.add(r.objId)
            }
            while (dq.isNotEmpty()) {
                val u = dq.removeFirst()
                for (v in reachAdj[u].orEmpty()) if (reachable.add(v)) dq.add(v)
            }
            // 仅弱可达：去掉弱边后不可达，但在当前策略下可达
            if (params.weakPolicy != WeakPolicy.KEEP_WEAK) {
                val strong = HashSet<String>()
                val dq2 = ArrayDeque<String>()
                for (r in activeRoots) if (strong.add(r.objId)) dq2.add(r.objId)
                while (dq2.isNotEmpty()) {
                    val u = dq2.removeFirst()
                    for (v in strongAdj[u].orEmpty()) {
                        if (v in reachable && strong.add(v)) dq2.add(v)
                    }
                }
                for (id in reachable) if (id !in strong) reachViaWeakOnly.add(id)
            }
        }

        // ---- 支配图：虚拟根 VROOT -> activeRoots，只含可达节点与参与支配的边 ----
        val VROOT = "VROOT"
        val succ = LinkedHashMap<String, MutableList<String>>()
        succ[VROOT] = activeRoots.map { it.objId }.distinct().toMutableList()
        for (id in nodeIds) if (id in reachable) succ[id] = domAdj[id]!!.filter { it in reachable }.toMutableList()
        val idom = LengauerTarjan(VROOT, succ).compute()

        // ---- 保留大小：支配子树（支配树只在可达图上） ----
        val domChildren = LinkedHashMap<String, MutableList<String>>()
        for (n in succ.keys) domChildren[n] = mutableListOf()
        for ((node, dom) in idom) if (dom != null) domChildren.getOrPut(dom) { mutableListOf() }.add(node)
        val subtreeBytes = LinkedHashMap<String, Long?>()
        val subtreeCount = LinkedHashMap<String, Int>()
        val subtreeMembers = LinkedHashMap<String, List<String>>()
        val order = topologicalFromRoots(domChildren, VROOT)
        for (n in order.reversed()) {
            var bytes: Long? = shallowByte(snap, n)
            var count = 1
            val members = mutableListOf(n)
            for (c in domChildren[n].orEmpty()) {
                val cb = subtreeBytes[c]
                bytes = if (bytes == null) cb else if (cb == null) bytes else bytes + cb
                count += subtreeCount[c] ?: 0
                subtreeMembers[c]?.let { members.addAll(it) }
            }
            subtreeBytes[n] = bytes
            subtreeCount[n] = count
            subtreeMembers[n] = members
        }

        // ---- 全部等长 root 路径 ----
        val rootPaths = try {
            shortestRootPaths(activeRoots, reachAdj, graphEdges, snap, budget)
        } catch (e: PathBudgetExceeded) {
            return AnalysisOutcome.BudgetExhausted(
                "等长 root 路径枚举超过预算，拒绝静默截断候选",
                listOf(Located(0, 0, "BUDGET_EXHAUSTED", "路径枚举预算 ${budget.maxPathEnumeration}", ""))
            )
        }

        val notes = mutableListOf<String>()
        if (snap.objects.values.any { it.size.bytes == null }) {
            notes.add("存在无法规范化为字节的对象 size，相关保留大小为 null（不以 0 伪装）。")
        }
        if (snap.duplicateAddresses.isNotEmpty()) {
            notes.add("发现重复地址 ${snap.duplicateAddresses.size} 组；对象身份使用原始 objId，地址仅作展示。")
        }
        if (params.weakPolicy == WeakPolicy.TRAVERSE_NO_DOMINATE) {
            notes.add("弱边参与可达但不参与支配，弱引用对象的保留归因到强/内部支配者。")
        }

        val results = LinkedHashMap<String, ObjectResult>()
        for (id in nodeIds) {
            val paths = rootPaths[id].orEmpty().sortedWith(
                compareBy({ it.length }, { it.rootCategory }, { it.rootObjId },
                    { it.edgeUids.joinToString(",") })
            )
            val dom = idom[id]
            results[id] = ObjectResult(
                objId = id,
                reachable = id in reachable,
                reachableViaWeakOnly = id in reachViaWeakOnly,
                dominatorId = dom,
                dominatorIsVirtualRoot = dom == VROOT,
                shallowBytes = shallowByte(snap, id),
                shallowObjectCount = 1,
                retainedBytes = if (id in reachable) subtreeBytes[id] else null,
                retainedObjectCount = if (id in reachable) subtreeCount[id] ?: 0 else 0,
                retainedIds = if (id in reachable) subtreeMembers[id].orEmpty()
                    .filter { it != VROOT }.sortedWith(stableObjectOrder(snap)) else emptyList(),
                rootPaths = paths,
                ambiguity = paths.size > 1
            )
        }

        val dominatorOrder = order.filter { it != VROOT }.sortedWith(stableObjectOrder(snap))
        val unreachable = nodeIds.filter { it !in reachable }
        val leakCandidates = results.values
            .filter { it.reachable && it.dominatorIsVirtualRoot }
            .map { r ->
                val o = snap.objects.getValue(r.objId)
                val site = o.allocSiteId?.let { snap.allocationSites[it] }
                LeakCandidate(
                    objId = r.objId,
                    retainedBytes = r.retainedBytes,
                    retainedObjectCount = r.retainedObjectCount,
                    reason = buildLeakReason(snap, r),
                    allocationSite = o.allocSiteId,
                    siteLocation = site?.locationRaw,
                    siteEpochRaw = site?.epochMillisRaw,
                    rootDistance = (r.rootPaths.minOfOrNull { it.length }) ?: 0
                )
            }
            .sortedWith(compareByDescending<LeakCandidate> { it.retainedBytes ?: -1L }
                .thenByDescending { it.retainedObjectCount }
                .thenBy { it.objId })

        return AnalysisOutcome.Ok(
            AnalysisResult(
                runId = runId,
                digest = digest,
                parameters = params,
                parentRunId = parentRunId,
                nodeIds = nodeIds,
                edges = graphEdges.sortedWith(compareBy({ it.fromId }, { it.toId }, { it.edgeUid })),
                virtualRootId = VROOT,
                objects = results,
                dominatorOrder = dominatorOrder,
                leakCandidates = leakCandidates,
                unreachableIds = unreachable,
                weakOnlyReachableIds = reachViaWeakOnly.sortedWith(stableObjectOrder(snap)),
                notes = notes
            )
        )
    }

    private fun shallowByte(snap: RawSnapshot, id: String): Long? =
        snap.objects[id]?.size?.bytes

    private fun buildLeakReason(snap: RawSnapshot, r: ObjectResult): String {
        val parts = mutableListOf("直接受虚拟根支配")
        if (r.ambiguity) parts.add("存在 ${r.rootPaths.size} 条等长 root 路径，归因不唯一")
        if (r.reachableViaWeakOnly) parts.add("仅经弱边可达（依当前策略）")
        if (snap.objects[r.objId]?.allocSiteId != null) parts.add("带 allocation site")
        return parts.joinToString("；")
    }

    private fun digest(snap: RawSnapshot): InputDigest {
        val raw = buildString {
            append(snap.snapshotId).append('|')
            for (o in snap.objects.values.sortedBy { it.objId }) {
                append(o.objId).append(',').append(o.address.raw).append(',')
                    .append(o.classIdRaw).append(',').append(o.size.raw).append(';')
            }
            append('|')
            for (e in snap.edges.sortedBy { it.edgeUid }) {
                append(e.edgeUid).append(',').append(e.fromId).append(',').append(e.toId)
                    .append(',').append(e.strength).append(';')
            }
            append('|')
            for (r in snap.roots.sortedBy { it.rootUid }) {
                append(r.rootUid).append(',').append(r.objId).append(',').append(r.categoryRaw).append(';')
            }
        }
        val md5 = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        val hash = md5.joinToString("") { "%02x".format(it) }
        return InputDigest(
            snapshotId = snap.snapshotId,
            objectCount = snap.objects.size,
            edgeCount = snap.edges.size,
            rootCount = snap.roots.size,
            errorDiagnosticCount = snap.diagnostics.count { it.severity == "ERROR" },
            contentHash = hash
        )
    }

    private fun stableObjectOrder(snap: RawSnapshot): Comparator<String> =
        Comparator { a, b ->
            val sa = snap.objects[a]?.startLine ?: Int.MAX_VALUE
            val sb = snap.objects[b]?.startLine ?: Int.MAX_VALUE
            if (sa != sb) sa.compareTo(sb) else a.compareTo(b)
        }
}
