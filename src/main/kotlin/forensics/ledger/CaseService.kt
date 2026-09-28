package forensics.ledger

import forensics.graph.*
import forensics.model.*
import forensics.parse.SnapshotParser
import forensics.store.Store
import java.time.Instant

data class ExportBundle(
    val bundleVersion: String,
    val exportedAtBasis: String,
    val header: CaseHeader,
    val snapshots: List<Snapshot>,
    val events: List<CaseEvent>,
    val results: List<Map<String, Any?>>,
)

class CaseService(val store: Store, private val parser: SnapshotParser = SnapshotParser()) {

    private val graphEngine = GraphEngine()
    private val diffEngine = DiffEngine()
    private fun now() = Instant.now().toString() + " [UTC]"
    private fun eid(prefix: String, content: String) =
        "$prefix-${forensics.graph.GraphEngine.sha1(content).take(12)}"

    // —— 阶段 1：初始导入，原始材料全部保留 ——
    fun createCase(caseId: String, title: String, sourceName: String, text: String): ImportOutcome {
        val outcome = parser.parse(sourceName, text)
        if (outcome !is ImportOutcome.Ok) return outcome
        val snap = outcome.snapshot
        val event = MaterialImported(
            eventId = eid("ev", "$caseId:import:${snap.snapshotId}:0"),
            caseId = caseId, seq = 0, timestampBasis = now(), parentEventIds = emptyList(),
            note = "初始导入：原始对象 id/地址/类元数据/强弱内部边/root 分类/不完整块/解析器版本均保留",
            snapshotId = snap.snapshotId,
            inputSummary = mapOf(
                "snapshotId" to snap.snapshotId, "parserVersion" to snap.parserVersion,
                "objects" to snap.objects.size, "edges" to snap.edges.size, "roots" to snap.roots.size,
                "incompleteChunks" to snap.incompleteChunks.size, "addressBasis" to snap.addressBasis,
                "sizeUnitBasis" to snap.sizeUnitBasis),
            diagnostics = outcome.warnings, importOutcome = "OK",
        )
        store.writeCase(CaseHeader(caseId, title, now(), listOf(snap.snapshotId)), snap, event)
        return outcome
    }

    fun importAdditionalSnapshot(caseId: String, sourceName: String, text: String): ImportOutcome {
        require(store.caseExists(caseId)) { "案例不存在: $caseId" }
        val outcome = parser.parse(sourceName, text)
        if (outcome !is ImportOutcome.Ok) return outcome
        val snap = outcome.snapshot
        val seq = store.events(caseId).size
        val event = MaterialImported(
            eventId = eid("ev", "$caseId:import:${snap.snapshotId}:$seq"),
            caseId = caseId, seq = seq, timestampBasis = now(),
            parentEventIds = store.events(caseId).lastOrNull()?.let { listOf(it.eventId) } ?: emptyList(),
            note = "第二快照导入（用于类群差异）",
            snapshotId = snap.snapshotId,
            inputSummary = mapOf("snapshotId" to snap.snapshotId, "parserVersion" to snap.parserVersion,
                "objects" to snap.objects.size, "edges" to snap.edges.size, "roots" to snap.roots.size),
            diagnostics = outcome.warnings, importOutcome = "OK",
        )
        store.appendSnapshot(caseId, snap, event)
        return outcome
    }

    fun reviseParams(caseId: String, weak: Boolean, internal: Boolean, leakMin: Long,
                     correspondence: List<ClassCorrespondence>, note: String?): ParamsRevised {
        val prev = latestParams(caseId)
        val pv = (prev?.paramVersion ?: 0) + 1
        val seq = store.events(caseId).size
        val ev = ParamsRevised(
            eventId = eid("ev", "$caseId:params:v$pv:$seq"), caseId = caseId, seq = seq,
            timestampBasis = now(),
            parentEventIds = store.events(caseId).lastOrNull()?.let { listOf(it.eventId) } ?: emptyList(),
            note = note, paramVersion = pv, weakKeepsAlive = weak, internalKeepsAlive = internal,
            leakRetainedMinBytes = leakMin,
            classCorrespondence = correspondence.map { mapOf("canonicalId" to it.canonicalId, "members" to it.members) },
        )
        store.appendEvent(ev)
        return ev
    }

