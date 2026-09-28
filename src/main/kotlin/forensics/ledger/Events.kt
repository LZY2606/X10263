package forensics.ledger

import forensics.graph.AnalysisParams
import forensics.graph.AnalysisResult
import forensics.graph.ClassCorrespondence
import forensics.graph.SnapshotDiff
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import forensics.model.Diagnostic
import forensics.model.Snapshot

/**
 * 事件账本：append-only。每个事件是机器可读证据，携带稳定身份、输入摘要、
 * 参数版本与父级依赖。回到任意旧事件时投影出当时的依赖版本与可见结论。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(MaterialImported::class),
    JsonSubTypes.Type(ParamsRevised::class),
    JsonSubTypes.Type(DerivationComputed::class),
    JsonSubTypes.Type(DecisionRecorded::class),
    JsonSubTypes.Type(CounterexampleRecorded::class),
)
sealed interface CaseEvent {
    val eventId: String
    val caseId: String
    val seq: Int
    val timestampBasis: String
    val parentEventIds: List<String>
    val note: String?
}

data class MaterialImported(
    override val eventId: String,
    override val caseId: String,
    override val seq: Int,
    override val timestampBasis: String,
    override val parentEventIds: List<String>,
    override val note: String?,
    val snapshotId: String,
    val inputSummary: Map<String, Any?>,
    val diagnostics: List<Diagnostic>,
    val importOutcome: String,
) : CaseEvent

data class ParamsRevised(
    override val eventId: String,
    override val caseId: String,
    override val seq: Int,
    override val timestampBasis: String,
    override val parentEventIds: List<String>,
    override val note: String?,
    val paramVersion: Int,
    val weakKeepsAlive: Boolean,
    val internalKeepsAlive: Boolean,
    val leakRetainedMinBytes: Long,
    val classCorrespondence: List<Map<String, Any?>>,
) : CaseEvent

data class DerivationComputed(
    override val eventId: String,
    override val caseId: String,
    override val seq: Int,
    override val timestampBasis: String,
    override val parentEventIds: List<String>,
    override val note: String?,
    val analysisIds: List<String>,
    val diffId: String?,
    val paramVersion: Int,
    val inputSummaries: List<Map<String, Any?>>,
) : CaseEvent

/** 人工决定：接受/否定泄漏候选或参数调整。互相冲突的决定都保留，状态机按序投影。 */
data class DecisionRecorded(
    override val eventId: String,
    override val caseId: String,
    override val seq: Int,
    override val timestampBasis: String,
    override val parentEventIds: List<String>,
    override val note: String?,
    val targetStableId: String,
    val decision: String,   // ACCEPT / REJECT / PARAM_CHANGE
    val rationale: String,
    val basedOnEventId: String,
    val adopted: Boolean,
) : CaseEvent

/** 反例档案条目：保留被采纳与未采纳结果。 */
data class CounterexampleRecorded(
    override val eventId: String,
    override val caseId: String,
    override val seq: Int,
    override val timestampBasis: String,
    override val parentEventIds: List<String>,
    override val note: String?,
    val archiveId: String,
    val scenario: String,
    val inputFragment: Map<String, Any?>,
    val judgmentStatus: String,
    val affectedObjectIds: List<String>,
    val adoptedResultId: String?,
    val rejectedResultIds: List<String>,
    val rationale: String,
) : CaseEvent

data class CaseHeader(
    val caseId: String,
    val title: String,
    val createdAtBasis: String,
    val snapshotOrder: List<String>,
)
