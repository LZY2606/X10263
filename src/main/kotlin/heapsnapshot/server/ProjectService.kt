package heapsnapshot.server

import heapsnapshot.analysis.*
import heapsnapshot.cases.CasePack
import heapsnapshot.domain.*
import heapsnapshot.parser.SnapshotParser
import heapsnapshot.store.HeapStore
import kotlinx.serialization.json.JsonObject

/**
 * 应用编排层：业务规则（可达性、支配树、弱引用策略、差异对应、失效）全部在此，
 * 可被测试直接调用，不依赖浏览器。
 */
class ProjectService(
    val store: HeapStore,
    private val engine: AnalysisEngine = AnalysisEngine(),
    private val diffEngine: ClassDiffEngine = ClassDiffEngine(),
    private val parser: SnapshotParser = SnapshotParser(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    val policies = linkedMapOf(
        "policy-v1" to AnalysisPolicy.v1(0L),
        "policy-v2" to AnalysisPolicy.v2(0L)
    )

    sealed class ImportOutcome {
        data class Imported(val event: LedgerEvent, val snapshot: RawSnapshot) : ImportOutcome()
        data class Failed(val failure: FailureOutcome) : ImportOutcome()
    }

    fun import(projectId: String, branch: String, rawText: String, parentEventId: String? = null): ImportOutcome {
        return when (val r = parser.parse(rawText)) {
            is SnapshotParser.Result.Failed -> ImportOutcome.Failed(r.outcome)
            is SnapshotParser.Result.Ok -> {
                val event = store.appendEvent(
                    projectId, branch,
                    EventPayload.ImportSnapshot(
                        "material:${r.snapshot.snapshotId}",
                        r.snapshot.header.parserVersion,
                        r.snapshot.diagnostics
                    ),
                    parentEventId
                )
                store.saveMaterial(projectId, rawText, r.snapshot, event.eventId)
                ImportOutcome.Imported(event, r.snapshot)
            }
        }
    }

    /** 用指定策略版本计算；结果记录输入摘要、参数版本、父级依赖。 */
    fun runAnalysis(
        projectId: String, branch: String, snapshot: RawSnapshot, policyVersion: String,
        parentEventId: String? = null, dependencyIds: List<String>
    ): Pair<LedgerEvent, AnalysisResult> {
        val policy = policies[policyVersion]
            ?: throw IllegalArgumentException("未知策略版本: $policyVersion")
        val event = store.appendEvent(projectId, branch, EventPayload.Analysis(
            resultId = "pending", snapshotId = snapshot.snapshotId, policyVersion = policyVersion,
            inputSummary = InputSummary(0, 0, 0, 0, ""), dependencyIds = dependencyIds, valid = true
        ), parentEventId)
        val result = engine.analyze(
            snapshot, policy.copy(createdAtEpochMs = event.epochMs),
            createdEventId = event.eventId,
            resultId = "analysis:${snapshot.snapshotId}:$policyVersion:${event.eventId}",
            dependencyIds = dependencyIds
        )
        store.saveAnalysis(projectId, result)
        // 用真实结果摘要回写事件载荷（事件一旦写出不可改，因此追加一个 Analysis 事件替代）。
        val corrected = store.appendEvent(projectId, branch, EventPayload.Analysis(
            resultId = result.resultId,
            snapshotId = result.snapshotId,
            policyVersion = result.policyVersion,
            inputSummary = result.inputSummary,
            dependencyIds = result.parentDependencyIds,
            valid = true
        ), event.eventId)
        return corrected to result
    }

    fun revisePolicy(
        projectId: String, branch: String, oldVersion: String, newVersion: String,
        patch: JsonObject, note: String, parentEventId: String? = null
    ): LedgerEvent {
        policies[newVersion] = policies[newVersion]
            ?: (if (newVersion == "policy-v2") AnalysisPolicy.v2(clock()) else throw IllegalArgumentException("未知新策略: $newVersion"))
        return store.appendEvent(projectId, branch,
            EventPayload.ParamRevision(oldVersion, newVersion, patch, note), parentEventId)
    }

    fun recordSourceChange(
        projectId: String, branch: String, materialRef: String, changedLocalIds: List<String>,
        note: String, parentEventId: String? = null
    ): LedgerEvent = store.appendEvent(projectId, branch,
        EventPayload.SourceChange(materialRef, changedLocalIds, note), parentEventId)

    fun recordDecision(
        projectId: String, branch: String, targetResultId: String, verdict: String,
        policyVersion: String?, rationale: String, conflictsWithEventId: String? = null,
        parentEventId: String? = null
    ): LedgerEvent = store.appendEvent(projectId, branch,
        EventPayload.Decision(targetResultId, verdict, policyVersion, rationale, conflictsWithEventId), parentEventId)

    fun recordCounterExample(
        projectId: String, branch: String, ce: EventPayload.CounterExample, parentEventId: String? = null
    ): LedgerEvent = store.appendEvent(projectId, branch, ce, parentEventId)

    fun runDiff(projectId: String, branch: String, base: RawSnapshot, other: RawSnapshot,
                baseAnalysis: AnalysisResult, otherAnalysis: AnalysisResult,
                parentEventId: String? = null): Pair<LedgerEvent, SnapshotClassDiff> {
        val diff = diffEngine.diff(base, other, baseAnalysis, otherAnalysis)
        val event = store.appendEvent(projectId, branch, EventPayload.Analysis(
            resultId = "diff:${base.snapshotId}:${other.snapshotId}",
            snapshotId = other.snapshotId,
            policyVersion = baseAnalysis.policyVersion,
            inputSummary = otherAnalysis.inputSummary,
            dependencyIds = listOf(baseAnalysis.resultId, otherAnalysis.resultId),
            valid = true
        ), parentEventId)
        store.saveDiff(projectId, "diff:${base.snapshotId}:${other.snapshotId}", event.eventId, diff)
        return event to diff
    }

    /** 使旧结果失效：历史记录不删除，只写失效原因与有效区间终点。 */
    fun invalidate(oldResultId: String, untilEventId: String, reason: String) =
        store.invalidateAnalysis(oldResultId, untilEventId, reason)

    // ---------- 历史重放视图 ----------
    /**
     * 回到指定事件时可见的结论：基于该事件之前（含）的材料版本、策略版本和决定。
     * 不拿最新结果覆盖历史。
     */
    fun viewAt(projectId: String, branch: String, eventId: String): HistoricalView {
        val all = store.events(projectId, branch)
        val upto = all.indexOfFirst { it.eventId == eventId }
        require(upto >= 0) { "事件不在分支 $branch 中: $eventId" }
        val slice = all.subList(0, upto + 1)
        var policyVersion = "policy-v1"
        val accepted = HashMap<String, String>() // resultId -> verdict
        val visibleResults = LinkedHashMap<String, AnalysisResult>()
        val materials = LinkedHashSet<String>()
        slice.forEach { ev ->
            when (val p = ev.payload) {
                is EventPayload.ParamRevision -> policyVersion = p.newPolicyVersion
                is EventPayload.Analysis -> {
                    val r = store.loadAnalysis(p.resultId)
                    if (r != null) visibleResults[p.resultId] = r
                }
                is EventPayload.Decision -> accepted[p.targetResultId] = p.verdict
                is EventPayload.ImportSnapshot -> materials += p.materialRef
                else -> Unit
            }
        }
        return HistoricalView(
            atEventId = eventId,
            policyVersionAtPoint = policyVersion,
            visibleResultIds = visibleResults.keys.toList(),
            decisions = accepted,
            materialRefs = materials.toList(),
            events = slice
        )
    }

    fun loadSnapshot(snapshotId: String): RawSnapshot? {
        val raw = store.rawMaterial(snapshotId) ?: return null
        return (parser.parse(raw) as? SnapshotParser.Result.Ok)?.snapshot
    }

    fun bootstrapCasePack(pack: CasePack, projectId: String = "proj-forensics"): BootstrapReport {
        store.createProject(projectId, pack.title)
        val branch = BranchId.BASELINE.name
        val imp0 = import(projectId, branch, resourceText(pack.baselineSnapshotFile))
        check(imp0 is ImportOutcome.Imported) { "基线导入失败: ${(imp0 as ImportOutcome.Failed).failure}" }
        val (evtA0, resV1) = runAnalysis(projectId, branch, imp0.snapshot, "policy-v1",
            dependencyIds = listOf(imp0.event.eventId))

        // 参数修订：弱引用显式保活（只改变派生层，不可变材料不动）。
        val rev = revisePolicy(projectId, branch, "policy-v1", "policy-v2",
            kotlinx.serialization.json.buildJsonObject { put("weakKeepsAlive", kotlinx.serialization.json.JsonPrimitive(true)) },
            "参数修订：弱引用改为保活以复核 cache 链")
        val (evtA1, resV2) = runAnalysis(projectId, branch, imp0.snapshot, "policy-v2",
            parentEventId = rev.eventId, dependencyIds = listOf(imp0.event.eventId, rev.eventId))
        invalidate(resV1.resultId, evtA1.eventId, "POLICY_SUPERSEDED: 策略版本 policy-v2 生效，v1 结果作为历史保留")

        // 只影响局部依赖的源数据改动：导入第二快照（对象身份沿用，地址全部变化）。
        val imp1 = import(projectId, branch, resourceText(pack.secondSnapshotFile), evtA1.eventId)
        check(imp1 is ImportOutcome.Imported) { "第二快照导入失败" }
        val changed = imp1.snapshot.objects.map { it.objectId }.sorted()
        val sc = recordSourceChange(projectId, branch, "material:${imp1.snapshot.snapshotId}", changed,
            "第二快照：类改名 + 地址整体搬迁，稳定 objectId 不变", imp1.event.eventId)
        val (evtA2, res1V2) = runAnalysis(projectId, branch, imp1.snapshot, "policy-v2",
            parentEventId = sc.eventId, dependencyIds = listOf(imp1.event.eventId))
        runDiff(projectId, branch, imp0.snapshot, imp1.snapshot, resV2, res1V2, evtA2.eventId)

        // 互相冲突的人工决定：接受 v2 结论，同时拒绝 v1 候选（两者都留档）。
        recordDecision(projectId, branch, resV2.resultId, "ACCEPTED", "policy-v2",
            "弱引用链保活解释 WTARGET 可达性，接受泄漏路径候选", null, evtA1.eventId)
        recordDecision(projectId, branch, resV1.resultId, "REJECTED", "policy-v1",
            "与运行时观察冲突：WTARGET 实际仍存活，拒绝‘不可达’结论（历史保留）", null, evtA1.eventId)

        // 反例档案：7 个边界组合，只改动一个直接来源。
        replayBoundaryScenarios(projectId, pack, imp0.snapshot, resV1, resV2)

        return BootstrapReport(projectId, resV1.resultId, resV2.resultId)
    }

    private fun replayBoundaryScenarios(
        projectId: String, pack: CasePack, baseline: RawSnapshot,
        resV1: AnalysisResult, resV2: AnalysisResult
    ) {
        pack.scenarios.forEachIndexed { i, sc ->
            val branch = if (sc.caseId == "bx-weak-policy") BranchId.PRINCIPLES.name else BranchId.BOUNDARY.name
            val principle = sc.principle?.let { PrincipleCode.valueOf(it) }
            val judgement = principle?.let { code ->
                val (holds, ids, rationale, observed) = CaseAssertions.evaluate(code, baseline, resV1, resV2)
                PrincipleJudgement(code, holds, ids, rationale, observed)
            }
            val adopted = if (sc.adoptedPolicyVersion == "policy-v2") resV2.resultId else resV1.resultId
            val rejected = sc.rejectedPolicyVersion?.let { v -> if (v == "policy-v2") resV2.resultId else resV1.resultId }
            val ce = EventPayload.CounterExample(
                caseId = sc.caseId,
                branch = branch,
                directSource = sc.directSource,
                mutation = sc.mutationKind,
                inputFragment = sc.inputFragment,
                affectedObjectIds = sc.affectedObjectIds,
                scopeClaim = sc.scopeClaim,
                scopeHolds = judgement?.holds ?: true,
                judgement = judgement,
                adoptedResultId = adopted,
                rejectedResultId = rejected
            )
            store.appendEvent(projectId, branch, ce)
        }
    }

    private fun resourceText(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("cases/$name")
            ?: throw IllegalStateException("案例资源缺失: cases/$name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

data class BootstrapReport(val projectId: String, val resultV1Id: String, val resultV2Id: String)

@kotlinx.serialization.Serializable
data class HistoricalView(
    val atEventId: String,
    val policyVersionAtPoint: String,
    val visibleResultIds: List<String>,
    val decisions: Map<String, String>,
    val materialRefs: List<String>,
    val events: List<LedgerEvent>
)
