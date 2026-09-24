package heapforensics

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.sql.Connection
import java.sql.DriverManager

class Store(path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    private val json = Json { prettyPrint = false }

    init {
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS snapshots(
                    snapshot_id TEXT PRIMARY KEY,
                    parser_version TEXT NOT NULL,
                    captured_at TEXT NOT NULL,
                    raw_json TEXT NOT NULL
                )""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS events(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    snapshot_id TEXT,
                    params_version INTEGER,
                    summary TEXT NOT NULL,
                    affected TEXT NOT NULL,
                    attributes TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    checksum TEXT NOT NULL
                )""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS analysis_runs(
                    run_id TEXT PRIMARY KEY,
                    snapshot_id TEXT NOT NULL,
                    params_version INTEGER NOT NULL,
                    input_digest TEXT NOT NULL,
                    parent_event_seq INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    superseded_by TEXT,
                    invalidation_reason TEXT
                )""")
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)""")
        }
    }

    override fun close() = conn.close()

    fun integrityOk(): Boolean =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA integrity_check").let { rs -> rs.next() && rs.getString(1) == "ok" }
        }

    // ---- snapshots (immutable materials) ----

    fun saveSnapshot(snapshot: HeapSnapshot, rawJson: String) {
        val existing = getSnapshotRaw(snapshot.snapshotId)
        if (existing != null) {
            require(existing == rawJson) { "snapshot ${snapshot.snapshotId} already exists with different content; immutable materials cannot be overwritten" }
            return
        }
        conn.prepareStatement("INSERT INTO snapshots(snapshot_id, parser_version, captured_at, raw_json) VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, snapshot.snapshotId); ps.setString(2, snapshot.parserVersion)
            ps.setString(3, snapshot.capturedAt); ps.setString(4, rawJson)
            ps.executeUpdate()
        }
    }

    fun getSnapshotRaw(id: String): String? =
        conn.prepareStatement("SELECT raw_json FROM snapshots WHERE snapshot_id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().let { if (it.next()) it.getString(1) else null }
        }

    fun getSnapshot(id: String): HeapSnapshot? =
        getSnapshotRaw(id)?.let { Json.decodeFromString(HeapSnapshot.serializer(), it) }

    fun listSnapshots(): List<HeapSnapshot> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT raw_json FROM snapshots ORDER BY snapshot_id").let { rs ->
                generateSequence { if (rs.next()) rs.getString(1) else null }
                    .map { Json.decodeFromString(HeapSnapshot.serializer(), it) }.toList()
            }
        }

    // ---- events (append-only, checksummed) ----

    private fun eventChecksum(e: DomainEvent): String =
        Analysis.sha(listOf(e.eventId, e.type, e.snapshotId, e.paramsVersion, e.summary,
            e.affected.joinToString(","), e.attributes.toSortedMap().toString(), e.createdAt).joinToString("|"))

    /** Appends a batch atomically: either every event lands or none does. */
    fun appendEvents(events: List<DomainEvent>): List<DomainEvent> {
        if (events.isEmpty()) return emptyList()
        // Deterministic re-runs of equivalent inputs reuse the same event ids; those already recorded are skipped.
        val existingIds = allEvents().map { it.eventId }.toSet()
        val toInsert = events.filter { it.eventId !in existingIds }
        if (toInsert.isEmpty()) return events.map { e -> allEvents().first { it.eventId == e.eventId } }
        conn.autoCommit = false
        try {
            val out = mutableListOf<DomainEvent>()
            conn.prepareStatement(
                "INSERT INTO events(event_id,type,snapshot_id,params_version,summary,affected,attributes,created_at,checksum) VALUES(?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                for (e in toInsert) {
                    ps.setString(1, e.eventId); ps.setString(2, e.type); ps.setString(3, e.snapshotId)
                    if (e.paramsVersion != null) ps.setInt(4, e.paramsVersion) else ps.setNull(4, java.sql.Types.INTEGER)
                    ps.setString(5, e.summary)
                    ps.setString(6, json.encodeToString(e.affected))
                    ps.setString(7, json.encodeToString(e.attributes))
                    ps.setString(8, e.createdAt); ps.setString(9, eventChecksum(e))
                    ps.executeUpdate()
                }
            }
            conn.commit()
            // re-read to assign seqs deterministically
            val ids = events.map { it.eventId }.toSet()
            return allEvents().filter { it.eventId in ids }
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    fun appendEvent(e: DomainEvent): DomainEvent = appendEvents(listOf(e)).single()

    private fun rowToEvent(rs: java.sql.ResultSet): DomainEvent {
        val affected = json.decodeFromString<List<String>>(rs.getString("affected"))
        val attrs = json.decodeFromString<Map<String, String>>(rs.getString("attributes"))
        return DomainEvent(rs.getString("event_id"), rs.getLong("seq"), rs.getString("type"),
            rs.getString("snapshot_id"), rs.getObject("params_version")?.let { (it as Number).toInt() },
            rs.getString("summary"), affected, attrs, rs.getString("created_at"))
    }

    fun allEvents(): List<DomainEvent> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM events ORDER BY seq").let { rs ->
                generateSequence { if (rs.next()) rs else null }.map { rowToEvent(it) }.toList()
            }
        }

    fun eventsUpTo(seq: Long): List<DomainEvent> = allEvents().filter { it.seq <= seq }

    fun maxSeq(): Long = allEvents().maxOfOrNull { it.seq } ?: 0L

    /** Verifies event checksums; returns first corrupt seq or null. Half-written tails are detectable. */
    fun verifyEventChain(): Long? =
        allEvents().firstOrNull { eventChecksum(it) != storedChecksum(it.eventId) }?.seq

    private fun storedChecksum(eventId: String): String? =
        conn.prepareStatement("SELECT checksum FROM events WHERE event_id=?").use { ps ->
            ps.setString(1, eventId); ps.executeQuery().let { if (it.next()) it.getString(1) else null }
        }

    // ---- params / decisions (derived from events) ----

    fun paramsAsOf(seq: Long, default: AnalysisParams = AnalysisParams(1, weakKeepAlive = false)): AnalysisParams {
        val setEvent = eventsUpTo(seq).filter { it.type == "PARAMS_SET" }.maxByOrNull { it.seq }
            ?: return default
        return AnalysisParams(
            version = setEvent.attributes["version"]?.toInt() ?: default.version,
            weakKeepAlive = setEvent.attributes["weakKeepAlive"]?.toBoolean() ?: default.weakKeepAlive,
            maxPathCandidates = setEvent.attributes["maxPathCandidates"]?.toInt() ?: default.maxPathCandidates,
            nodeBudget = setEvent.attributes["nodeBudget"]?.toInt() ?: default.nodeBudget)
    }

    fun currentParams(): AnalysisParams = paramsAsOf(Long.MAX_VALUE)

    fun decisionsAsOf(seq: Long): List<DomainEvent> = eventsUpTo(seq).filter { it.type == "DECISION" }

    /** Latest decision per target wins; full history is retained. */
    fun effectiveDecisions(seq: Long): Map<String, DomainEvent> =
        decisionsAsOf(seq).groupBy { it.attributes["target"] ?: "" }
            .mapValues { (_, v) -> v.maxByOrNull { it.seq }!! }
            .filterKeys { it.isNotEmpty() }

    // ---- analysis runs ----

    fun saveRun(run: AnalysisRun) {
        conn.prepareStatement("INSERT OR REPLACE INTO analysis_runs(run_id,snapshot_id,params_version,input_digest,parent_event_seq,status,created_at,superseded_by,invalidation_reason) VALUES(?,?,?,?,?,?,?,?,?)").use { ps ->
            ps.setString(1, run.runId); ps.setString(2, run.snapshotId); ps.setInt(3, run.paramsVersion)
            ps.setString(4, run.inputDigest); ps.setLong(5, run.parentEventSeq); ps.setString(6, run.status)
            ps.setString(7, run.createdAt); ps.setString(8, run.supersededBy); ps.setString(9, run.invalidationReason)
            ps.executeUpdate()
        }
    }

    fun runs(): List<AnalysisRun> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM analysis_runs ORDER BY created_at, run_id").let { rs ->
                generateSequence { if (rs.next()) rs else null }.map {
                    AnalysisRun(it.getString("run_id"), it.getString("snapshot_id"), it.getInt("params_version"),
                        it.getString("input_digest"), it.getLong("parent_event_seq"), it.getString("status"),
                        it.getString("created_at"), it.getString("superseded_by"), it.getString("invalidation_reason"))
                }.toList()
            }
        }

    fun supersedeRun(runId: String, byRunId: String, reason: String) {
        conn.prepareStatement("UPDATE analysis_runs SET superseded_by=?, invalidation_reason=? WHERE run_id=?").use { ps ->
            ps.setString(1, byRunId); ps.setString(2, reason); ps.setString(3, runId); ps.executeUpdate()
        }
    }

    fun getMeta(key: String): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use { ps ->
            ps.setString(1, key); ps.executeQuery().let { if (it.next()) it.getString(1) else null }
        }

    fun setMeta(key: String, value: String) {
        conn.prepareStatement("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)").use { ps ->
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate()
        }
    }
}
