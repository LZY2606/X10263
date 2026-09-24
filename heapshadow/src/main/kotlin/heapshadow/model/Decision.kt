package heapshadow.model

import kotlinx.serialization.Serializable

@Serializable
data class Decision(
    val decisionId: String,
    val eventId: String,
    val branch: String,
    val targetObjectId: String? = null,
    val targetAnalysisId: String? = null,
    val verdict: String,
    val rationale: String,
    /** 与该决定冲突的既有决定 id（人工互相冲突的决定被保留，不互相覆盖）。 */
    val conflictsWith: List<String> = emptyList(),
    val createdAtEpochMillis: Long
)

@Serializable
data class EventRecord(
    val eventId: String,
    val seq: Int,
    val branch: String,
    val parentEventId: String?,
    val type: String,
    val atEpochMillis: Long,
    val payloadJson: String,
    val inputSha256: String? = null,
    val producedAnalysisId: String? = null,
    val supersedesAnalysisId: String? = null
)

@Serializable
data class TimelineView(
    val branches: List<BranchInfo>,
    val events: List<EventView>,
    val currentEventId: String
)

@Serializable
data class BranchInfo(val name: String, val rootEventId: String, val headEventId: String, val depth: Int)

@Serializable
data class EventView(
    val eventId: String,
    val seq: Int,
    val branch: String,
    val parentEventId: String?,
    val type: String,
    val atEpochMillis: Long,
    val payload: kotlinx.serialization.json.JsonObject,
    val analysisId: String? = null,
    val decisionId: String? = null
)
