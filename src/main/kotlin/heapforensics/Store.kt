package heapforensics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

@Serializable
data class ExportBundle(
    val formatVersion: Int = 1,
    val snapshots: List<SnapshotData>,
    val params: List<Params>,
    val analyses: List<AnalysisResult>,
    val events: List<EventRec>,
    val decisions: List<DecisionRec>,
)

class Store(path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    init {
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS snapshots(
                     snapshot_id TEXT PRIMARY KEY, name TEXT, parser_version TEXT,
                     raw_text TEXT, data_json TEXT, ord INTEGER)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS params(
                     version INTEGER PRIMARY KEY, data_json TEXT, ord INTEGER)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS analyses(
                     analysis_id TEXT PRIMARY KEY, snapshot_id TEXT, params_version INTEGER,
                     status TEXT, result_json TEXT, ord INTEGER)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                     event_id TEXT PRIMARY KEY, seq INTEGER UNIQUE, kind TEXT,
                     payload_json TEXT, parent_ids TEXT, analysis_id TEXT, created_at TEXT)"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS decisions(
                     decision_id TEXT PRIMARY KEY, event_id TEXT, object_id TEXT,
                     verdict TEXT, note TEXT, ord INTEGER)"""
            )
        }
    }

    private fun nextOrd(table: String): Int =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COALESCE(MAX(ord),0)+1 FROM $table")
            rs.next(); rs.getInt(1)
        }

    fun nextSeq(): Int =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events")
            rs.next(); rs.getInt(1)
        }

    fun saveSnapshot(s: SnapshotData) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO snapshots VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, s.snapshotId); ps.setString(2, s.name)
            ps.setString(3, s.parserVersion); ps.setString(4, s.rawText)
            ps.setString(5, json.encodeToString(SnapshotData.serializer(), s))
            ps.setInt(6, nextOrd("snapshots")); ps.executeUpdate()
        }
    }

    fun getSnapshot(id: String): SnapshotData? =
        conn.prepareStatement("SELECT data_json FROM snapshots WHERE snapshot_id=?").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) json.decodeFromString(SnapshotData.serializer(), rs.getString(1)) else null
        }

    fun listSnapshots(): List<SnapshotData> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT data_json FROM snapshots ORDER BY ord")
            generateSequence { if (rs.next()) json.decodeFromString(SnapshotData.serializer(), rs.getString(1)) else null }.toList()
        }

    fun saveParams(p: Params) {
        conn.prepareStatement("INSERT OR REPLACE INTO params VALUES(?,?,?)").use { ps ->
            ps.setInt(1, p.version)
            ps.setString(2, json.encodeToString(Params.serializer(), p))
            ps.setInt(3, nextOrd("params")); ps.executeUpdate()
        }
    }

    fun listParams(): List<Params> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT data_json FROM params ORDER BY ord")
            generateSequence { if (rs.next()) json.decodeFromString(Params.serializer(), rs.getString(1)) else null }.toList()
        }

    fun latestParams(): Params? = listParams().lastOrNull()

    fun saveAnalysis(status: String, r: AnalysisResult) {
        conn.prepareStatement("INSERT OR REPLACE INTO analyses VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, r.analysisId); ps.setString(2, r.snapshotId)
            ps.setInt(3, r.paramsVersion); ps.setString(4, status)
            ps.setString(5, json.encodeToString(AnalysisResult.serializer(), r))
            ps.setInt(6, nextOrd("analyses")); ps.executeUpdate()
        }
    }

    fun recordFailedAnalysis(analysisId: String, snapshotId: String, paramsVersion: Int, status: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO analyses VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, analysisId); ps.setString(2, snapshotId)
            ps.setInt(3, paramsVersion); ps.setString(4, status)
            ps.setString(5, ""); ps.setInt(6, nextOrd("analyses")); ps.executeUpdate()
        }
    }

    fun getAnalysis(id: String): AnalysisResult? =
        conn.prepareStatement("SELECT result_json FROM analyses WHERE analysis_id=? AND status='ok'").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) json.decodeFromString(AnalysisResult.serializer(), rs.getString(1)) else null
        }

    fun latestAnalysis(snapshotId: String): AnalysisResult? =
        conn.prepareStatement(
            "SELECT result_json FROM analyses WHERE snapshot_id=? AND status='ok' ORDER BY ord DESC LIMIT 1").use { ps ->
            ps.setString(1, snapshotId)
            val rs = ps.executeQuery()
            if (rs.next()) json.decodeFromString(AnalysisResult.serializer(), rs.getString(1)) else null
        }

    fun addEvent(kind: String, payloadJson: String, parentIds: List<String>, analysisId: String? = null): EventRec {
        val e = EventRec("ev-" + UUID.randomUUID().toString().take(8), nextSeq(), kind,
            payloadJson, parentIds, analysisId, Instant.now().toString())
        conn.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, e.eventId); ps.setInt(2, e.seq); ps.setString(3, e.kind)
            ps.setString(4, e.payloadJson); ps.setString(5, parentIds.joinToString(","))
            ps.setString(6, e.analysisId); ps.setString(7, e.createdAt); ps.executeUpdate()
        }
        return e
    }

    fun listEvents(upToSeq: Int = Int.MAX_VALUE): List<EventRec> =
        conn.prepareStatement("SELECT * FROM events WHERE seq<=? ORDER BY seq").use { ps ->
            ps.setInt(1, upToSeq)
            val rs = ps.executeQuery()
            generateSequence {
                if (rs.next()) EventRec(rs.getString(1), rs.getInt(2), rs.getString(3),
                    rs.getString(4), rs.getString(5).split(",").filter { it.isNotBlank() },
                    rs.getString(6), rs.getString(7)) else null
            }.toList()
        }

    fun addDecision(objectId: String, verdict: String, note: String): Pair<DecisionRec, EventRec> {
        val ev = addEvent("decision",
            json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("objectId", kotlinx.serialization.json.JsonPrimitive(objectId))
                    put("verdict", kotlinx.serialization.json.JsonPrimitive(verdict))
                    put("note", kotlinx.serialization.json.JsonPrimitive(note))
                }),
            listEvents().lastOrNull()?.let { listOf(it.eventId) } ?: emptyList())
        val d = DecisionRec("dec-" + UUID.randomUUID().toString().take(8), ev.eventId, objectId, verdict, note)
        conn.prepareStatement("INSERT INTO decisions VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, d.decisionId); ps.setString(2, d.eventId); ps.setString(3, d.objectId)
            ps.setString(4, d.verdict); ps.setString(5, d.note)
            ps.setInt(6, nextOrd("decisions")); ps.executeUpdate()
        }
        return d to ev
    }

    fun listDecisions(): List<DecisionRec> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT * FROM decisions ORDER BY ord")
            generateSequence {
                if (rs.next()) DecisionRec(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5)) else null
            }.toList()
        }

    fun exportBundle(): ExportBundle = ExportBundle(
        snapshots = listSnapshots(),
        params = listParams(),
        analyses = conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT result_json FROM analyses WHERE status='ok' ORDER BY ord")
            generateSequence { if (rs.next()) json.decodeFromString(AnalysisResult.serializer(), rs.getString(1)) else null }.toList()
        },
        events = listEvents(),
        decisions = listDecisions(),
    )

    fun exportJson(): String = Json { prettyPrint = true }.encodeToString(ExportBundle.serializer(), exportBundle())

    /** Atomic import: any failure rolls back, leaving the DB untouched (half-write recovery). */
    fun importBundle(text: String): Outcome<ExportBundle> {
        val bundle: ExportBundle = try {
            json.decodeFromString(ExportBundle.serializer(), text)
        } catch (e: Exception) {
            return Outcome.CorruptFormat(listOf(Diagnostic("json", "error", "unparseable export: ${e.message}")))
        }
        if (bundle.formatVersion != 1)
            return Outcome.CorruptFormat(listOf(Diagnostic("formatVersion", "error",
                "unsupported export format ${bundle.formatVersion}")))
        conn.autoCommit = false
        try {
            bundle.snapshots.forEach { saveSnapshot(it) }
            bundle.params.forEach { saveParams(it) }
            bundle.analyses.forEach { saveAnalysis("ok", it) }
            bundle.events.forEach { e ->
                conn.prepareStatement("INSERT OR REPLACE INTO events VALUES(?,?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, e.eventId); ps.setInt(2, e.seq); ps.setString(3, e.kind)
                    ps.setString(4, e.payloadJson); ps.setString(5, e.parentIds.joinToString(","))
                    ps.setString(6, e.analysisId); ps.setString(7, e.createdAt); ps.executeUpdate()
                }
            }
            bundle.decisions.forEach { d ->
                conn.prepareStatement("INSERT OR REPLACE INTO decisions VALUES(?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, d.decisionId); ps.setString(2, d.eventId); ps.setString(3, d.objectId)
                    ps.setString(4, d.verdict); ps.setString(5, d.note)
                    ps.setInt(6, nextOrd("decisions")); ps.executeUpdate()
                }
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            return Outcome.CorruptFormat(listOf(Diagnostic("import", "error",
                "rolled back mid-import (half-write recovered): ${e.message}")))
        } finally {
            conn.autoCommit = true
        }
        return Outcome.Ok(bundle)
    }

    fun reset() {
        conn.createStatement().use { st ->
            listOf("snapshots", "params", "analyses", "events", "decisions").forEach {
                st.executeUpdate("DELETE FROM $it")
            }
        }
    }

    fun close() = conn.close()
}
