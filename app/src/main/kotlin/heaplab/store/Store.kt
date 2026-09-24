package heaplab.store

import heaplab.analysis.AnalysisOutcome
import heaplab.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

@Serializable
data class StoredEvent(
    val eventId: String,
    val seq: Long,
    val type: String,            // snapshot.imported | analysis.completed | params.revised | source.amended | decision.* | view.reverted
    val payload: String,         // JSON
    val inputDigest: String,
    val paramsVersion: String,
    val parentEventId: String?,  // dependency chain
    val status: String,          // pending | committed | aborted
    val checksum: String,
)

@Serializable
data class Decision(
    val decisionId: String,
    val analysisEventId: String,
    val targetObjectId: String,
    val verdict: String,         // ACCEPT | REJECT | ADJUST
    val rationale: String,
    val decidedAt: String,
)

@Serializable
data class ExportBundle(
    val formatVersion: String = "heaplab-export/v1",
    val snapshots: List<HeapSnapshot>,
    val events: List<StoredEvent>,
    val decisions: List<Decision>,
    val analyses: List<StoredAnalysis>,
)

@Serializable
data class StoredAnalysis(
    val analysisId: String,
    val snapshotId: String,
    val params: AnalysisParams,
    val inputDigest: String,
    val parentEventId: String?,
    val outcome: String,         // JSON of AnalysisOutcome
    val outcomeKind: String,     // Success | BudgetExhausted | CorruptFormat | SemanticUnresolvable
)

