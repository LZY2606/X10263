package hs.analysis

import hs.domain.*
import java.security.MessageDigest

/**
 * 内存分析核心。业务规则集中在此处（与存储、浏览器解耦），可被测试直接调用。
 *
 * 不变量：
 *  1. 支配树只在“可达图”上构建；不可达对象没有支配父级。
 *  2. 弱引用是否保活由显式 WeakPolicy 决定，结果记录 policyVersion。
 *  3. root 路径保留全部等长最短路径（无法唯一决定时保留全部合法候选）。
 *  4. 地址从不作为稳定身份；身份一律使用 objectId。
 */
class AnalysisEngine(private val budget: IngestBudget = IngestBudget()) {

    private val unitToBytes: Map<String, Long> = mapOf(
        "B" to 1L, "" to 1L, "KB" to 1024L, "K" to 1024L,
        "MB" to 1024L * 1024, "M" to 1024L * 1024, "GB" to 1024L * 1024 * 1024
    )

    fun analyze(snapshot: RawSnapshot, policy: WeakPolicy, nowMillis: Long = System.currentTimeMillis()): AnalysisResult {
        val diags = snapshot.diagnostics.toMutableList()
        val objs = snapshot.objects.associateBy { it.objectId }
        val ambiguities = mutableListOf<Ambiguity>()

        // ---- 规范化大小：无法转换留在原位并附诊断，绝不用 0 伪装 ----
        data class SizeVal(val bytes: Long, val unconvertible: Boolean, val raw: String)
        val sizes = mutableMapOf<String, SizeVal>()
        var unconvertibleCount = 0
        for (o in snapshot.objects) {
            val parsed = o.shallowSize.toDoubleOrNull()
            val factor = unitToBytes[o.unit.uppercase()] ?: 1L
            if (parsed == null || parsed < 0) {
                sizes[o.objectId] = SizeVal(0L, true, o.shallowSize + o.unit)
                unconvertibleCount++
                diags += Diagnostic("SIZE_LEFT_RAW", Severity.WARNING,
                    "对象 ${o.objectId} 的大小 '${o.shallowSize}${o.unit}' 无法规范化，保留原值且不计入保留大小",
                    o.declaredAt, o.shallowSize + o.unit, o.objectId)
            } else {
                sizes[o.objectId] = SizeVal((parsed * factor).toLong(), false, o.shallowSize + o.unit)
            }
        }

        // ---- 保活边：强边总是保活；弱边/内部边看显式策略；UNKNOWN 不保活 ----
        fun keepsAlive(kind: EdgeKind): Boolean = when (kind) {
            EdgeKind.STRONG -> true
            EdgeKind.WEAK -> policy.weakKeepsAlive
            EdgeKind.INTERNAL -> policy.internalKeepsAlive
            EdgeKind.UNKNOWN -> false
        }
        val validEdges = snapshot.edges.filter { it.fromObjectId in objs && it.toObjectId in objs }
        val keepEdges = validEdges.filter { keepsAlive(it.kind) }
        val keepSucc = LinkedHashMap<String, MutableList<Edge>>()
        val allSucc = LinkedHashMap<String, MutableList<Edge>>()
        for (o in snapshot.objects) { keepSucc[o.objectId] = mutableListOf(); allSucc[o.objectId] = mutableListOf() }
        for (e in keepEdges) keepSucc[e.fromObjectId]!!.add(e)
        for (e in validEdges) allSucc[e.fromObjectId]!!.add(e)

        // ---- 虚拟超级 root（SR）：所有 root 分类节点是 SR 的直接后继 ----
        val roots = snapshot.roots.filter { it.objectId in objs }
        val rootKindOf = roots.groupBy { it.objectId }.mapValues { it.value.sortedBy { r -> r.kind.name }.first() }
        val reachable = bfsReachable(roots.map { it.objectId }.distinct(), keepSucc)

        // ---- 可达图上的支配树（Cooper–Harvey–Kennedy 迭代算法）----
        val idom = dominators(reachable, roots.map { it.objectId }.toSortedSet().toList(), keepSucc)

        // ---- 支配子树 → 保留大小 ----
        val children = LinkedHashMap<String, MutableList<String>>()
        for (o in snapshot.objects) if (o.objectId in reachable) children[o.objectId] = mutableListOf()
        for ((node, dom) in idom) if (dom != null) children[dom]!!.add(node)
        for (v in children.values) v.sort()
        val order = topologicalFromRoots(children, roots.map { it.objectId }.distinct().sorted())
        val retainedBytes = LinkedHashMap<String, Long>()
        for (o in snapshot.objects) retainedBytes[o.objectId] = if (sizes[o.objectId]!!.unconvertible) 0L else sizes[o.objectId]!!.bytes
        for (node in order.reversed()) {
            var acc = retainedBytes[node] ?: 0L
            for (c in children[node].orEmpty()) acc += retainedBytes[c] ?: 0L
            retainedBytes[node] = acc
        }

        // ---- 全部等长最短 root 路径（保活边）----
        val allPaths = HashMap<String, List<RootPath>>()
        val rootSet = roots.map { it.objectId }.toSet()
        for (target in reachable) {
            val paths = allShortestRootPaths(target, rootSet, rootKindOf, keepSucc, budget.maxEqualPaths)
            if (paths.truncated) {
                diags += Diagnostic("PATH_CAP", Severity.WARNING,
                    "对象 $target 的等长 root 路径超过 ${budget.maxEqualPaths}，仅保留前 ${budget.maxEqualPaths} 条合法候选",
                    null, null, target)
            }
            allPaths[target] = paths.paths
        }

        // ---- 重复地址：诊断统计；地址永远不参与身份 ----
        val dupGroups = snapshot.objects.filter { it.address != null }.groupBy { it.address }.count { it.value.size > 1 }
        if (dupGroups > 0) diags += Diagnostic("DUP_ADDRESS", Severity.INFO,
            "存在 $dupGroups 组重复地址；地址不作为稳定身份，以 objectId 为准", null)
        snapshot.objects.filter { !it.complete }.forEach {
            diags += Diagnostic("INCOMPLETE_OBJECT", Severity.WARNING,
                "对象 ${it.objectId} 来自不完整块，保留可用字段，结论标注来源", it.declaredAt, null, it.objectId)
        }

        // ---- 语义无解：edge/root 只能用地址而该地址匹配多个对象 ----
        if (checkAddressAmbiguity(snapshot, objs, ambiguities)) {
            return buildResult(snapshot, policy, AnalysisStatus.SEMANTIC_UNRESOLVABLE, reachable,
                idom, retainedBytes, emptyMap(), emptyList(), diags, ambiguities, sizes, unconvertibleCount,
                dupGroups, nowMillis)
        }

        // ---- 支配信息（仅可达）----
        val depth = HashMap<String, Int>()
        for (r in roots.map { it.objectId }.distinct().sorted()) depth[r] = 0
        for (n in order) {
            val d = idom[n]
            if (d != null) depth[n] = (depth[d] ?: 0) + 1
        }
        val domInfos = order.map { DominatorInfo(it, idom[it], depth[it] ?: 0) }

        val retained = order.map { oid ->
            val s = sizes[oid]!!
            val paths = allPaths[oid].orEmpty()
            RetainedResult(
                objectId = oid,
                retainedSize = retainedBytes[oid] ?: 0L,
                unit = "B",
                reachable = true,
                dominatorParentId = idom[oid],
                shortestRootLength = paths.minOfOrNull { it.length },
                rootPathCount = paths.size,
                equalLengthRootPaths = paths,
                shallowSize = if (s.unconvertible) 0L else s.bytes,
                unconvertible = s.unconvertible,
                retainedBasis = "dominator-subtree over reachable graph; policy=${policy.version}; " +
                    "weakKeepsAlive=${policy.weakKeepsAlive}; unit=B; excludes-raw-sizes"
            )
        }

        val leaks = leakCandidates(objs, retained, allPaths, keepEdges, rootSet)

        return buildResult(snapshot, policy, AnalysisStatus.OK, reachable, idom, retainedBytes, allPaths,
            domInfos, diags, ambiguities, sizes, unconvertibleCount, dupGroups, nowMillis, leaks)
    }

