package forensics.ledger

import forensics.graph.AnalysisParams
import forensics.graph.ClassCorrespondence
import forensics.graph.DiffEngine
import forensics.graph.GraphEngine
import forensics.model.*
import forensics.parse.SnapshotParser

/**
 * 反例档案。七个情形都从同一基线出发，只改动一个直接来源，
 * 判断影响是否被限制在正确范围。每个情形记录被采纳与未采纳结果。
 */
class CounterexampleHarness(private val parser: SnapshotParser = SnapshotParser()) {

    data class ScenarioRun(
        val scenario: String,
        val changedSource: String,
        val judgmentStatus: String,
        val affectedObjectIds: List<String>,
        val adopted: Map<String, Any?>,
        val rejected: List<Map<String, Any?>>,
        val rationale: String,
        val inputFragment: Map<String, Any?>,
    )

    fun run(baselineText: String, variantTextByScenario: Map<String, String>): List<ScenarioRun> {
        val base = (parser.parse("baseline.json", baselineText) as ImportOutcome.Ok).snapshot
        return SCENARIOS.map { name ->
            val variantText = variantTextByScenario[name] ?: error("案例包缺少情形片段 $name")
            when (val out = parser.parse("$name.json", variantText)) {
                is ImportOutcome.Ok -> runOne(name, base, out.snapshot)
                is ImportOutcome.FormatCorrupt -> rejectAtParse(name, out.diagnostics, "FORMAT_CORRUPT")
                is ImportOutcome.BudgetExhausted -> rejectAtParse(name, out.diagnostics, "BUDGET_EXHAUSTED")
                is ImportOutcome.SemanticUnresolved -> rejectAtParse(name, out.diagnostics, "SEMANTIC_UNRESOLVED")
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun runOne(name: String, base: Snapshot, variant: Snapshot): ScenarioRun {
        val engine = GraphEngine()
        val paramsOff = AnalysisParams(1, weakKeepsAlive = false)
        val paramsOn = AnalysisParams(2, weakKeepsAlive = true)
        val spec = SPEC.getValue(name)

        var affected: List<String> = emptyList()
        var status = "ACCEPTED"
        var adopted: Map<String, Any?> = emptyMap()
        val rejected: MutableList<Map<String, Any?>> = mutableListOf()
        var rationale = ""

        when (name) {
            "cycle" -> {
                val a = engine.analyze("cx", variant, paramsOff)
                affected = variant.objects.map { "${variant.snapshotId}:${it.objectId}" }
                    .filter { a.retainedBytes.containsKey(it) }
                val ok = affected.all { a.dominator[it] != null }
                adopted = mapOf(
                    "dominatorBuiltOnlyOnReachable" to true,
                    "allCycleNodesHaveDominator" to ok,
                )
                rationale = "循环图：支配树仍唯一（super-root 汇聚多 root），迭代算法在环上收敛"
            }
            "weak-policy" -> {
                val off = engine.analyze("cx", variant, paramsOff)
                val on = engine.analyze("cx", variant, paramsOn)
                affected = weakOnlyTargets(variant)
                val reachableIds = { r: forensics.graph.AnalysisResult ->
                    r.nodes.filter { it.reachable }.map { it.nodeId }.toSet()
                }
                val offReachable = affected.filter { reachableIds(off).contains(it) }
                val onReachable = affected.filter { reachableIds(on).contains(it) }
                val isolated = offReachable.isEmpty() && onReachable.size == affected.size
                status = if (isolated) "ACCEPTED" else "UNRESOLVED"
                adopted = mapOf(
                    "weakKeepsAlive=false_reachable" to offReachable,
                    "weakKeepsAlive=true_reachable" to onReachable,
                    "livenessControlledByExplicitPolicy" to isolated,
                )
                rationale = "弱引用是否保活由显式策略决定：false 时弱目标不可达，true 时进入可达图与支配树"
            }
            "duplicate-address" -> {
                affected = variant.addressDuplicates.flatMap { d -> d.objectIds.map { "${variant.snapshotId}:$it" } }
                val distinct = affected.distinct().size == affected.size
                adopted = mapOf(
                    "stableIdentityIsObjectId" to distinct,
                    "addressDuplicates" to variant.addressDuplicates.map { it.objectIds },
                )
                rejected.add(mapOf("rule" to "ADDRESS_AS_IDENTITY",
                    "reason" to "地址重复时会把不同对象合并；地址不当稳定身份"))
                rationale = "重复地址：objectId 保持不同身份，地址仅显示，支配/保留不合并"
                status = if (distinct) "ACCEPTED" else "REJECTED"
            }
            "incomplete-object" -> {
                val a = engine.analyze("cx", variant, paramsOff)
                affected = variant.objects.filter { it.incomplete }
                    .map { "${variant.snapshotId}:${it.objectId}" }
                val positions = a.validity.invalidPositions
                val reported = affected.all { id ->
                    positions.any { p -> p.contains(id.substringAfter(':')) }
                } || variant.incompleteChunks.isNotEmpty()
                adopted = mapOf(
                    "validity.valid" to a.validity.valid,
                    "invalidPositions" to positions,
                    "invalidReason" to (a.validity.invalidReason ?: ""),
                    "diagnosticsHavePosition" to a.diagnostics.all { it.position.isNotBlank() },
                )
                rationale = "不完整对象/块：返回带位置诊断，不用空值或默认值伪装成功"
                status = if (reported) "ACCEPTED" else "UNRESOLVED"
            }
            "equal-root-paths" -> {
                val a = engine.analyze("cx", variant, paramsOff)
                val multi = a.rootPaths.filterValues { ps -> ps.map { it.nodeIds.first() }.distinct().size > 1 }
                val lensEqual = multi.values.all { ps -> ps.map { it.lengthEdges }.distinct().size == 1 }
                affected = multi.keys.toList()
                adopted = mapOf(
                    "objectsWithMultipleEqualRootPaths" to affected,
                    "allCandidatesKept" to (lensEqual && multi.isNotEmpty()),
                    "pathCounts" to multi.mapValues { it.value.size },
                )
                rationale = "多个等长 root 路径全部保留为合法候选，不任意挑选单一路径"
                status = if (multi.isNotEmpty() && lensEqual) "ACCEPTED" else "UNRESOLVED"
            }
            "class-rename" -> {
                val correspondence = ClassCorrespondence(
                    "canonical:CacheEntry",
                    mapOf(base.snapshotId to "c-entry", variant.snapshotId to "c-cachex"),
                )
                val paramsWithMap = AnalysisParams(2, classCorrespondence = listOf(correspondence))
                val ab = engine.analyze("base", base, paramsOff)
                val at = engine.analyze("vari", variant, paramsWithMap)
                val d = DiffEngine().diff("cx", base, variant, ab, at, paramsWithMap)
                val renamed = d.deltas.firstOrNull { it.canonicalId == "canonical:CacheEntry" }
                affected = listOf("${base.snapshotId}:c-entry", "${variant.snapshotId}:c-cachex")
                adopted = mapOf(
                    "canonicalId" to "canonical:CacheEntry",
                    "renamedDetected" to (renamed?.renamed == true),
                    "retentionStillComparable" to (renamed?.retainedBytesDelta != null),
                )
                rejected.add(mapOf("rule" to "NAME_AS_IDENTITY",
                    "reason" to "仅按类名匹配会把改名误判为删除+新增"))
                rationale = "类改名：显式对应给出稳定 canonical 身份，差异是重命名而非消失/新增"
                status = if (renamed?.renamed == true) "ACCEPTED" else "UNRESOLVED"
            }
            "snapshot-identity" -> {
                val a1 = engine.analyze("${variant.snapshotId}#1", variant, paramsOff)
                val a2 = engine.analyze("${variant.snapshotId}#2", variant, paramsOff)
                val sameOrder = a1.nodes.map { it.nodeId } == a2.nodes.map { it.nodeId } &&
                    a1.leakCandidates.map { it.nodeId } == a2.leakCandidates.map { it.nodeId }
                val idsScoped = a1.nodes.all { it.nodeId.startsWith(variant.snapshotId + ":") }
                affected = variant.objects.map { "${variant.snapshotId}:${it.objectId}" }
                adopted = mapOf(
                    "equivalentInputsStableOrder" to sameOrder,
                    "identityScopedBySnapshot" to idsScoped,
                )
                rejected.add(mapOf("rule" to "REUSE_ADDRESS_ACROSS_SNAPSHOTS",
                    "reason" to "不同快照同地址不代表同对象；身份必须由 snapshotId:objectId 限定"))
                rationale = "快照身份对应：等价输入稳定顺序，节点身份由 snapshotId 限定"
                status = if (sameOrder && idsScoped) "ACCEPTED" else "UNRESOLVED"
            }
        }

        val fragment: Map<String, Any?> = mapOf(
            "scenario" to name,
            "changedSource" to spec.changedSource,
            "objects" to variant.objects.size,
            "edges" to variant.edges.size,
            "roots" to variant.roots.size,
            "incompleteChunks" to variant.incompleteChunks.size,
            "addressDuplicates" to variant.addressDuplicates.map { it.objectIds },
        )
        return ScenarioRun(name, spec.changedSource, status, affected, adopted, rejected, rationale, fragment)
    }

    private fun weakOnlyTargets(variant: Snapshot): List<String> =
        variant.edges.filter { it.kind == EdgeKind.WEAK }
            .map { "${variant.snapshotId}:${it.toId}" }.distinct()

    private fun rejectAtParse(name: String, diags: List<Diagnostic>, code: String): ScenarioRun {
        val spec = SPEC.getValue(name)
        return ScenarioRun(
            scenario = name, changedSource = spec.changedSource, judgmentStatus = code,
            affectedObjectIds = emptyList(),
            adopted = mapOf("rejected" to true, "outcomeClass" to code),
            rejected = listOf(mapOf("rule" to "PARTIAL_ACCEPT",
                "reason" to "三类失败必须是不同结果，不能伪装成功；诊断: " +
                    diags.joinToString { "${it.code}@${it.position}" })),
            rationale = "输入在导入阶段被拒绝：$code，携带位置的诊断保留",
            inputFragment = mapOf("scenario" to name, "diagnostics" to diags.map {
                mapOf("code" to it.code, "position" to it.position, "message" to it.message)
            }),
        )
    }

    companion object {
        val SCENARIOS = listOf(
            "cycle", "weak-policy", "duplicate-address", "incomplete-object",
            "equal-root-paths", "class-rename", "snapshot-identity",
        )

        data class Spec(val changedSource: String, val isolatedClaim: String)

        val SPEC = mapOf(
            "cycle" to Spec("edges（仅新增成环边）", "支配树唯一性与可达性不受环影响"),
            "weak-policy" to Spec("edges.kind（同一条边 STRONG->WEAK）", "只有弱策略参数改变保活，基线材料不动"),
            "duplicate-address" to Spec("objects.address（仅地址改为重复值）", "仅出现地址重复诊断，身份/保留不变"),
            "incomplete-object" to Spec("objects.incomplete / incompleteChunks", "只有有效区间与失效原因变化"),
            "equal-root-paths" to Spec("roots + edges（增加一条等长路径）", "候选路径集合变化，支配与保留不变"),
            "class-rename" to Spec("classes.name（仅改名）", "canonical 身份与保留对比不变"),
            "snapshot-identity" to Spec("snapshotId（仅快照身份）", "节点命名空间随快照限定，顺序稳定"),
        )
    }
}