class Store(private val dbPath: String) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var conn: Connection = open()

    private fun open(): Connection {
        val c = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        c.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        migrate(c)
        recoverHalfWrites(c)
        return c
    }

    fun reopen() { conn.close(); conn = open() }

    private fun migrate(c: Connection) {
        c.createStatement().use { st ->
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshots(
                    snapshot_id TEXT PRIMARY KEY,
                    payload TEXT NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS events(
                    event_id TEXT PRIMARY KEY,
                    seq INTEGER NOT NULL,
                    type TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    input_digest TEXT NOT NULL,
                    params_version TEXT NOT NULL,
                    parent_event_id TEXT,
                    status TEXT NOT NULL,
                    checksum TEXT NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decisions(
                    decision_id TEXT PRIMARY KEY,
                    analysis_event_id TEXT NOT NULL,
                    target_object_id TEXT NOT NULL,
                    verdict TEXT NOT NULL,
                    rationale TEXT NOT NULL,
                    decided_at TEXT NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS analyses(
                    analysis_id TEXT PRIMARY KEY,
                    snapshot_id TEXT NOT NULL,
                    params TEXT NOT NULL,
                    input_digest TEXT NOT NULL,
                    parent_event_id TEXT,
                    outcome TEXT NOT NULL,
                    outcome_kind TEXT NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)""")
        }
    }

    /** Half-write recovery: any event left 'pending' (crash between begin and commit) is marked 'aborted'. */
    private fun recoverHalfWrites(c: Connection) {
        c.createStatement().use {
            it.executeUpdate("UPDATE events SET status='aborted' WHERE status='pending'")
        }
    }

    fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun snapshotDigest(s: HeapSnapshot): String = sha256(json.encodeToString(s))

    // ---- immutable materials ----
    fun putSnapshot(s: HeapSnapshot) {
        val ps = conn.prepareStatement("INSERT OR REPLACE INTO snapshots(snapshot_id, payload) VALUES(?,?)")
        ps.setString(1, s.snapshotId); ps.setString(2, json.encodeToString(s)); ps.executeUpdate(); ps.close()
    }

    fun getSnapshot(id: String): HeapSnapshot? {
        val ps = conn.prepareStatement("SELECT payload FROM snapshots WHERE snapshot_id=?")
        ps.setString(1, id)
        val rs = ps.executeQuery()
        val out = if (rs.next()) json.decodeFromString<HeapSnapshot>(rs.getString(1)) else null
        ps.close(); return out
    }

    fun listSnapshots(): List<HeapSnapshot> {
        val rs = conn.createStatement().executeQuery("SELECT payload FROM snapshots ORDER BY snapshot_id")
        val out = mutableListOf<HeapSnapshot>()
        while (rs.next()) out += json.decodeFromString<HeapSnapshot>(rs.getString(1))
        return out.sortedBy { it.snapshotId }
    }

    // ---- events: append-only, two-phase with checksum ----
    fun nextSeq(): Long {
        val rs = conn.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events")
        rs.next(); return rs.getLong(1)
    }

    fun appendEvent(type: String, payload: String, inputDigest: String, paramsVersion: String, parentEventId: String?): StoredEvent {
        val seq = nextSeq()
        val id = "evt-%06d".format(seq)
        val checksum = sha256("$id|$seq|$type|$payload|$inputDigest|$paramsVersion|${parentEventId ?: ""}")
        conn.autoCommit = false
        try {
            val ps = conn.prepareStatement(
                "INSERT INTO events(event_id,seq,type,payload,input_digest,params_version,parent_event_id,status,checksum) VALUES(?,?,?,?,?,?,?,'pending',?)")
            ps.setString(1, id); ps.setLong(2, seq); ps.setString(3, type); ps.setString(4, payload)
            ps.setString(5, inputDigest); ps.setString(6, paramsVersion)
            ps.setString(7, parentEventId); ps.setString(8, checksum)
            ps.executeUpdate(); ps.close()
            conn.commit()
            val ps2 = conn.prepareStatement("UPDATE events SET status='committed' WHERE event_id=?")
            ps2.setString(1, id); ps2.executeUpdate(); ps2.close()
            conn.commit()
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally { conn.autoCommit = true }
        return StoredEvent(id, seq, type, payload, inputDigest, paramsVersion, parentEventId, "committed", checksum)
    }

    fun listEvents(committedOnly: Boolean = true): List<StoredEvent> {
        val sql = if (committedOnly) "SELECT * FROM events WHERE status='committed' ORDER BY seq"
                  else "SELECT * FROM events ORDER BY seq"
        val rs = conn.createStatement().executeQuery(sql)
        val out = mutableListOf<StoredEvent>()
        while (rs.next()) out += StoredEvent(
            rs.getString("event_id"), rs.getLong("seq"), rs.getString("type"), rs.getString("payload"),
            rs.getString("input_digest"), rs.getString("params_version"), rs.getString("parent_event_id"),
            rs.getString("status"), rs.getString("checksum"))
        return out
    }

    fun getEvent(id: String): StoredEvent? = listEvents(false).firstOrNull { it.eventId == id }

    // ---- analyses ----
    fun putAnalysis(a: StoredAnalysis) {
        val ps = conn.prepareStatement(
            "INSERT OR REPLACE INTO analyses(analysis_id,snapshot_id,params,input_digest,parent_event_id,outcome,outcome_kind) VALUES(?,?,?,?,?,?,?)")
        ps.setString(1, a.analysisId); ps.setString(2, a.snapshotId); ps.setString(3, json.encodeToString(a.params))
        ps.setString(4, a.inputDigest); ps.setString(5, a.parentEventId); ps.setString(6, a.outcome); ps.setString(7, a.outcomeKind)
        ps.executeUpdate(); ps.close()
    }

    fun listAnalyses(): List<StoredAnalysis> {
        val rs = conn.createStatement().executeQuery("SELECT * FROM analyses ORDER BY analysis_id")
        val out = mutableListOf<StoredAnalysis>()
        while (rs.next()) out += StoredAnalysis(
            rs.getString("analysis_id"), rs.getString("snapshot_id"),
            json.decodeFromString(rs.getString("params")), rs.getString("input_digest"),
            rs.getString("parent_event_id"), rs.getString("outcome"), rs.getString("outcome_kind"))
        return out
    }

    fun getAnalysis(id: String): StoredAnalysis? = listAnalyses().firstOrNull { it.analysisId == id }

    // ---- decisions (mutable layer, separate from immutable materials) ----
    fun putDecision(d: Decision) {
        val ps = conn.prepareStatement(
            "INSERT OR REPLACE INTO decisions(decision_id,analysis_event_id,target_object_id,verdict,rationale,decided_at) VALUES(?,?,?,?,?,?)")
        ps.setString(1, d.decisionId); ps.setString(2, d.analysisEventId); ps.setString(3, d.targetObjectId)
        ps.setString(4, d.verdict); ps.setString(5, d.rationale); ps.setString(6, d.decidedAt)
        ps.executeUpdate(); ps.close()
    }

    fun listDecisions(): List<Decision> {
        val rs = conn.createStatement().executeQuery("SELECT * FROM decisions ORDER BY decision_id")
        val out = mutableListOf<Decision>()
        while (rs.next()) out += Decision(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
        return out
    }

    /** Conflicting human decisions: same analysis event + same object, different verdicts. */
    fun conflictingDecisions(): Map<Pair<String, String>, List<Decision>> =
        listDecisions().groupBy { it.analysisEventId to it.targetObjectId }
            .filter { (_, ds) -> ds.map { it.verdict }.toSet().size > 1 }

    // ---- current view pointer (revert restores historical dependency versions + visible conclusions) ----
    fun setCurrentEvent(eventId: String) {
        val ps = conn.prepareStatement("INSERT OR REPLACE INTO kv(k,v) VALUES('current_event',?)")
        ps.setString(1, eventId); ps.executeUpdate(); ps.close()
    }

    fun currentEventId(): String? {
        val rs = conn.createStatement().executeQuery("SELECT v FROM kv WHERE k='current_event'")
        return if (rs.next()) rs.getString(1) else null
    }

    // ---- export / import / reset ----
    fun export(): ExportBundle = ExportBundle(
        snapshots = listSnapshots(),
        events = listEvents(),
        decisions = listDecisions(),
        analyses = listAnalyses(),
    )

    fun import(bundle: ExportBundle) {
        conn.autoCommit = false
        try {
            bundle.snapshots.forEach { putSnapshot(it) }
            bundle.analyses.forEach { putAnalysis(it) }
            bundle.decisions.forEach { putDecision(it) }
            for (e in bundle.events.sortedBy { it.seq }) {
                val ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO events(event_id,seq,type,payload,input_digest,params_version,parent_event_id,status,checksum) VALUES(?,?,?,?,?,?,?,?,?)")
                ps.setString(1, e.eventId); ps.setLong(2, e.seq); ps.setString(3, e.type); ps.setString(4, e.payload)
                ps.setString(5, e.inputDigest); ps.setString(6, e.paramsVersion); ps.setString(7, e.parentEventId)
                ps.setString(8, e.status); ps.setString(9, e.checksum)
                ps.executeUpdate(); ps.close()
            }
            conn.commit()
        } catch (e: Exception) { conn.rollback(); throw e }
        finally { conn.autoCommit = true }
    }

    fun reset() {
        conn.createStatement().use { st ->
            st.executeUpdate("DELETE FROM events"); st.executeUpdate("DELETE FROM decisions")
            st.executeUpdate("DELETE FROM analyses"); st.executeUpdate("DELETE FROM snapshots")
            st.executeUpdate("DELETE FROM kv")
        }
    }

    fun close() { conn.close() }
}
