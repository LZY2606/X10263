package forensics.store

import forensics.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

@Serializable
data class EventRef(val eventId: String, val seq: Int, val kind: String)

@Serializable
data class LedgerEvent(
    val eventId: String,
    val seq: Int,
    val branch: String,
    val kind: String,
    val status: String,
    val parentEventId: String?,
    val timestampUtc: String,
    val inputSummary: String,
    val paramVersion: String?,
    val deps: List<String>,
    val payload: String,
)

@Serializable
data class StoredSnapshot(
    val snapshotId: String,
    val parserVersion: String,
    val importEventId: String,
    val role: String,
    val sourceText: String,
    val snapshot: HeapSnapshot,
)

class Store private constructor(private val conn: Connection) {

    val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        fun open(path: String): Store {
            Class.forName("org.sqlite.JDBC")
            val props = Properties().apply { put("foreign_keys", "true") }
            val c = if (path == ":memory:") DriverManager.getConnection("jdbc:sqlite::memory:", props)
            else DriverManager.getConnection("jdbc:sqlite:$path", props)
            c.autoCommit = false
            val s = Store(c)
            s.migrate()
            s.recoverHalfWrites()
            return s
        }
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS materials(
                snapshot_id TEXT PRIMARY KEY, parser_version TEXT, import_event_id TEXT, role TEXT, source_text TEXT)""")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS event_seq(n INTEGER)")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS events(
                event_id TEXT PRIMARY KEY, seq INTEGER UNIQUE, branch TEXT, kind TEXT, status TEXT,
                parent_event_id TEXT, ts TEXT, input_summary TEXT, param_version TEXT, deps TEXT, payload TEXT)""")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS analyses(
                analysis_id TEXT PRIMARY KEY, event_id TEXT, snapshot_id TEXT, policy_version TEXT, result_json TEXT)""")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS diffs(
                diff_id TEXT PRIMARY KEY, event_id TEXT, snap_a TEXT, snap_b TEXT, result_json TEXT)""")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS ingest_journal(import_id TEXT PRIMARY KEY, stage TEXT, detail TEXT)")
            st.executeUpdate("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT)")
            st.executeUpdate("SELECT COUNT(*) FROM event_seq").let { }
        }
        commit()
    }

    fun commit() = conn.commit()
    fun rollback() = conn.rollback()

    // ---------- half-write recovery ----------

    private fun recoverHalfWrites() {
        val rows = conn.createStatement().executeQuery("SELECT import_id, stage, detail FROM ingest_journal").use { q ->
            buildList { while (q.next()) add(Triple(q.getString(1), q.getString(2), q.getString(3))) }
        }
        rows.forEach { (id, stage, detail) ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO events(event_id,seq,branch,kind,status,parent_event_id,ts,input_summary,param_version,deps,payload) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { ps ->
                val seq = nextSeqRaw()
                ps.setString(1, "evt-recover-${"%04d".format(seq)}")
                ps.setInt(2, seq)
                ps.setString(3, "main")
                ps.setString(4, "INGEST_ABORTED_RECOVERED")
                ps.setString(5, "recovered")
                ps.setString(6, null)
                ps.setString(7, java.time.Instant.now().toString())
                ps.setString(8, "half-written import '$id' found at stage '$stage'")
                ps.setString(9, null)
                ps.setString(10, "[]")
                ps.setString(11, json.encodeToString(kotlinx.serialization.serializer(),
                    mapOf("importId" to id, "stage" to stage, "detail" to detail)))
                ps.executeUpdate()
            }
            conn.createStatement().use { it.executeUpdate("DELETE FROM ingest_journal WHERE import_id='$id'") }
        }
        commit()
    }

    private fun nextSeqRaw(): Int {
        val v = conn.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0) FROM events").use {
            if (it.next()) it.getInt(1) else 0
        }
        return v + 1
    }

    // ---------- events ----------

    @Synchronized
    fun appendEvent(
        kind: String, status: String, branch: String, inputSummary: String,
        paramVersion: String?, deps: List<String>, payload: Any, parentEventId: String? = null,
    ): LedgerEvent {
        val seq = nextSeqRaw()
        val ev = LedgerEvent(
            "evt-${"%04d".format(seq)}", seq, branch, kind, status, parentEventId,
            java.time.Instant.now().toString(), inputSummary, paramVersion, deps,
            json.encodeToString(kotlinx.serialization.serializer(), payload),
        )
        conn.prepareStatement(
            "INSERT INTO events VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, ev.eventId); ps.setInt(2, ev.seq); ps.setString(3, ev.branch)
            ps.setString(4, ev.kind); ps.setString(5, ev.status); ps.setString(6, ev.parentEventId)
            ps.setString(7, ev.timestampUtc); ps.setString(8, ev.inputSummary)
            ps.setString(9, ev.paramVersion); ps.setString(10, json.encodeToString(
                kotlinx.serialization.serializer<List<String>>(), ev.deps))
            ps.setString(11, ev.payload); ps.executeUpdate()
        }
        commit()
        return ev
    }

    fun eventsUpTo(seq: Int? = null): List<LedgerEvent> =
        conn.createStatement().executeQuery(
            "SELECT * FROM events${if (seq == null) "" else " WHERE seq<=$seq"} ORDER BY seq").use { q ->
            buildList { while (q.next()) add(LedgerEvent(
                q.getString("event_id"), q.getInt("seq"), q.getString("branch"), q.getString("kind"),
                q.getString("status"), q.getString("parent_event_id"), q.getString("ts"),
                q.getString("input_summary"), q.getString("param_version"),
                json.decodeFromString(q.getString("deps")), q.getString("payload"))) }
        }

    fun lastEvent(): LedgerEvent? = eventsUpTo().lastOrNull()

    inline fun <reified T> decode(payload: String): T = json.decodeFromString(payload)

    // ---------- materials ----------

    fun putMaterial(stored: StoredSnapshot) {
        conn.prepareStatement("INSERT OR REPLACE INTO materials VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, stored.snapshotId); ps.setString(2, stored.parserVersion)
            ps.setString(3, stored.importEventId); ps.setString(4, stored.role); ps.setString(5, stored.sourceText)
            ps.executeUpdate()
        }
        commit()
    }

    fun materials(): List<StoredSnapshot> =
        conn.createStatement().executeQuery(
            "SELECT snapshot_id,parser_version,import_event_id,role,source_text FROM materials ORDER BY snapshot_id").use { q ->
            buildList {
                while (q.next()) {
                    val id = q.getString(1)
                    add(StoredSnapshot(id, q.getString(2), q.getString(3), q.getString(4), q.getString(5),
                        SnapshotCodec.decode(q.getString(5))))
                }
            }
        }

    fun material(id: String): StoredSnapshot? = materials().firstOrNull { it.snapshotId == id }

    fun saveAnalysis(analysisId: String, eventId: String, snapshotId: String, policyVersion: String, resultJson: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO analyses VALUES(?,?,?,?,?)").use {
            it.setString(1, analysisId); it.setString(2, eventId); it.setString(3, snapshotId)
            it.setString(4, policyVersion); it.setString(5, resultJson); it.executeUpdate()
        }; commit()
    }

    fun analyses(): List<Triple<String, String, String>> =
        conn.createStatement().executeQuery("SELECT analysis_id,event_id,result_json FROM analyses ORDER BY analysis_id").use { q ->
            buildList { while (q.next()) add(Triple(q.getString(1), q.getString(2), q.getString(3))) }
        }

    fun saveDiff(diffId: String, eventId: String, a: String, b: String, resultJson: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO diffs VALUES(?,?,?,?,?)").use {
            it.setString(1, diffId); it.setString(2, eventId); it.setString(3, a)
            it.setString(4, b); it.setString(5, resultJson); it.executeUpdate()
        }; commit()
    }

    fun diffs(): List<Pair<String, String>> =
        conn.createStatement().executeQuery("SELECT diff_id,result_json FROM diffs ORDER BY diff_id").use { q ->
            buildList { while (q.next()) add(q.getString(1) to q.getString(2)) }
        }

    // ---------- staged import with half-write simulation ----------

    fun journalStage(importId: String, stage: String, detail: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO ingest_journal VALUES(?,?,?)").use {
            it.setString(1, importId); it.setString(2, stage); it.setString(3, detail); it.executeUpdate()
        }; commit()
    }

    fun clearJournal(importId: String) {
        conn.createStatement().use { it.executeUpdate("DELETE FROM ingest_journal WHERE import_id='$importId'") }; commit()
    }

    fun journalRows(): List<Triple<String, String, String>> =
        conn.createStatement().executeQuery("SELECT import_id,stage,detail FROM ingest_journal").use { q ->
            buildList { while (q.next()) add(Triple(q.getString(1), q.getString(2), q.getString(3))) }
        }

    fun setKv(k: String, v: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO kv VALUES(?,?)").use {
            it.setString(1, k); it.setString(2, v); it.executeUpdate() }; commit()
    }
    fun kv(k: String): String? =
        conn.prepareStatement("SELECT v FROM kv WHERE k=?").use { it.setString(1, k); it.executeQuery() }
            .takeIf { it.next() }?.getString(1)

    fun close() = conn.close()
}

object SnapshotCodec {
    val json = Json { ignoreUnknownKeys = true }
    fun decode(text: String): HeapSnapshot = json.decodeFromString(text)
}
