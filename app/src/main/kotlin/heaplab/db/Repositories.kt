@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package heaplab.db

import heaplab.analysis.AnalysisOutcome
import heaplab.analysis.AnalysisResult
import heaplab.analysis.Parameters
import heaplab.event.Decision
import heaplab.event.EventRecord
import heaplab.event.ViewState
import heaplab.model.RawSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.PreparedStatement

val appJson = Json {
    prettyPrint = false
    encodeDefaults = true
    classDiscriminator = "kind"
}

class SnapshotRepository(private val conn: Connection) {
    fun save(snap: RawSnapshot, origin: String, stagedLock: String? = null) {
        val hash = snap.objects.values.joinToString("|") { it.objId + it.address.raw }
            .let { md5(it) }
        conn.prepareStatement(
            """INSERT OR REPLACE INTO snapshot
              (snapshot_id,source_name,format_version,parser_version,imported_at,origin,raw_json,content_hash)
              VALUES (?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            ps.setString(1, snap.snapshotId); ps.setString(2, snap.sourceName)
            ps.setString(3, snap.formatVersion); ps.setString(4, snap.parserVersion)
            ps.setLong(5, snap.importedAtEpoch); ps.setString(6, origin)
            ps.setString(7, appJson.encodeToString(RawSnapshot.serializer(), snap))
            ps.setString(8, hash); ps.executeUpdate()
        }
    }

    fun find(id: String): RawSnapshot? =
        conn.prepareStatement("SELECT raw_json FROM snapshot WHERE snapshot_id=?").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) appJson.decodeFromString(RawSnapshot.serializer(), rs.getString(1)) else null
        }

    fun findByHash(hash: String): RawSnapshot? =
        conn.prepareStatement("SELECT raw_json FROM snapshot WHERE content_hash=? LIMIT 1").use { ps ->
            ps.setString(1, hash)
            val rs = ps.executeQuery()
            if (rs.next()) appJson.decodeFromString(RawSnapshot.serializer(), rs.getString(1)) else null
        }

    fun all(): List<RawSnapshot> =
        conn.createStatement().executeQuery(
            "SELECT raw_json FROM snapshot ORDER BY imported_at, snapshot_id"
        ).use { rs ->
            val out = mutableListOf<RawSnapshot>()
            while (rs.next()) out += appJson.decodeFromString(RawSnapshot.serializer(), rs.getString(1))
            out
        }
}

class RunRepository(private val conn: Connection) {
    fun save(runId: String, snapId: String, parentRunId: String?, params: Parameters,
             inputHash: String, outcome: AnalysisOutcome, createdAt: Long) {
        val (status, outcomeJson) = when (outcome) {
            is AnalysisOutcome.Ok -> "OK" to appJson.encodeToString(AnalysisOutcome.serializer(), outcome)
            is AnalysisOutcome.FormatCorrupt -> "FORMAT_CORRUPT" to
                appJson.encodeToString(AnalysisOutcome.serializer(), outcome)
            is AnalysisOutcome.SemanticUnresolved -> "SEMANTIC_UNRESOLVED" to
                appJson.encodeToString(AnalysisOutcome.serializer(), outcome)
            is AnalysisOutcome.BudgetExhausted -> "BUDGET_EXHAUSTED" to
                appJson.encodeToString(AnalysisOutcome.serializer(), outcome)
        }
        conn.prepareStatement(
            """INSERT OR REPLACE INTO analysis_run
              (run_id,snapshot_id,parent_run_id,parameters_version,parameters_json,input_hash,status,outcome_json,created_at)
              VALUES (?,?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            ps.setString(1, runId); ps.setString(2, snapId)
            if (parentRunId == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, parentRunId)
            ps.setInt(4, params.version)
            ps.setString(5, appJson.encodeToString(Parameters.serializer(), params))
            ps.setString(6, inputHash); ps.setString(7, status)
            ps.setString(8, outcomeJson); ps.setLong(9, createdAt)
            ps.executeUpdate()
        }
    }

    fun outcome(runId: String): Pair<String, AnalysisOutcome>? =
        conn.prepareStatement("SELECT status,outcome_json FROM analysis_run WHERE run_id=?").use { ps ->
            ps.setString(1, runId); val rs = ps.executeQuery()
            if (rs.next()) rs.getString(1) to
                appJson.decodeFromString(AnalysisOutcome.serializer(), rs.getString(2)) else null
        }

    fun resultOrNull(runId: String): AnalysisResult? =
        (outcome(runId)?.second as? AnalysisOutcome.Ok)?.result

    fun runsForSnapshot(snapId: String): List<Triple<String, Int, String>> =
        conn.prepareStatement(
            "SELECT run_id,parameters_version,status FROM analysis_run WHERE snapshot_id=? ORDER BY created_at"
        ).use { ps ->
            ps.setString(1, snapId); val rs = ps.executeQuery()
            val out = mutableListOf<Triple<String, Int, String>>()
            while (rs.next()) out += Triple(rs.getString(1), rs.getInt(2), rs.getString(3))
            out
        }
}

class EventRepository(private val conn: Connection) {

