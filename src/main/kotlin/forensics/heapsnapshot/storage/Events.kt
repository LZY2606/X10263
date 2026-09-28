package forensics.heapsnapshot.storage

import forensics.heapsnapshot.domain.Diagnostic
import forensics.heapsnapshot.domain.ParsedSnapshot
import forensics.heapsnapshot.engine.AnalysisParams
import forensics.heapsnapshot.engine.AnalysisResult
import forensics.heapsnapshot.engine.ClassGroupDiff
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 结构化状态与可重放证据：每一个事件都是机器可读的判定记录，
 * 携带输入摘要、参数版本、父级依赖和判定依据（rationaleCode）。
 */
@Serializable
data class Rationale(
    val code: String,
    val explanation: String,
    val evidenceRefs: List<String> = emptyList(),
)

@Serializable
sealed class EventPayload {
    abstract val rationale: Rationale

    @Serializable
    data class SnapshotImported(
        val snapshotId: String,
        val rawFileName: String,
        val parseOutcome: String, // OK | SEMANTIC_AMBIGUITY | CORRUPT_FORMAT | BUDGET_EXHAUSTED
        val diagnostics: List<Diagnostic>,
        override val rationale: Rationale,
    ) : EventPayload()

    @Serializable
    data class ParamsRevised(
        val newVersion: Int,
        val params: AnalysisParams,
        override val rationale: Rationale,
    ) : EventPayload()

    @Serializable
    data class AnalysisComputed(
        val analysisEventId: String,
        val snapshotId: String,
        val paramsVersion: Int,
        val parentEventIds: List<String>,
        val inputDigest: JsonElement,
        override val rationale: Rationale,
    ) : EventPayload()

    @Serializable
    data class CandidateAdjudicated(
        val candidateId: String,
        val decision: String, // ACCEPTED | REJECTED | PARAM_ADJUST
        val overridesParamsVersion: Int?,
        override val rationale: Rationale,
    ) : EventPayload()

    @Serializable
    data class SourcePatched(
        val snapshotId: String,
        val changedLine: Int,
        val changedRawLine: String,
        val scopeKey: String,
        override val rationale: Rationale,
    ) : EventPayload()

    @Serializable
    data class DiffComputed(
        val beforeSnapshotId: String,
        val afterSnapshotId: String,
        val analysisEventId: String?,
        override val rationale: Rationale,
    ) : EventPayload()
}

@Serializable
data class StoredEvent(
    val eventId: String,
    val seq: Long,
    val parentEventId: String?,
    val branch: String,
    val at: String,
    val actor: String,
    val type: String,
    val payload: EventPayload,
)

@Serializable
data class StoredSnapshot(
    val snapshotId: String,
    val identity: String,
    val raw: String,
    val importSeq: Long,
)

@Serializable
data class CandidateDecisionRecord(
    val candidateId: String,
    val decision: String,
    val eventSeq: Long,
    val paramsVersion: Int?,
)

@Serializable
data class ProjectState(
    val seq: Long,
    val snapshotIds: List<String>,
    val currentParams: AnalysisParams,
    val latestAnalysisEventIdBySnapshot: Map<String, String>,
    val decisions: Map<String, CandidateDecisionRecord>,
    val branch: String,
)
