package heaplab.evidence

import heaplab.model.AnalysisParams
import heaplab.model.WeakPolicy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class EventActor { SYSTEM, REVIEWER }

/**
 * 机器可读取的取证事件。所有业务判断都落成结构化事件，而不是只写日志。
 * 关键判断依据直接编码进类型与 payload：
 *  - DOMINATORS_ON_REACHABLE_GRAPH
 *  - WEAK_REACHABILITY_POLICY
 *  - EQUAL_LENGTH_ROOT_PATHS_KEPT
 *  - ADDRESS_NOT_IDENTITY
 */
@Serializable
enum class EventType {
    SNAPSHOT_IMPORTED,          // payload: {snapshotId,label,sha256,parserVersion,dialect,...}
    PARAMS_REVISED,             // payload: AnalysisParams
    ANALYSIS_COMPUTED,          // payload: {resultId,snapshotId,inputSummary,parentDependencies}
    DECISION_ACCEPTED,          // payload: {targetKey,resultId,note}
    DECISION_REJECTED,          // payload: {targetKey,resultId,note}
    DECISION_PARAMS_ADJUSTED,   // payload: {targetKey,resultId,note,weakPolicy}
    SOURCE_AMENDED,             // payload: {newSnapshotId,replacesSnapshotId,scope,reason}
    CASE_NOTE
}

@Serializable
data class EvidenceEvent(
    val seq: Long,
    val eventId: String,
    val type: EventType,
    val actor: EventActor,
    val createdAtMs: Long,
    val paramsVersion: Int,
    val inputSha: String?,
    val parentEventIds: List<String>,
    val payload: JsonElement
) {
    fun ruleTag(): String? = when (type) {
        EventType.ANALYSIS_COMPUTED -> "DOMINATORS_ON_REACHABLE_GRAPH;WEAK_REACHABILITY_POLICY;EQUAL_LENGTH_ROOT_PATHS_KEPT;ADDRESS_NOT_IDENTITY"
        EventType.PARAMS_REVISED,
        EventType.DECISION_PARAMS_ADJUSTED -> "WEAK_REACHABILITY_POLICY"
        else -> null
    }
}

@Serializable
data class DecisionRecord(
    val eventId: String,
    val seq: Long,
    val targetKey: String,
    val verdict: String, // ACCEPTED | REJECTED | PARAMS_ADJUSTED
    val resultId: String,
    val note: String,
    val weakPolicyAtDecision: WeakPolicy?,
    val paramsVersion: Int,
    val createdAtMs: Long
)

/** 重放到某一事件序号后的投影状态。 */
data class ProjectedState(
    val upToSeq: Long,
    /** 快照材料：snapshotId -> 原始 HSPL 文本（不可变）。 */
    val rawBySnapshot: LinkedHashMap<String, String>,
    val labels: Map<String, String>,
    val params: AnalysisParams,
    /** 每次参数修订的历史（paramsVersion -> eventId）。 */
    val paramsHistory: List<Pair<Int, String>>,
    /** targetKey -> 该目标最新决定（旧决定仍留在事件流里）。 */
    val decisions: Map<String, DecisionRecord>,
    /** 新快照 -> 被其局部替换的旧快照。 */
    val amendments: Map<String, String>,
    val computedResults: Set<String>
)
