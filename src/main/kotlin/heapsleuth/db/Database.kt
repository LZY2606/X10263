package heapsleuth.db

import heapsleuth.model.*
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

class Database(private val conn: Connection) : AutoCloseable {

    companion object {
        fun open(path: String): Database {
            val conn = DriverManager.getConnection("jdbc:sqlite:$path")
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA foreign_keys=ON")
            }
            return Database(conn).also { it.migrate() }
        }
        fun memory(): Database {
            val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
            return Database(conn).also { it.migrate() }
        }
    }

    private fun migrate() {
        conn.createStatement().use { st ->
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshots(
                    snapshot_id TEXT PRIMARY KEY,
                    parser_version TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    input_digest TEXT NOT NULL,
                    diagnostics_json TEXT NOT NULL,
                    raw_json TEXT NOT NULL
                )""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS classes(
                    snapshot_id TEXT NOT NULL, id TEXT NOT NULL, class_key TEXT NOT NULL,
                    name TEXT NOT NULL, super_name TEXT, instance_size_bytes INTEGER,
                    instance_size_raw TEXT, ord INTEGER NOT NULL,
                    PRIMARY KEY(snapshot_id, id))""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS objects(
                    snapshot_id TEXT NOT NULL, id TEXT NOT NULL, address TEXT NOT NULL,
                    class_id TEXT, size_bytes INTEGER, size_raw TEXT NOT NULL,
                    allocation_site TEXT, incomplete INTEGER NOT NULL, ord INTEGER NOT NULL,
                    PRIMARY KEY(snapshot_id, id))""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS edges(
                    snapshot_id TEXT NOT NULL, from_id TEXT NOT NULL, to_id TEXT NOT NULL,
                    kind TEXT NOT NULL, name TEXT, ord INTEGER NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS roots(
                    snapshot_id TEXT NOT NULL, object_id TEXT NOT NULL,
                    root_type TEXT NOT NULL, ord INTEGER NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS incomplete_blocks(
                    snapshot_id TEXT NOT NULL, offset TEXT NOT NULL, length_bytes INTEGER,
                    length_raw TEXT NOT NULL, reason TEXT NOT NULL, ord INTEGER NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS analyses(
                    analysis_id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL,
                    params_json TEXT NOT NULL, param_version TEXT NOT NULL,
                    input_digest TEXT NOT NULL, result_json TEXT NOT NULL,
                    judgments_json TEXT NOT NULL, stale_reason TEXT, created_at TEXT NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS events(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE NOT NULL,
                    type TEXT NOT NULL, payload_json TEXT NOT NULL, input_digest TEXT,
                    param_version TEXT, parents_json TEXT NOT NULL, created_at TEXT NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decisions(
                    decision_id TEXT PRIMARY KEY, target_id TEXT NOT NULL, verdict TEXT NOT NULL,
                    reviewer TEXT NOT NULL, rationale TEXT NOT NULL, event_seq INTEGER NOT NULL,
                    created_at TEXT NOT NULL)""")
        }
    }

    fun <T> tx(block: () -> T): T {
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    // ---------- snapshots ----------
    fun insertSnapshot(s: HeapSnapshot, diagnostics: List<Diagnostic>, rawJson: String, digest: String) = tx {
        conn.prepareStatement("INSERT INTO snapshots VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, s.snapshotId); ps.setString(2, s.parserVersion)
            ps.setString(3, Instant.now().toString()); ps.setString(4, digest)
            ps.setString(5, json.encodeToString(diagnostics)); ps.setString(6, rawJson)
            ps.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO classes VALUES(?,?,?,?,?,?,?)").use { ps ->
            s.classes.forEachIndexed { i, c ->
                ps.setString(1, s.snapshotId); ps.setString(2, c.id); ps.setString(3, c.classKey)
                ps.setString(4, c.name); ps.setString(5, c.superName)
                if (c.instanceSizeBytes != null) ps.setLong(6, c.instanceSizeBytes) else ps.setNull(6, java.sql.Types.INTEGER)
                ps.setString(7, c.instanceSizeRaw); ps.setInt(8, i); ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement("INSERT INTO objects VALUES(?,?,?,?,?,?,?,?,?)").use { ps ->
            s.objects.forEachIndexed { i, o ->
                ps.setString(1, s.snapshotId); ps.setString(2, o.id); ps.setString(3, o.address)
                ps.setString(4, o.classId)
                if (o.sizeBytes != null) ps.setLong(5, o.sizeBytes) else ps.setNull(5, java.sql.Types.INTEGER)
                ps.setString(6, o.sizeRaw); ps.setString(7, o.allocationSite)
                ps.setInt(8, if (o.incomplete) 1 else 0); ps.setInt(9, i); ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement("INSERT INTO edges VALUES(?,?,?,?,?,?)").use { ps ->
            s.edges.forEachIndexed { i, e ->
                ps.setString(1, s.snapshotId); ps.setString(2, e.from); ps.setString(3, e.to)
                ps.setString(4, e.kind.name); ps.setString(5, e.name); ps.setInt(6, i); ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement("INSERT INTO roots VALUES(?,?,?,?)").use { ps ->
            s.roots.forEachIndexed { i, r ->
                ps.setString(1, s.snapshotId); ps.setString(2, r.objectId)
                ps.setString(3, r.rootType.name); ps.setInt(4, i); ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement("INSERT INTO incomplete_blocks VALUES(?,?,?,?,?,?)").use { ps ->
            s.incompleteBlocks.forEachIndexed { i, b ->
                ps.setString(1, s.snapshotId); ps.setString(2, b.offset)
                if (b.lengthBytes != null) ps.setLong(3, b.lengthBytes) else ps.setNull(3, java.sql.Types.INTEGER)
                ps.setString(4, b.lengthRaw); ps.setString(5, b.reason); ps.setInt(6, i); ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun loadSnapshot(id: String): HeapSnapshot? {
        val meta = conn.prepareStatement("SELECT parser_version FROM snapshots WHERE snapshot_id=?").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (!rs.next()) return null
            rs.getString(1)
        }
        val classes = mutableListOf<ClassMeta>()
        conn.prepareStatement("SELECT id,class_key,name,super_name,instance_size_bytes,instance_size_raw FROM classes WHERE snapshot_id=? ORDER BY ord").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            while (rs.next()) classes += ClassMeta(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5)?.let { (it as Number).toLong() }, rs.getString(6))
        }
        val objects = mutableListOf<HeapObject>()
        conn.prepareStatement("SELECT id,address,class_id,size_bytes,size_raw,allocation_site,incomplete FROM objects WHERE snapshot_id=? ORDER BY ord").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            while (rs.next()) objects += HeapObject(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4)?.let { (it as Number).toLong() }, rs.getString(5), rs.getString(6), rs.getInt(7) == 1)
        }
        val edges = mutableListOf<HeapEdge>()
        conn.prepareStatement("SELECT from_id,to_id,kind,name FROM edges WHERE snapshot_id=? ORDER BY ord").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            while (rs.next()) edges += HeapEdge(rs.getString(1), rs.getString(2), EdgeKind.valueOf(rs.getString(3)), rs.getString(4))
        }
        val roots = mutableListOf<HeapRoot>()
        conn.prepareStatement("SELECT object_id,root_type FROM roots WHERE snapshot_id=? ORDER BY ord").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            while (rs.next()) roots += HeapRoot(rs.getString(1), RootType.valueOf(rs.getString(2)))
        }
        val blocks = mutableListOf<IncompleteBlock>()
        conn.prepareStatement("SELECT offset,length_bytes,length_raw,reason FROM incomplete_blocks WHERE snapshot_id=? ORDER BY ord").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            while (rs.next()) blocks += IncompleteBlock(rs.getString(1),
                rs.getObject(2)?.let { (it as Number).toLong() }, rs.getString(3), rs.getString(4))
        }
        return HeapSnapshot(id, meta, classes, objects, edges, roots, blocks)
    }

    data class SnapshotRow(val snapshotId: String, val parserVersion: String, val createdAt: String,
                           val inputDigest: String, val diagnostics: List<Diagnostic>)

    fun listSnapshots(): List<SnapshotRow> =
        conn.prepareStatement("SELECT snapshot_id,parser_version,created_at,input_digest,diagnostics_json FROM snapshots ORDER BY created_at, snapshot_id").use { ps ->
            val rs = ps.executeQuery()
            val out = mutableListOf<SnapshotRow>()
            while (rs.next()) out += SnapshotRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                json.decodeFromString(rs.getString(5)))
            out
        }

    fun snapshotDigest(id: String): String? =
        conn.prepareStatement("SELECT input_digest FROM snapshots WHERE snapshot_id=?").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) rs.getString(1) else null
        }

    // ---------- analyses ----------
    data class AnalysisRow(val analysisId: String, val snapshotId: String, val params: AnalysisParams,
                           val inputDigest: String, val resultJson: String, val judgments: List<String>,
                           val staleReason: String?, val createdAt: String)

    fun insertAnalysis(a: AnalysisRow) = tx {
        conn.prepareStatement("INSERT INTO analyses VALUES(?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, a.analysisId); ps.setString(2, a.snapshotId)
            ps.setString(3, json.encodeToString(a.params)); ps.setString(4, a.params.paramVersion)
            ps.setString(5, a.inputDigest); ps.setString(6, a.resultJson)
            ps.setString(7, json.encodeToString(a.judgments)); ps.setString(8, a.staleReason)
            ps.setString(9, a.createdAt); ps.executeUpdate()
        }
    }

    fun listAnalyses(): List<AnalysisRow> =
        conn.prepareStatement("SELECT analysis_id,snapshot_id,params_json,input_digest,result_json,judgments_json,stale_reason,created_at FROM analyses ORDER BY created_at, analysis_id").use { ps ->
            val rs = ps.executeQuery()
            val out = mutableListOf<AnalysisRow>()
            while (rs.next()) out += AnalysisRow(rs.getString(1), rs.getString(2),
                json.decodeFromString(rs.getString(3)), rs.getString(4), rs.getString(5),
                json.decodeFromString(rs.getString(6)), rs.getString(7), rs.getString(8))
            out
        }

    fun getAnalysis(id: String): AnalysisRow? =
        conn.prepareStatement("SELECT analysis_id,snapshot_id,params_json,input_digest,result_json,judgments_json,stale_reason,created_at FROM analyses WHERE analysis_id=?").use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (!rs.next()) return null
            AnalysisRow(rs.getString(1), rs.getString(2), json.decodeFromString(rs.getString(3)),
                rs.getString(4), rs.getString(5), json.decodeFromString(rs.getString(6)), rs.getString(7), rs.getString(8))
        }

    fun markStale(snapshotId: String, reason: String) = tx {
        conn.prepareStatement("UPDATE analyses SET stale_reason=? WHERE snapshot_id=? AND stale_reason IS NULL").use { ps ->
            ps.setString(1, reason); ps.setString(2, snapshotId); ps.executeUpdate()
        }
    }

    // ---------- events ----------
    data class EventRow(val seq: Long, val eventId: String, val type: String, val payloadJson: String,
                        val inputDigest: String?, val paramVersion: String?, val parents: List<String>,
                        val createdAt: String)

    fun insertEvent(type: String, eventId: String, payloadJson: String, inputDigest: String?,
                    paramVersion: String?, parents: List<String>, seq: Long? = null, createdAt: String? = null): Long = tx {
        val sql = if (seq == null)
            "INSERT INTO events(event_id,type,payload_json,input_digest,param_version,parents_json,created_at) VALUES(?,?,?,?,?,?,?)"
        else
            "INSERT INTO events(seq,event_id,type,payload_json,input_digest,param_version,parents_json,created_at) VALUES(?,?,?,?,?,?,?,?)"
        conn.prepareStatement(sql).use { ps ->
            var i = 1
            if (seq != null) ps.setLong(i++, seq)
            ps.setString(i++, eventId); ps.setString(i++, type); ps.setString(i++, payloadJson)
            ps.setString(i++, inputDigest); ps.setString(i++, paramVersion)
            ps.setString(i++, json.encodeToString(parents)); ps.setString(i, createdAt ?: Instant.now().toString())
            ps.executeUpdate()
        }
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT last_insert_rowid()")
            rs.next(); rs.getLong(1)
        }
    }

    fun listEvents(): List<EventRow> =
        conn.prepareStatement("SELECT seq,event_id,type,payload_json,input_digest,param_version,parents_json,created_at FROM events ORDER BY seq").use { ps ->
            val rs = ps.executeQuery()
            val out = mutableListOf<EventRow>()
            while (rs.next()) out += EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), json.decodeFromString(rs.getString(7)), rs.getString(8))
            out
        }

    fun getEvent(seq: Long): EventRow? =
        conn.prepareStatement("SELECT seq,event_id,type,payload_json,input_digest,param_version,parents_json,created_at FROM events WHERE seq=?").use { ps ->
            ps.setLong(1, seq)
            val rs = ps.executeQuery()
            if (!rs.next()) return null
            EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), json.decodeFromString(rs.getString(7)), rs.getString(8))
        }

    // ---------- decisions ----------
    data class DecisionRow(val decisionId: String, val targetId: String, val verdict: String,
                           val reviewer: String, val rationale: String, val eventSeq: Long, val createdAt: String)

    fun insertDecision(d: DecisionRow) = tx {
        conn.prepareStatement("INSERT INTO decisions VALUES(?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, d.decisionId); ps.setString(2, d.targetId); ps.setString(3, d.verdict)
            ps.setString(4, d.reviewer); ps.setString(5, d.rationale); ps.setLong(6, d.eventSeq)
            ps.setString(7, d.createdAt); ps.executeUpdate()
        }
    }

    fun listDecisions(): List<DecisionRow> =
        conn.prepareStatement("SELECT decision_id,target_id,verdict,reviewer,rationale,event_seq,created_at FROM decisions ORDER BY created_at, decision_id").use { ps ->
            val rs = ps.executeQuery()
            val out = mutableListOf<DecisionRow>()
            while (rs.next()) out += DecisionRow(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7))
            out
        }

    fun clearAll() = tx {
        conn.createStatement().use { st ->
            for (t in listOf("decisions","events","analyses","incomplete_blocks","roots","edges","objects","classes","snapshots"))
                st.executeUpdate("DELETE FROM $t")
        }
    }

    override fun close() { conn.close() }
}
