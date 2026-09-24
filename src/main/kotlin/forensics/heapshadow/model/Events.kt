package forensics.heapshadow.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 事件溯源：材料与决定分层。
 *  - MATERIAL_IMPORTED / SOURCE_PATCHED 是不可变材料事件；
 *  - PARAMS_REVISED / DECISION 是可修改（以追加新版本方式）事件；
 *  - ANALYSIS / DIFF 是派生记录，只引用输入摘要+参数版本+父依赖，永不覆盖历史。
 */
enum class EventType {
    MATERIAL_IMPORTED,
    SOURCE_PATCHED,
    PARAMS_REVISED,
    DECISION,
    ANALYSIS,
    DIFF,
    PROBE_RECORDED,
    PROJECT_OPENED,
    RECOVERY_MARKER,
}

enum class DecisionVerdict { ACCEPTED, REJECTED, PARAM_ADJUSTED, CONFLICT }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class EventRecord(
    val eventId: String,
    val seq: Long,
    val branch: String,
    val type: EventType,
    /** 事件发生的时间基准（毫秒+原始基准说明）。 */
    val occurredAtMs: Long,
    val timeBasis: String?,
    val parentEventIds: List<String>,
    /** 依赖的参数版本。 */
    val parameterVersion: String?,
    /** 输入摘要哈希（材料或派生结果）。 */
    val inputDigest: String?,
    val payload: Map<String, Any?>,
    /** 可选的反序列化结果句柄（analysisId / diffId / archiveId）。 */
    val refId: String?,
    val note: String?,
)

data class BranchInfo(
    val branch: String,
    val parentBranch: String?,
    val forkFromEventId: String?,
    val createdAtMs: Long,
)

/**
 * 时间旅行视图：回到任意事件时，恢复当时的参数版本、材料版本与可见结论。
 */
data class TimelineView(
    val atEventId: String,
    val branch: String,
    val parameterVersion: String,
    val parameters: AnalysisParameters,
    val materialEventIds: List<String>,
    val visibleAnalysisIds: List<String>,
    val visibleDecisions: List<String>,
    val snapshotOrder: List<String>,
)