    /** 阶段 2/3：在某个参数版本上重放派生（分析 + 差异）。结果按内容寻址、幂等、可被测试直接调用。 */
    fun compute(caseId: String, note: String? = null): DerivationComputed {
        val snaps = store.snapshots(caseId)
        require(snaps.isNotEmpty()) { "案例 $caseId 无材料" }
        val params = latestParams(caseId) ?: defaultParams(1)
        val analyses = snaps.map { graphEngine.analyze(caseId, it, params) }
        analyses.forEach { store.saveResult(caseId, it.analysisId, "ANALYSIS", it.snapshotId, params.paramVersion, it) }
        val diff: SnapshotDiff? = if (analyses.size >= 2)
            diffEngine.diff(caseId, snaps[0], snaps[1], analyses[0], analyses[1], params) else null
        diff?.let { store.saveResult(caseId, it.diffId, "DIFF", null, params.paramVersion, it) }
        val seq = store.events(caseId).size
        val ev = DerivationComputed(
            eventId = eid("ev", "$caseId:derive:${analyses.joinToString(",") { it.analysisId }}:$diff:$seq"),
            caseId = caseId, seq = seq, timestampBasis = now(),
            parentEventIds = store.events(caseId).mapNotNull { e ->
                when { e is MaterialImported -> e.eventId
                    e is ParamsRevised && e.paramVersion == params.paramVersion -> e.eventId
                    else -> null }
            },
            note = note, analysisIds = analyses.map { it.analysisId }, diffId = diff?.diffId,
            paramVersion = params.paramVersion, inputSummaries = analyses.map { it.inputSummary.let { s ->
                mapOf("snapshotId" to s.snapshotId, "objectCount" to s.objectCount, "edgeCount" to s.edgeCount,
                    "rootCount" to s.rootCount, "incompleteChunkCount" to s.incompleteChunkCount,
                    "parserVersion" to s.parserVersion, "materialSha" to s.materialSha) } },
        )
        store.appendEvent(ev)
        return ev
    }

    fun decide(caseId: String, targetStableId: String, decision: String, rationale: String,
               basedOnEventId: String, adopted: Boolean, note: String? = null): DecisionRecorded {
        val seq = store.events(caseId).size
        val ev = DecisionRecorded(
            eventId = eid("ev", "$caseId:decision:$targetStableId:$decision:$seq"), caseId = caseId, seq = seq,
            timestampBasis = now(),
            parentEventIds = listOf(basedOnEventId) + (store.events(caseId).lastOrNull()?.let { listOf(it.eventId) } ?: emptyList()),
            note = note, targetStableId = targetStableId, decision = decision, rationale = rationale,
            basedOnEventId = basedOnEventId, adopted = adopted,
        )
        store.appendEvent(ev)
        return ev
    }

    fun recordCounterexample(
        caseId: String, scenario: String, inputFragment: Map<String, Any?>, judgmentStatus: String,
        affectedObjectIds: List<String>, adoptedResultId: String?, rejectedResultIds: List<String>,
        rationale: String, note: String? = null,
    ): CounterexampleRecorded {
        val seq = store.events(caseId).size
        val archiveId = "cx-${GraphEngine.sha1("$caseId:$scenario:$affectedObjectIds").take(10)}"
        val ev = CounterexampleRecorded(
            eventId = eid("ev", "$caseId:cx:$archiveId:$seq"), caseId = caseId, seq = seq,
            timestampBasis = now(),
            parentEventIds = store.events(caseId).lastOrNull()?.let { listOf(it.eventId) } ?: emptyList(),
            note = note, archiveId = archiveId, scenario = scenario, inputFragment = inputFragment,
            judgmentStatus = judgmentStatus, affectedObjectIds = affectedObjectIds,
            adoptedResultId = adoptedResultId, rejectedResultIds = rejectedResultIds, rationale = rationale,
        )
        store.appendEvent(ev)
        return ev
    }

    fun defaultParams(version: Int) = AnalysisParams(version)

    fun latestParams(caseId: String): AnalysisParams? =
        store.events(caseId).filterIsInstance<ParamsRevised>().maxByOrNull { it.paramVersion }?.toParams()

