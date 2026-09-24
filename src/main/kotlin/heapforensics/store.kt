package heapforensics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager

const val SCHEMA_VERSION = "1"

@Serializable
data class StoredEvent(
    val seq: Long,
    val eventId: String,
    val type: String,
    val createdAtEpochMs: Long,
    val inputDigest: String?,
    val paramsVersion: String?,
    val parents: List<String>,
    val payload: String
)

@Serializable
data class Decision(
    val decisionId: String,
    val analysisId: String,
    val objectId: String?,
    val verdict: String,   // ACCEPTED | REJECTED | PARAM_ADJUST
    val note: String,
    val createdAtEpochMs: Long,
    val supersedes: String? = null
)

@Serializable
data class ExportBundle(
    val format: String = "hsf-export/1",
    val meta: Map<String, String>,
    val snapshots: List<String>,          // RawSnapshot JSON, stable order
    val analyses: List<String>,           // AnalysisResult JSON, stable order
    val decisions: List<Decision>,
    val events: List<StoredEvent>
)

class Store(val path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshots(
                  snapshot_id TEXT PRIMARY KEY,
                  raw_json TEXT NOT NULL,
                  digest TEXT NOT NULL,
                  imported_at INTEGER NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS analyses(
                  analysis_id TEXT PRIMARY KEY,
                  snapshot_id TEXT NOT NULL,
                  params_version TEXT NOT NULL,
                  input_digest TEXT,
                  status TEXT NOT NULL,
                  result_json TEXT,
                  created_at INTEGER NOT NULL)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decisions(
                  decision_id TEXT PRIMARY KEY,
                  analysis_id TEXT NOT NULL,
                  object_id TEXT,
                  verdict TEXT NOT NULL,
                  note TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  supersedes TEXT)""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS events(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  event_id TEXT NOT NULL,
                  type TEXT NOT NULL,
                  created_at INTEGER NOT NULL,
                  input_digest TEXT,
                  params_version TEXT,
                  parents_json TEXT NOT NULL,
                  payload_json TEXT NOT NULL)""")
        }
        setMetaIfAbsent("schemaVersion", SCHEMA_VERSION)
    }

    private val txDepth = ThreadLocal.withInitial { 0 }

    /** Reentrant transaction: nested calls join the outer transaction. */
    fun <T> inTransaction(block: () -> T): T {
        if (txDepth.get() > 0) {
            txDepth.set(txDepth.get() + 1)
            try { return block() } finally { txDepth.set(txDepth.get() - 1) }
        }
        conn.autoCommit = false
        txDepth.set(1)
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            txDepth.set(0)
            conn.autoCommit = true
        }
    }

    private fun setMetaIfAbsent(k: String, v: String) {
        conn.prepareStatement("INSERT OR IGNORE INTO meta(key,value) VALUES(?,?)").use {
            it.setString(1, k); it.setString(2, v); it.executeUpdate()
        }
    }
    fun getMeta(k: String): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use {
            it.setString(1, k)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    fun setMeta(k: String, v: String) {
        conn.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value").use {
            it.setString(1, k); it.setString(2, v); it.executeUpdate()
        }
    }

    fun hasSnapshot(id: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM snapshots WHERE snapshot_id=?").use {
            it.setString(1, id); it.executeQuery().use { rs -> rs.next() }
        }

    /** Import a raw snapshot as immutable material. */
    fun putSnapshot(raw: RawSnapshot, now: Long) = inTransaction {
        conn.prepareStatement("INSERT OR REPLACE INTO snapshots(snapshot_id,raw_json,digest,imported_at) VALUES(?,?,?,?)").use {
            it.setString(1, raw.snapshotId)
            it.setString(2, snapshotJson.encodeToString(RawSnapshot.serializer(), raw))
            it.setString(3, canonicalDigest(raw))
            it.setLong(4, now)
            it.executeUpdate()
        }
        appendEvent("snapshot.imported", canonicalDigest(raw), null, emptyList(),
            """{"snapshotId":"${raw.snapshotId}","parserVersion":"${raw.parserVersion}","objects":${raw.objects.size},"edges":${raw.edges.size},"roots":${raw.roots.size},"incompleteChunks":${raw.incompleteChunks.size}}""", now)
    }

    fun getSnapshot(id: String): RawSnapshot? =
        conn.prepareStatement("SELECT raw_json FROM snapshots WHERE snapshot_id=?").use {
            it.setString(1, id)
            it.executeQuery().use { rs ->
                if (rs.next()) snapshotJson.decodeFromString(RawSnapshot.serializer(), rs.getString(1)) else null
            }
        }

    fun listSnapshots(): List<Pair<String, String>> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT snapshot_id, digest FROM snapshots ORDER BY snapshot_id").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toList()
            }
        }

    fun putAnalysis(analysisId: String, snapshotId: String, outcome: AnalysisOutcome, now: Long) = inTransaction {
        val (status, resultJson, digest, pver) = when (outcome) {
            is AnalysisOutcome.Ok -> {
                val r = outcome.result
                Quad("OK", snapshotJson.encodeToString(AnalysisResult.serializer(), r), r.inputDigest, r.params.version)
            }
            is AnalysisOutcome.BudgetExhausted -> Quad("BUDGET_EXHAUSTED", null, null, ANALYSIS_VERSION)
            is AnalysisOutcome.SemanticallyUnsolvable -> Quad("SEMANTICALLY_UNSOLVABLE",
                snapshotJson.encodeToString(outcome.diagnostics), null, ANALYSIS_VERSION)
        }
        conn.prepareStatement("INSERT OR REPLACE INTO analyses(analysis_id,snapshot_id,params_version,input_digest,status,result_json,created_at) VALUES(?,?,?,?,?,?,?)").use {
            it.setString(1, analysisId); it.setString(2, snapshotId)
            it.setString(3, pver); it.setString(4, digest)
            it.setString(5, status); it.setString(6, resultJson); it.setLong(7, now)
            it.executeUpdate()
        }
        val payload = when (outcome) {
            is AnalysisOutcome.Ok -> """{"analysisId":"$analysisId","snapshotId":"$snapshotId","status":"OK","reachable":${outcome.result.reachable.size},"unreachable":${outcome.result.unreachable.size},"leakCandidates":${outcome.result.leakCandidates.size}}"""
            is AnalysisOutcome.BudgetExhausted -> """{"analysisId":"$analysisId","snapshotId":"$snapshotId","status":"BUDGET_EXHAUSTED","detail":${Json.encodeToString(outcome.detail)}}"""
            is AnalysisOutcome.SemanticallyUnsolvable -> """{"analysisId":"$analysisId","snapshotId":"$snapshotId","status":"SEMANTICALLY_UNSOLVABLE","diagnostics":${outcome.diagnostics.size}}"""
        }
        appendEvent("analysis.completed", digest, pver, emptyList(), payload, now)
    }

    fun getAnalysis(analysisId: String): Triple<String, String, String?>? =
        conn.prepareStatement("SELECT snapshot_id,status,result_json FROM analyses WHERE analysis_id=?").use {
            it.setString(1, analysisId)
            it.executeQuery().use { rs ->
                if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null
            }
        }

    fun listAnalyses(snapshotId: String): List<Triple<String, String, String>> =
        conn.prepareStatement("SELECT analysis_id,status,params_version FROM analyses WHERE snapshot_id=? ORDER BY created_at, analysis_id").use {
            it.setString(1, snapshotId)
            it.executeQuery().use { rs ->
                generateSequence { if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null }.toList()
            }
        }

    fun addDecision(d: Decision) = inTransaction {
        conn.prepareStatement("INSERT INTO decisions(decision_id,analysis_id,object_id,verdict,note,created_at,supersedes) VALUES(?,?,?,?,?,?,?)").use {
            it.setString(1, d.decisionId); it.setString(2, d.analysisId); it.setString(3, d.objectId)
            it.setString(4, d.verdict); it.setString(5, d.note); it.setLong(6, d.createdAtEpochMs)
            it.setString(7, d.supersedes); it.executeUpdate()
        }
        appendEvent("decision.recorded", null, null,
            listOfNotNull(d.supersedes),
            """{"decisionId":"${d.decisionId}","analysisId":"${d.analysisId}","objectId":${d.objectId?.let { "\"$it\"" } ?: "null"},"verdict":"${d.verdict}","note":${Json.encodeToString(d.note)}}""",
            d.createdAtEpochMs)
    }

    fun listDecisions(analysisId: String? = null): List<Decision> {
        val sql = if (analysisId == null) "SELECT * FROM decisions ORDER BY created_at, decision_id"
        else "SELECT * FROM decisions WHERE analysis_id=? ORDER BY created_at, decision_id"
        return conn.prepareStatement(sql).use {
            if (analysisId != null) it.setString(1, analysisId)
            it.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) Decision(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7)) else null
                }.toList()
            }
        }
    }

    fun appendEvent(type: String, inputDigest: String?, paramsVersion: String?, parents: List<String>, payload: String, now: Long): Long {
        conn.prepareStatement("INSERT INTO events(event_id,type,created_at,input_digest,params_version,parents_json,payload_json) VALUES(?,?,?,?,?,?,?)").use {
            it.setString(1, "evt-${now}-${type}")
            it.setString(2, type); it.setLong(3, now)
            it.setString(4, inputDigest); it.setString(5, paramsVersion)
            it.setString(6, Json.encodeToString(parents)); it.setString(7, payload)
            it.executeUpdate()
        }
        return conn.createStatement().use { it.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
    }

    fun listEvents(): List<StoredEvent> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT seq,event_id,type,created_at,input_digest,params_version,parents_json,payload_json FROM events ORDER BY seq").use { rs ->
                generateSequence {
                    if (rs.next()) StoredEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6),
                        Json.decodeFromString(rs.getString(7)), rs.getString(8)) else null
                }.toList()
            }
        }

    fun getEvent(seq: Long): StoredEvent? =
        conn.prepareStatement("SELECT seq,event_id,type,created_at,input_digest,params_version,parents_json,payload_json FROM events WHERE seq=?").use {
            it.setLong(1, seq)
            it.executeQuery().use { rs ->
                if (rs.next()) StoredEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                    rs.getString(5), rs.getString(6),
                    Json.decodeFromString(rs.getString(7)), rs.getString(8)) else null
            }
        }

    // ---------- Export / import (identity, order, provenance preserved) ----------

    fun export(): ExportBundle = ExportBundle(
        meta = listOf("schemaVersion", "archiveId").mapNotNull { k -> getMeta(k)?.let { k to it } }.toMap(),
        snapshots = conn.createStatement().use { st ->
            st.executeQuery("SELECT raw_json FROM snapshots ORDER BY snapshot_id").use { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
            }
        },
        analyses = conn.createStatement().use { st ->
            st.executeQuery("SELECT analysis_id,snapshot_id,params_version,input_digest,status,result_json,created_at FROM analyses ORDER BY created_at, analysis_id").use { rs ->
                generateSequence {
                    if (rs.next()) Json.encodeToString(listOf(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4) ?: "", rs.getString(5), rs.getString(6) ?: "", rs.getLong(7).toString())) else null
                }.toList()
            }
        },
        decisions = listDecisions(),
        events = listEvents()
    )

    fun importBundle(bundle: ExportBundle, now: Long) = inTransaction {
        bundle.meta.forEach { (k, v) -> setMeta(k, v) }
        for (s in bundle.snapshots) {
            val raw = snapshotJson.decodeFromString(RawSnapshot.serializer(), s)
            conn.prepareStatement("INSERT OR REPLACE INTO snapshots(snapshot_id,raw_json,digest,imported_at) VALUES(?,?,?,?)").use {
                it.setString(1, raw.snapshotId); it.setString(2, s)
                it.setString(3, canonicalDigest(raw)); it.setLong(4, now); it.executeUpdate()
            }
        }
        for (a in bundle.analyses) {
            val f = Json.decodeFromString<List<String>>(a)
            conn.prepareStatement("INSERT OR REPLACE INTO analyses(analysis_id,snapshot_id,params_version,input_digest,status,result_json,created_at) VALUES(?,?,?,?,?,?,?)").use {
                it.setString(1, f[0]); it.setString(2, f[1]); it.setString(3, f[2])
                it.setString(4, f[3].ifEmpty { null }); it.setString(5, f[4])
                it.setString(6, f[5].ifEmpty { null }); it.setLong(7, f[6].toLong()); it.executeUpdate()
            }
        }
        for (d in bundle.decisions) {
            conn.prepareStatement("INSERT OR REPLACE INTO decisions(decision_id,analysis_id,object_id,verdict,note,created_at,supersedes) VALUES(?,?,?,?,?,?,?)").use {
                it.setString(1, d.decisionId); it.setString(2, d.analysisId); it.setString(3, d.objectId)
                it.setString(4, d.verdict); it.setString(5, d.note); it.setLong(6, d.createdAtEpochMs)
                it.setString(7, d.supersedes); it.executeUpdate()
            }
        }
        for (e in bundle.events) {
            conn.prepareStatement("INSERT INTO events(event_id,type,created_at,input_digest,params_version,parents_json,payload_json) VALUES(?,?,?,?,?,?,?)").use {
                it.setString(1, e.eventId); it.setString(2, e.type); it.setLong(3, e.createdAtEpochMs)
                it.setString(4, e.inputDigest); it.setString(5, e.paramsVersion)
                it.setString(6, Json.encodeToString(e.parents)); it.setString(7, e.payload)
                it.executeUpdate()
            }
        }
    }

    private data class Quad(val a: String, val b: String?, val c: String?, val d: String)

    fun close() = conn.close()
}