    fun append(e: EventRecord): Int {
        val existing = conn.prepareStatement("SELECT 1 FROM event WHERE event_id=?").use { ps ->
            ps.setString(1, e.eventId); ps.executeQuery().next()
        }
        if (existing) return seqOf(e.eventId)
        conn.prepareStatement(
            """INSERT INTO event
              (event_id,type,epoch_millis,snapshot_id,run_id,parent_event_seq,parent_run_id,
               parameters_version,input_content_hash,actor,label,payload_json)
              VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            ps.setString(1, e.eventId); ps.setString(2, e.type); ps.setLong(3, e.epochMillis)
            setNullable(ps, 4, e.snapshotId); setNullable(ps, 5, e.runId)
            setNullableInt(ps, 6, e.parentEventSeq); setNullable(ps, 7, e.parentRunId)
            setNullableInt(ps, 8, e.parametersVersion); setNullable(ps, 9, e.inputContentHash)
            ps.setString(10, e.actor); ps.setString(11, e.label)
            ps.setString(12, e.payload.toString()); ps.executeUpdate()
        }
        return seqOf(e.eventId)
    }

    fun seqOf(eventId: String): Int = conn.prepareStatement("SELECT seq FROM event WHERE event_id=?").use { ps ->
        ps.setString(1, eventId); val rs = ps.executeQuery(); rs.next(); rs.getInt(1)
    }

    fun all(): List<EventRecord> = conn.createStatement()
        .executeQuery("SELECT * FROM event ORDER BY seq").use { rs ->
            val out = mutableListOf<EventRecord>()
            while (rs.next()) out += mapEvent(rs)
            out
        }

    fun upTo(seq: Int): List<EventRecord> =
        conn.prepareStatement("SELECT * FROM event WHERE seq<=? ORDER BY seq").use { ps ->
            ps.setInt(1, seq); val rs = ps.executeQuery()
            val out = mutableListOf<EventRecord>()
            while (rs.next()) out += mapEvent(rs)
            out
        }

    private fun mapEvent(rs: java.sql.ResultSet): EventRecord = EventRecord(
        seq = rs.getInt("seq"),
        eventId = rs.getString("event_id"),
        type = rs.getString("type"),
        epochMillis = rs.getLong("epoch_millis"),
        snapshotId = rs.getString("snapshot_id"),
        runId = rs.getString("run_id"),
        parentEventSeq = rs.getObject("parent_event_seq")?.let { (it as Number).toInt() },
        parentRunId = rs.getString("parent_run_id"),
        parametersVersion = rs.getObject("parameters_version")?.let { (it as Number).toInt() },
        inputContentHash = rs.getString("input_content_hash"),
        payload = appJson.parseToJsonElement(rs.getString("payload_json")),
        actor = rs.getString("actor"),
        label = rs.getString("label")
    )

    /**
     * 重放到指定事件（含）：恢复当时的活动快照、参数版本 -> runId、决定、反例可见性。
     * 历史不被最新结果覆盖：每个 runId 的 outcome 在写入时已固化。
     */
    fun viewAsOf(seq: Int, activeSnapshot: String): ViewState {
        val events = upTo(seq)
        var paramsVersion = 1
        val runByParams = LinkedHashMap<Int, String>()
        val decisions = mutableListOf<Decision>()
        val cxRuns = LinkedHashMap<String, String>()
        var activeSnapshotId: String? = activeSnapshot
        var activeRunId: String? = null

        for (e in events) {
            when (e.type) {
                EventRecord.IMPORT -> {
                    activeSnapshotId = e.snapshotId
                    e.runId?.let { activeRunId = it; runByParams[e.parametersVersion ?: 1] = it }
                }
                EventRecord.COUNTEREXAMPLE -> {
                    val key = e.payload.jsonObject["scenarioKey"]?.let { (it as JsonPrimitive).content }
                    if (key != null && e.runId != null) cxRuns[key] = e.runId!!
                }
                EventRecord.BASELINE -> {
                    activeSnapshotId = e.snapshotId
                    e.runId?.let { runByParams[e.parametersVersion ?: 1] = it; activeRunId = it }
                }
                EventRecord.PARAM_REVISION -> {
                    paramsVersion = e.parametersVersion ?: paramsVersion
                    e.runId?.let { runByParams[paramsVersion] = it; activeRunId = it }
                }
                EventRecord.SOURCE_PATCHED -> {
                    activeSnapshotId = e.snapshotId
                    e.runId?.let { activeRunId = it; runByParams[paramsVersion] = it }
                }
                EventRecord.DECISION, EventRecord.DECISION_CONFLICT -> {
                    decisions += appJson.decodeFromJsonElement(Decision.serializer(),
                        e.payload.jsonObject["decision"]!!)
                }
            }
            if (e.snapshotId == activeSnapshotId && e.runId != null &&
                (e.type == EventRecord.IMPORT || e.type == EventRecord.PARAM_REVISION ||
                    e.type == EventRecord.SOURCE_PATCHED)) {
                activeRunId = e.runId
            }
        }
        return ViewState(seq, activeSnapshotId, activeRunId, paramsVersion, runByParams, decisions, cxRuns)
    }
}

class CounterexampleRepository(private val conn: Connection) {
    fun save(
        archiveId: String, scenarioKey: String, title: String, description: String,
        baselineSnapshotId: String?, variantSnapshotId: String?,
        expectedScopeJson: String, adoptedJson: String, rejectedJson: String, rationaleEvent: String
    ) {
        conn.prepareStatement(
            """INSERT OR REPLACE INTO counterexample
              (archive_id,scenario_key,title,description,baseline_snapshot_id,variant_snapshot_id,
               expected_scope_json,adopted_json,rejected_json,rationale_event)
              VALUES (?,?,?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            ps.setString(1, archiveId); ps.setString(2, scenarioKey)
            ps.setString(3, title); ps.setString(4, description)
            setNullable(ps, 5, baselineSnapshotId); setNullable(ps, 6, variantSnapshotId)
            ps.setString(7, expectedScopeJson); ps.setString(8, adoptedJson)
            ps.setString(9, rejectedJson); ps.setString(10, rationaleEvent)
            ps.executeUpdate()
        }
    }

    fun all(): List<CounterexampleRow> = conn.createStatement()
        .executeQuery("SELECT * FROM counterexample ORDER BY archive_id,scenario_key").use { rs ->
            val out = mutableListOf<CounterexampleRow>()
            while (rs.next()) out += CounterexampleRow(
                rs.getString("archive_id"), rs.getString("scenario_key"),
                rs.getString("title"), rs.getString("description"),
                rs.getString("baseline_snapshot_id"), rs.getString("variant_snapshot_id"),
                rs.getString("expected_scope_json"), rs.getString("adopted_json"),
                rs.getString("rejected_json"), rs.getString("rationale_event")
            )
            out
        }
}

data class CounterexampleRow(
    val archiveId: String, val scenarioKey: String, val title: String, val description: String,
    val baselineSnapshotId: String?, val variantSnapshotId: String?,
    val expectedScopeJson: String, val adoptedJson: String, val rejectedJson: String,
    val rationaleEvent: String
)

internal fun setNullable(ps: PreparedStatement, idx: Int, v: String?) {
    if (v == null) ps.setNull(idx, java.sql.Types.VARCHAR) else ps.setString(idx, v)
}
internal fun setNullableInt(ps: PreparedStatement, idx: Int, v: Int?) {
    if (v == null) ps.setNull(idx, java.sql.Types.INTEGER) else ps.setInt(idx, v)
}
internal fun md5(s: String): String {
    val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
    return d.joinToString("") { "%02x".format(it) }
}