    private fun ParamsRevised.toParams() = AnalysisParams(
        paramVersion = paramVersion, weakKeepsAlive = weakKeepsAlive, internalKeepsAlive = internalKeepsAlive,
        leakRetainedMinBytes = leakRetainedMinBytes,
        classCorrespondence = classCorrespondence.map {
            ClassCorrespondence(it["canonicalId"] as String,
                @Suppress("UNCHECKED_CAST") (it["members"] as Map<String, String>))
        })

    /** 时间线投影：回到 seq 时恢复当时材料 + 参数版本 + 可见派生/决定，绝不拿最新结果覆盖历史。 */
    fun projectAt(caseId: String, atSeqInclusive: Int): TimelineView {
        val all = store.events(caseId)
        val slice = all.filter { it.seq <= atSeqInclusive }
        val materials = slice.filterIsInstance<MaterialImported>().map { it.snapshotId }
        val snaps = store.snapshots(caseId).filter { it.snapshotId in materials }
        val params = slice.filterIsInstance<ParamsRevised>().maxByOrNull { it.seq }?.toParams() ?: defaultParams(1)
        val latestDerivation = slice.filterIsInstance<DerivationComputed>().maxByOrNull { it.seq }
        val analyses = latestDerivation?.analysisIds?.mapNotNull { id ->
            store.resultPayload(id)?.let { store.mapper.readValue(it, AnalysisResult::class.java) }
        }.orEmpty()
        val diff = latestDerivation?.diffId?.let { id ->
            store.resultPayload(id)?.let { store.mapper.readValue(it, SnapshotDiff::class.java) }
        }
        val decisions = slice.filterIsInstance<DecisionRecorded>()
        return TimelineView(
            caseId = caseId, atSeq = atSeqInclusive, totalEvents = all.size,
            snapshots = snaps, params = params, analyses = analyses, diff = diff,
            decisions = decisions, events = slice, counterexamples = slice.filterIsInstance<CounterexampleRecorded>(),
        )
    }

    fun export(caseId: String): ExportBundle {
        val results = mutableListOf<Map<String, Any?>>()
        store.events(caseId).filterIsInstance<DerivationComputed>().forEach { d ->
            (d.analysisIds + listOfNotNull(d.diffId)).forEach { rid ->
                val raw = store.resultPayload(rid) ?: return@forEach
                results.add(mapOf("resultId" to rid, "payload" to store.mapper.readTree(raw)))
            }
        }
        val header = store.listCases().first { it.caseId == caseId }
        return ExportBundle("heap-shadow-bundle/1", Instant.now().toString() + " [UTC]",
            header, store.snapshots(caseId), store.events(caseId), results)
    }

    /** 导入：先校验全部内容再整体写入；身份、分支（事件 id）与决定历史保持不变。 */
    fun importBundle(bundle: ExportBundle, targetStore: Store = store): String {
        require(bundle.bundleVersion == "heap-shadow-bundle/1") { "不支持的导出版本 ${bundle.bundleVersion}" }
        val cid = bundle.header.caseId
        bundle.snapshots.forEachIndexed { i, s ->
            val ev = bundle.events.filterIsInstance<MaterialImported>().first { it.snapshotId == s.snapshotId }
            if (i == 0) targetStore.writeCase(bundle.header, s, ev)
            else targetStore.appendSnapshot(cid, s, ev)
        }
        bundle.events.filter { it !is MaterialImported }.forEach { targetStore.appendEvent(it) }
        bundle.results.forEach { r ->
            val node = r["payload"] as com.fasterxml.jackson.databind.JsonNode
            val payload = node.toString()
            val tree = store.mapper.readTree(payload)
            val kind = if (tree.has("diffId")) "DIFF" else "ANALYSIS"
            val snapshotId = tree.path("snapshotId").asText(null)
            val pv = tree.path("paramVersion").asInt(1)
            targetStore.saveResult(cid, r["resultId"] as String, kind, snapshotId, pv,
                store.mapper.readValue(payload, Any::class.java))
        }
        return cid
    }
}

data class TimelineView(
    val caseId: String,
    val atSeq: Int,
    val totalEvents: Int,
    val snapshots: List<Snapshot>,
    val params: AnalysisParams,
    val analyses: List<AnalysisResult>,
    val diff: SnapshotDiff?,
    val decisions: List<DecisionRecorded>,
    val events: List<CaseEvent>,
    val counterexamples: List<CounterexampleRecorded>,
)
