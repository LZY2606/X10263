package heapfx.db

import heapfx.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite store. Two layers are kept strictly apart:
 *  - immutable raw material (raw_* tables, classes, incomplete_blocks)
 *  - mutable derived/decision layer (policies, computations, derived_*, events, decisions)
 */
class Database(val path: String) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().use { it.execute("PRAGMA journal_mode=WAL"); it.execute("PRAGMA foreign_keys=ON") }
    }

    fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS snapshots(
                snapshot_id TEXT PRIMARY KEY, name TEXT, parser_version TEXT,
                captured_at TEXT, source_hash TEXT, imported_seq INTEGER)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS classes(
                snapshot_id TEXT, class_id TEXT, name TEXT, super_name TEXT, class_loader TEXT,
                seq INTEGER, PRIMARY KEY(snapshot_id, class_id))""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS raw_objects(
                snapshot_id TEXT, object_key TEXT, raw_id TEXT, address_raw TEXT, class_id TEXT,
                size_raw TEXT, alloc_site TEXT, incomplete INTEGER, seq INTEGER,
                PRIMARY KEY(snapshot_id, object_key))""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS raw_edges(
                snapshot_id TEXT, from_key TEXT, to_key TEXT, kind TEXT, name TEXT, seq INTEGER,
                PRIMARY KEY(snapshot_id, seq))""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS raw_roots(
                snapshot_id TEXT, object_key TEXT, root_type TEXT, seq INTEGER,
                PRIMARY KEY(snapshot_id, seq))""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS incomplete_blocks(
                snapshot_id TEXT, block_id TEXT, reason TEXT, affected_json TEXT, seq INTEGER,
                PRIMARY KEY(snapshot_id, block_id))""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS policies(
                policy_version TEXT PRIMARY KEY, body_json TEXT, created_event TEXT)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS computations(
                compute_id TEXT PRIMARY KEY, snapshot_id TEXT, policy_version TEXT,
                input_digest TEXT, status TEXT, created_event TEXT, created_seq INTEGER)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS derived_objects(
                compute_id TEXT, object_key TEXT, reachable INTEGER, size_bytes INTEGER,
                retained_bytes INTEGER, dominator_parent TEXT, depth INTEGER,
                PRIMARY KEY(compute_id, object_key))""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS root_paths(
                compute_id TEXT, object_key TEXT, path_index INTEGER, nodes_json TEXT, root_type TEXT,
                PRIMARY KEY(compute_id, object_key, path_index))""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS events(
                event_id TEXT PRIMARY KEY, seq INTEGER, ts TEXT, kind TEXT,
                payload_json TEXT, view_json TEXT)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS decisions(
                decision_id TEXT PRIMARY KEY, event_id TEXT, target TEXT, verdict TEXT,
                rationale TEXT, adopted INTEGER, supersedes TEXT)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS diagnostics(
                owner_type TEXT, owner_id TEXT, severity TEXT, position TEXT, message TEXT, seq INTEGER)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS counterexamples(
                cx_id TEXT PRIMARY KEY, scenario TEXT, mutation_json TEXT, expected_scope_json TEXT,
                rationale_json TEXT, adopted_json TEXT, rejected_json TEXT, outcome TEXT)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS import_sessions(
                session_id TEXT PRIMARY KEY, state TEXT, snapshot_id TEXT, started_ts TEXT)""")
        }
    }

    fun <T> tx(block: () -> T): T {
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    /** Half-write recovery: any import session left in 'started' state is purged with its partial rows. */
    fun recoverIncompleteImports(): List<String> {
        val stale = mutableListOf<Pair<String, String?>>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT session_id, snapshot_id FROM import_sessions WHERE state='started'")
            while (rs.next()) stale += rs.getString(1) to rs.getString(2)
        }
        if (stale.isEmpty()) return emptyList()
        tx {
            for ((sessionId, snapId) in stale) {
                if (snapId != null) purgeSnapshot(snapId)
                conn.prepareStatement("DELETE FROM import_sessions WHERE session_id=?").use { ps ->
                    ps.setString(1, sessionId); ps.executeUpdate()
                }
            }
        }
        return stale.map { it.first }
    }

    fun purgeSnapshot(snapshotId: String) {
        for (t in listOf("snapshots", "classes", "raw_objects", "raw_edges", "raw_roots", "incomplete_blocks")) {
            conn.prepareStatement("DELETE FROM $t WHERE snapshot_id=?").use { ps ->
                ps.setString(1, snapshotId); ps.executeUpdate()
            }
        }
    }

    fun nextSeq(table: String, column: String): Long =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COALESCE(MAX($column),0)+1 FROM $table")
            rs.next(); rs.getLong(1)
        }

    fun recordEvent(kind: String, payload: JsonObject, view: JsonObject, eventId: String? = null, ts: String? = null): FxEvent {
        val id = eventId ?: "evt-" + nextSeq("events", "seq").toString().padStart(6, '0')
        val seq = nextSeq("events", "seq")
        val timestamp = ts ?: java.time.Instant.now().toString()
        conn.prepareStatement("INSERT INTO events(event_id,seq,ts,kind,payload_json,view_json) VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, id); ps.setLong(2, seq); ps.setString(3, timestamp); ps.setString(4, kind)
            ps.setString(5, payload.toString()); ps.setString(6, view.toString()); ps.executeUpdate()
        }
        return FxEvent(id, seq, timestamp, kind, payload, view)
    }

    fun events(): List<FxEvent> {
        val out = mutableListOf<FxEvent>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT event_id,seq,ts,kind,payload_json,view_json FROM events ORDER BY seq")
            while (rs.next()) out += FxEvent(
                rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                json.parseToJsonElement(rs.getString(5)) as JsonObject,
                json.parseToJsonElement(rs.getString(6)) as JsonObject)
        }
        return out
    }

    fun addDiagnostic(ownerType: String, ownerId: String, d: Diagnostic, seq: Int) {
        conn.prepareStatement("INSERT INTO diagnostics(owner_type,owner_id,severity,position,message,seq) VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, ownerType); ps.setString(2, ownerId); ps.setString(3, d.severity)
            ps.setString(4, d.position); ps.setString(5, d.message); ps.setInt(6, seq); ps.executeUpdate()
        }
    }

    fun diagnostics(ownerType: String, ownerId: String): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        conn.prepareStatement("SELECT severity,position,message FROM diagnostics WHERE owner_type=? AND owner_id=? ORDER BY seq").use { ps ->
            ps.setString(1, ownerType); ps.setString(2, ownerId)
            val rs = ps.executeQuery()
            while (rs.next()) out += Diagnostic(rs.getString(1), rs.getString(2), rs.getString(3))
        }
        return out
    }

    fun snapshotIds(): List<String> =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT snapshot_id FROM snapshots ORDER BY imported_seq")
            generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
        }

    fun isEmpty(): Boolean =
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT COUNT(*) FROM snapshots"); rs.next(); rs.getLong(1) == 0L
        }

    fun close() = conn.close()
}
