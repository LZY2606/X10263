package heaplab.event

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 机器可读事件。所有可见结论的变化都以事件表达，绝不拿最新结果覆盖历史。
 * 每个事件记录输入摘要、参数版本、父级依赖（parentRunId / parentEventSeq）。
 */
@Serializable
data class EventRecord(
    val seq: Int,
    val eventId: String,
    val type: String,                 // 见 EventType
    val epochMillis: Long,
    val snapshotId: String?,
    val runId: String?,
    val parentEventSeq: Int?,
    val parentRunId: String?,
    val parametersVersion: Int?,
    val inputContentHash: String?,
    val payload: JsonElement,
    val actor: String,                // "system" / "reviewer"
    val label: String
) {
    companion object {
        const val IMPORT = "SNAPSHOT_IMPORTED"
        const val PARAM_REVISION = "PARAMETERS_REVISED"
        const val SOURCE_PATCH = "SOURCE_PATCHED"
        const val DECISION = "DECISION_MADE"
        const val DECISION_CONFLICT = "DECISION_CONFLICT"
        const val BASELINE = "COUNTEREXAMPLE_BASELINE"
        const val COUNTEREXAMPLE = "COUNTEREXAMPLE_RUN"
        const val EXPORT = "ARCHIVE_EXPORTED"
        const val REIMPORT = "ARCHIVE_REIMPORTED"
        const val NOTE = "NOTE"
    }
}

/** 人工决定：接受 / 否定 / 参数调整。可在同一对象上冲突。 */
@Serializable
data class Decision(
    val decisionId: String,
    val targetObjId: String?,
    val candidateKey: String?,        // 泄漏候选/路径候选稳定键
    val verdict: String,             // ACCEPT / REJECT / ADJUST
    val rationale: String,
    val parameterOverride: JsonElement? = null
)

/** 某个事件序号（含）时刻的视图状态。 */
@Serializable
data class ViewState(
    val asOfSeq: Int,
    val activeSnapshotId: String?,
    val activeRunId: String?,
    val parametersVersion: Int,
    val runIdByParams: Map<Int, String>,
    val decisions: List<Decision>,
    val counterexampleResults: Map<String, String> // scenarioKey -> runId（该时刻可见）
)
