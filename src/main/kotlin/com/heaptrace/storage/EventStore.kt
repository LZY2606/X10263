package com.heaptrace.storage

import com.heaptrace.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant

@Serializable
data class SnapshotImportedPayload(
    val snapshotId: String,
    val label: String,
    val parserVersion: String,
    val inputDigest: String,
    val objectCount: Int,
    val classCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
    val incompleteBlockIds: List<String>,
    val diagnosticCodes: List<String>,
    val rawSource: String = "case-package",
    val derivedFrom: String? = null,
    val changeScope: List<String> = emptyList(),
    val status: String = "IMPORTED"
)

@Serializable
data class AnalysisComputedPayload(
    val analysisId: String,
    val snapshotId: String,
    val policy: AnalysisPolicy,
    val inputDigest: String,
    val dependencyEventIds: List<String>,
    val reachableCount: Int,
    val leakCandidateCount: Int,
    val status: String,
    val stage: String? = null
)

@Serializable
data class DecisionPayload(
    val decisionId: String,
    val decision: String,
    val target: String? = null,
    val params: JsonObject = JsonObject(emptyMap()),
    val reason: String,
    val reviewer: String,
    val supersedes: String? = null,
    val snapshotId: String
)

@Serializable
data class JudgmentPayload(
    val judgmentId: String,
    val scenarioId: String,
    val ruleId: String,
    val holds: Boolean,
    val adopted: Boolean,
    val expected: JsonObject,
    val observed: JsonObject,
    val evidence: List<String>,
    val snapshotId: String? = null
)

@Serializable
data class ScenarioReplayedPayload(
    val scenarioId: String,
    val branch: String,
    val inputs: List<String>,
    val status: String,
    val affectedObjects: List<String>,
    val judgmentEventId: String? = null
)

/**
 * 事件仓库：所有接受/否定/参数调整/源数据改动都是 append-only 事件。
 * digest 链覆盖父事件，回到任意 seq 重放即可恢复当时依赖版本与可见结论。
 */
class EventStore(private val db: HeapDatabase) {

    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun now(): String = Instant.now().toString()

    private fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun events(): List<Event> {
        val rows = db.conn.createStatement().executeQuery("SELECT seq,id,parent_id,kind,at,snapshot_id,payload_json,digest FROM events ORDER BY seq")
        val out = mutableListOf<Event>()
        rows.use {
            while (it.next()) {
                out += Event(
                    seq = it.getInt(1),
                    id = it.getString(2),
                    parentId = it.getString(3),
                    kind = it.getString(4),
                    at = it.getString(5),
                    snapshotId = it.getString(6),
                    payload = json.parseToJsonElement(it.getString(7)).jsonObject,
                    digest = it.getString(8)
                )
            }
        }
        return out
    }

    fun event(id: String): Event? = events().firstOrNull { it.id == id }

    fun lastEvent(): Event? = events().lastOrNull()

    fun append(kind: String, snapshotId: String?, payload: JsonObject, overrideId: String? = null): Event {
        val parent = lastEvent()
        val seq = (parent?.seq ?: 0) + 1
        val id = overrideId ?: "evt-${seq.toString().padStart(4, '0')}-${sha(kind + seq).take(6)}"
        val at = now()
        val payloadCanonical = Json.encodeToString(JsonObject.serializer(), payload)
        val digest = sha("$seq|$id|$kind|$at|$payloadCanonical|${parent?.digest ?: "root"}")
        db.conn.prepareStatement(
            "INSERT INTO events(seq,id,parent_id,kind,at,snapshot_id,payload_json,digest) VALUES(?,?,?,?,?,?,?,?)"
        ).use {
            it.setInt(1, seq); it.setString(2, id); it.setString(3, parent?.id)
            it.setString(4, kind); it.setString(5, at); it.setString(6, snapshotId)
            it.setString(7, payloadCanonical); it.setString(8, digest)
            it.executeUpdate()
        }
        db.conn.commit()
        return Event(seq, id, parent?.id, kind, at, snapshotId, payload, digest)
    }

    inline fun <reified T> appendTyped(kind: String, snapshotId: String?, payload: T, overrideId: String? = null): Event =
        append(kind, snapshotId, json.encodeToJsonElement(payload) as JsonObject, overrideId)

    /** 校验 digest 链；任一环被改写都能发现。 */
    fun verifyChain(): List<String> {
        val problems = mutableListOf<String>()
        var prevDigest = "root"
        var prevId: String? = null
        var expectedSeq = 1
        for (e in events()) {
            if (e.seq != expectedSeq) problems += "seq 在 ${e.id} 处不连续"
            val canonical = Json.encodeToString(JsonObject.serializer(), e.payload)
            val recomputed = sha("${e.seq}|${e.id}|${e.kind}|${e.at}|$canonical|$prevDigest")
            if (recomputed != e.digest) problems += "事件 ${e.id} digest 不匹配（材料被改写）"
            if (e.parentId != prevId) problems += "事件 ${e.id} 父链断裂"
            prevDigest = e.digest; prevId = e.id; expectedSeq++
        }
        return problems
    }
}
