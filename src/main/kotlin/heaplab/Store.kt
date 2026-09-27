package heaplab

import kotlinx.serialization.encodeToString
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite storage. Two layers:
 *  - immutable material: snapshots/classes/objects/edges/roots (insert-only, identity preserved)
 *  - mutable derived state: params, computations, decisions, events (append/versioned)
 */
class Store(val dbPath: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").apply {
        createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }

    init {
        conn.createStatement().use { st ->
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS snapshots(
                snapshot_id TEXT PRIMARY KEY, parser_version TEXT NOT NULL,
                captured_at TEXT NOT NULL, digest TEXT NOT NULL, raw_json TEXT NOT NULL)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS diagnostics(
                id INTEGER PRIMARY KEY AUTOINCREMENT, snapshot_id TEXT NOT NULL,
                position TEXT NOT NULL, severity TEXT NOT NULL, message TEXT NOT NULL)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS params(
                version INTEGER PRIMARY KEY, weak_policy TEXT NOT NULL,
                max_objects INTEGER NOT NULL, max_paths INTEGER NOT NULL)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS computations(
                computation_id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL,
                param_version INTEGER NOT NULL, input_digest TEXT NOT NULL,
                parents TEXT NOT NULL, status TEXT NOT NULL, invalid_reason TEXT,
                result_json TEXT NOT NULL, created_seq INTEGER NOT NULL)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS decisions(
                decision_id TEXT PRIMARY KEY, subject TEXT NOT NULL, status TEXT NOT NULL,
                rationale TEXT NOT NULL, param_version INTEGER NOT NULL,
                supersedes TEXT, created_seq INTEGER NOT NULL)""")
            st.executeUpdate("""
              CREATE TABLE IF NOT EXISTS events(
                seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, payload TEXT NOT NULL)""")
        }
    }

    fun <T> tx(block: () -> T): T {
        conn.autoCommit = false
        try {
            val r = block(); conn.commit(); return r
        } catch (e: Exception) { conn.rollback(); throw e }
        finally { conn.autoCommit = true }
    }

    fun event(kind: String, payload: String) {
        conn.prepareStatement("INSERT INTO events(kind,payload) VALUES(?,?)").use {
            it.setString(1, kind); it.setString(2, payload); it.executeUpdate()
        }
    }

    fun events(): List<Event> = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT seq,kind,payload FROM events ORDER BY seq")
        generateSequence { if (rs.next()) Event(rs.getLong(1), rs.getString(2), rs.getString(3)) else null }.toList()
    }

    /** Import immutable material. Same id + same digest => idempotent. Same id + different digest => conflict. */
    fun importSnapshot(s: HeapSnapshot): OpResult<List<Diagnostic>> {
        val diags = Engine.validate(s)
        if (diags.any { it.severity == Severity.ERROR })
            return OpResult.Failed(FailureFailure.CompanionKindCorrupt(), diags, "snapshot failed validation")
        val digest = Engine.digestOf(s)
        val existing = getSnapshot(s.snapshotId)
        if (existing != null) {
            return if (Engine.digestOf(existing) == digest) {
                event("snapshot.reimport.identical", json.encodeToString(mapOf("snapshotId" toString" to s.snapshotId)))
                OpResult.Ok(diags)
            } else OpResult.Failed(FailureKind.SEMANTIC_UNSOLVABLE, diags,
                "snapshot id '${s.snapshotId}' already exists with different content; identity conflict")
        }
        if (s.objects.size > currentParams().maxObjects)
            return OpResult.Failed(FailureKind.BUDGET_EXHAUSTED, diags,
                "object count ${s.objects.size} exceeds budget ${currentParams().maxObjects}")
        tx {
            conn.prepareStatement(
                "INSERT INTO snapshots(snapshot_id,parser_version,captured_at,digest,raw_json) VALUES(?,?,?,?,?)").use {
                it.setString(1, s.snapshotId); it.setString(2, s.parserVersion); it.setString(3, s.capturedAt)
                it.setString(4, digest); it.setString(5, json.encodeToString(s)); it.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO diagnostics(snapshot_id,position,severity,message) VALUES(?,?,?,?)").use { ps ->
                for (d in diags) {
                    ps.setString(1, s.snapshotId); ps.setString(2, d.position)
                    ps.setString(3, d.severity.name); ps.setString(4, d.message); ps.addBatch()
                }
                ps.executeBatch()
            }
            event("snapshot.imported", json.encodeToString(mapOf(
                "snapshotId" to s.snapshotId, "digest" to digest, "parserVersion" to s.parserVersion,
                "objects" to s.objects.size.toString(), "diagnostics" to diags.size.toString())))
        }
        return OpResult.Ok(diags)
    }

    fun getSnapshot(id: String): HeapSnapshot? =
        conn.prepareStatement("SELECT raw_json FROM snapshots WHERE snapshot_id=?").use {
            it.setString(1, id)
            val rs = it.executeQuery()
            if (rs.next()) json.decodeFromString<HeapSnapshot>(rs.getString(1)) else null
        }

    fun listSnapshots(): List<HeapSnapshot> = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT raw_json FROM snapshots ORDER BY snapshot_id")
        generateSequence { if (rs.next()) json.decodeFromString<HeapSnapshot>(rs.getString(1)) else null }.toList()
    }

    fun diagnosticsFor(snapshotId: String): List<Diagnostic> =
        conn.prepareStatement("SELECT position,severity,message FROM diagnostics WHERE snapshot_id=? ORDER BY id").use {
            it.setString(1, snapshotId); val rs = it.executeQuery()
            generateSequence {
                if (rs.next()) Diagnostic(rs.getString(1), Severity.valueOf(rs.getString(2)), rs.getString(3)) else null
            }.toList()
        }

    // ----- params (versioned) -----
    fun currentParams(): ParamSet = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT version,weak_policy,max_objects,max_paths FROM params ORDER BY version DESC LIMIT 1")
        if (rs.next()) ParamSet(rs.getInt(1), WeakPolicy.valueOf(rs.getString(2)), rs.getInt(3), rs.getInt(4))
        else ParamSet(1, WeakPolicy.WEAK_NOT_ALIVE)
    }

    fun setParams(p: ParamSet) {
        tx {
            conn.prepareStatement("INSERT OR REPLACE INTO params(version,weak_policy,max_objects,max_paths) VALUES(?,?,?,?)").use {
                it.setInt(1, p.version); it.setString(2, p.weakPolicy.name)
                it.setInt(3, p.maxObjects); it.setInt(4, p.maxPaths); it.executeUpdate()
            }
            // invalidate computations made with other param versions
            conn.prepareStatement(
                "UPDATE computations SET status='INVALID', invalid_reason=? WHERE param_version<>? AND status='VALID'").use {
                it.setString(1, "param version moved to ${p.version}; dependency invalidated")
                it.setInt(2, p.version); it.executeUpdate()
            }
            event("params.revised", json.encodeToString(mapOf(
                "version" to p.version.toString(), "weakPolicy" to p.weakPolicy.name)))
        }
    }

    // ----- computations -----
    fun saveComputation(r: ComputationResult, status: String = "VALID") {
        tx {
            conn.prepareStatement(
                "INSERT OR REPLACE INTO computations(computation_id,snapshot_id,param_version,input_digest,parents,status,invalid_reason,result_json,created_seq) VALUES(?,?,?,?,?,?,?,?,?)").use {
                it.setString(1, r.computationId); it.setString(2, r.snapshotId); it.setInt(3, r.paramVersion)
                it.setString(4, r.inputDigest); it.setString(5, r.parentComputationIds.joinToString(","))
                it.setString(6, status); it.setNull(7, java.sql.Types.VARCHAR)
                it.setString(8, json.encodeToString(r))
                it.setLong(9, nextSeq()); it.executeUpdate()
            }
            event("computation.saved", json.encodeToString(mapOf(
                "computationId" to r.computationId, "snapshotId" to r.snapshotId,
                "paramVersion" to r.paramVersion.toString(), "inputDigest" to r.inputDigest)))
        }
    }

    fun getComputation(id: String): Pair<ComputationResult, String>? =
        conn.prepareStatement("SELECT result_json,status FROM computations WHERE computation_id=?").use {
            it.setString(1, id); val rs = it.executeQuery()
            if (rs.next()) json.decodeFromString<ComputationResult>(rs.getString(1)) to rs.getString(2) else null
        }

    fun listComputations(): List<Triple<ComputationResult, String, String?>> = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT result_json,status,invalid_reason FROM computations ORDER BY created_seq")
        generateSequence {
            if (rs.next()) Triple(json.decodeFromString<ComputationResult>(rs.getString(1)), rs.getString(2), rs.getString(3)) else null
        }.toList()
    }

    /** Invalidate computations whose input digest no longer matches the stored snapshot material. */
    fun invalidateStaleComputations() {
        val snaps = listSnapshots().associateBy { it.snapshotId }
        conn.prepareStatement("SELECT computation_id,snapshot_id,input_digest,status FROM computations").use { ps ->
            val rs = ps.executeQuery()
            val stale = mutableListOf<String>()
            while (rs.next()) {
                val snap = snaps[rs.getString(2)]
                if (snap == null || Engine.digestOf(snap) != rs.getString(3)) stale += rs.getString(1)
            }
            tx {
                for (id in stale) {
                    conn.prepareStatement("UPDATE computations SET status='INVALID', invalid_reason=? WHERE computation_id=? AND status='VALID'").use {
                        it.setString(1, "source material changed; input digest mismatch"); it.setString(2, id); it.executeUpdate()
                    }
                    event("computation.invalidated", json.encodeToString(mapOf("computationId" to id)))
                }
            }
        }
    }

    // ----- decisions -----
    fun saveDecision(d: Decision) {
        tx {
            conn.prepareStatement(
                "INSERT OR REPLACE INTO decisions(decision_id,subject,status,rationale,param_version,supersedes,created_seq) VALUES(?,?,?,?,?,?,?)").use {
                it.setString(1, d.decisionId); it.setString(2, d.subject); it.setString(3, d.status.name)
                it.setString(4, d.rationale); it.setInt(5, d.paramVersion); it.setString(6, d.supersedes)
                it.setLong(7, nextSeq()); it.executeUpdate()
            }
            event("decision.recorded", json.encodeToString(mapOf(
                "decisionId" to d.decisionId, "subject" to d.subject, "status" to d.status.name)))
        }
    }

    fun listDecisions(): List<Decision> = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT decision_id,subject,status,rationale,param_version,supersedes,created_seq FROM decisions ORDER BY created_seq")
        generateSequence {
            if (rs.next()) Decision(rs.getString(1), rs.getString(2), DecisionStatus.valueOf(rs.getString(3)),
                rs.getString(4), rs.getInt(5), rs.getString(6), rs.getLong(7)) else null
        }.toList()
    }

    private fun nextSeq(): Long = conn.createStatement().use { st ->
        val rs = st.executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events"); rs.next(); rs.getLong(1)
    }

    fun close() = conn.close()
}

// small helper to keep import-site readable
private fun FailureKind.CompanionKindCorrupt(): FailureKind = FailureKind.CORRUPT_FORMAT