    private fun buildResult(
        snapshot: RawSnapshot, policy: WeakPolicy, status: AnalysisStatus,
        reachable: Set<String>, idom: Map<String, String?>, retainedBytes: Map<String, Long>,
        allPaths: Map<String, List<RootPath>>, domInfos: List<DominatorInfo>,
        diags: List<Diagnostic>, ambiguities: List<Ambiguity>,
        sizes: Map<String, Any>, unconvertibleCount: Int, dupGroups: Int,
        nowMillis: Long, leaks: List<LeakCandidate> = emptyList()
    ): AnalysisResult {
        val summary = InputSummary(
            objectCount = snapshot.objects.size,
            edgeCount = snapshot.edges.size,
            rootCount = snapshot.roots.size,
            classCount = snapshot.classes.size,
            incompleteCount = snapshot.incomplete.size,
            weakEdgeCount = snapshot.edges.count { it.kind == EdgeKind.WEAK },
            strongEdgeCount = snapshot.edges.count { it.kind == EdgeKind.STRONG },
            internalEdgeCount = snapshot.edges.count { it.kind == EdgeKind.INTERNAL },
            duplicateAddressGroups = dupGroups,
            unconvertibleSizes = unconvertibleCount
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((snapshot.meta.snapshotId + "|" + snapshot.rawText + "|" + policy.version).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val retained = if (status == AnalysisStatus.OK) domInfos.map { di ->
            RetainedResult(di.objectId, retainedBytes[di.objectId] ?: 0, "B", true, di.idomObjectId,
                allPaths[di.objectId]?.minOfOrNull { it.length }, allPaths[di.objectId]?.size ?: 0,
                allPaths[di.objectId].orEmpty(), 0L, false, "dominator-subtree")
        } else emptyList()
        return AnalysisResult(
            analysisId = "an-${snapshot.meta.snapshotId}-${policy.version.substringAfterLast('/')}-${digest.take(8)}",
            snapshotId = snapshot.meta.snapshotId,
            policyVersion = policy.version,
            status = status,
            reachableObjectIds = reachable.sorted(),
            dominators = domInfos,
            retained = retained,
            leakCandidates = leaks,
            inputDigest = digest,
            inputSummary = summary,
            diagnostics = diags.sortedWith(compareBy({ it.pos?.line ?: 0 }, { it.code })),
            ambiguities = ambiguities,
            computedAtMillis = nowMillis,
            sizeUnit = "B",
            valueBasis = "shallow sizes parsed from raw <value><unit>; retained = dominator subtree sum in bytes; " +
                "raw/unconvertible values kept on object, excluded from sums"
        )
    }
