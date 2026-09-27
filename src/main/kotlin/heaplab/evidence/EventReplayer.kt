package heaplab.evidence

import heaplab.model.AnalysisParams
import kotlinx.serialization.json.*

/**
 * 纯函数重放：把事件流投影到某个序号的状态。
 * 回到任意旧事件时恢复当时的参数版本与决定，而不会用最新计算覆盖历史。
 */
object EventReplayer {
    private val json = Json { ignoreUnknownKeys = true }

    fun replay(events: List<EvidenceEvent>, upToSeq: Long = Long.MAX_VALUE): ProjectedState {
        val raw = LinkedHashMap<String, String>()
        val labels = LinkedHashMap<String, String>()
        var params = AnalysisParams.INITIAL
        val paramsHistory = mutableListOf<Pair<Int, String>>()
        val decisions = LinkedHashMap<String, DecisionRecord>()
        val amendments = LinkedHashMap<String, String>()
        val computed = mutableSetOf<String>()

        for (e in events) {
            if (e.seq > upToSeq) break
            when (e.type) {
                EventType.SNAPSHOT_IMPORTED -> {
                    val sid = e.payload.jsonObject.getValue("snapshotId").jsonPrimitive.content
                    // 原始文本存在 store 中（导入事件只记录摘要）；若直接携带文本也接受。
                    e.payload.jsonObject["rawText"]?.jsonPrimitive?.contentOrNull()?.let { raw[sid] = it }
                    labels[sid] = e.payload.jsonObject["label"]?.jsonPrimitive?.contentOrNull() ?: sid
                }
                EventType.SOURCE_AMENDED -> {
                    val newSid = e.payload.jsonObject.getValue("newSnapshotId").jsonPrimitive.content
                    val oldSid = e.payload.jsonObject.getValue("replacesSnapshotId").jsonPrimitive.content
                    amendments[newSid] = oldSid
                    e.payload.jsonObject["rawText"]?.jsonPrimitive?.contentOrNull()?.let { raw[newSid] = it }
                    labels[newSid] = e.payload.jsonObject["label"]?.jsonPrimitive?.contentOrNull() ?: newSid
                }
                EventType.PARAMS_REVISED -> {
                    params = json.decodeFromJsonElement(AnalysisParams.serializer(), e.payload)
                    paramsHistory += params.version to e.eventId
                }
                EventType.DECISION_PARAMS_ADJUSTED -> {
                    val wp = e.payload.jsonObject["weakPolicy"]?.jsonPrimitive?.contentOrNull()
                    if (wp != null) params = params.copy(
                        version = e.payload.jsonObject["newParamsVersion"]?.jsonPrimitive?.intOrNull() ?: (params.version + 1),
                        weakPolicy = heaplab.model.WeakPolicy.valueOf(wp)
                    )
                    paramsHistory += params.version to e.eventId
                    recordDecision(decisions, e, "PARAMS_ADJUSTED")
                }
                EventType.DECISION_ACCEPTED -> recordDecision(decisions, e, "ACCEPTED")
                EventType.DECISION_REJECTED -> recordDecision(decisions, e, "REJECTED")
                EventType.ANALYSIS_COMPUTED ->
                    computed += e.payload.jsonObject.getValue("resultId").jsonPrimitive.content
                EventType.CASE_NOTE -> Unit
            }
        }
        return ProjectedState(upToSeq, raw, labels, params, paramsHistory, decisions, amendments, computed)
    }

    private fun recordDecision(
        into: MutableMap<String, DecisionRecord>,
        e: EvidenceEvent,
        verdict: String
    ) {
        val p = e.payload.jsonObject
        val target = p.getValue("targetKey").jsonPrimitive.content
        into[target] = DecisionRecord(
            eventId = e.eventId,
            seq = e.seq,
            targetKey = target,
            verdict = verdict,
            resultId = p["resultId"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            note = p["note"]?.jsonPrimitive?.contentOrNull().orEmpty(),
            weakPolicyAtDecision = p["weakPolicy"]?.jsonPrimitive?.contentOrNull()
                ?.let { heaplab.model.WeakPolicy.valueOf(it) },
            paramsVersion = e.paramsVersion,
            createdAtMs = e.createdAtMs
        )
    }
}
