package heapsleuth

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/** SQLite 存储层。所有写入走事务 + write_sessions 标记，支持半写恢复。 */
class Store(path: String) {
    private val conn: Connection

    init {
        if (path != ":memory:") Paths.get(path).toAbsolutePath().parent?.let { Files.createDirectories(it) }
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
        }
        schema()
    }

    fun close() = conn.close()

    private fun schema() {
        conn.createStatement().use { st ->
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS snapshots(
                snapshot_id TEXT PRIMARY KEY, name TEXT, parser_version TEXT,
                imported_at TEXT, source TEXT, digest TEXT, raw_json TEXT NOT NULL)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS classes(
                snapshot_id TEXT, class_id TEXT, name TEXT, super_name TEXT, metadata_json TEXT,
                PRIMARY KEY(snapshot_id, class_id))""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS objects(
                snapshot_id TEXT, object_id TEXT, address_raw TEXT, class_id TEXT,
                size_bytes INTEGER, size_raw TEXT, alloc_site TEXT, incomplete INTEGER,
                PRIMARY KEY(snapshot_id, object_id))""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS edges(
                snapshot_id TEXT, edge_id TEXT, from_obj TEXT, to_obj TEXT, kind TEXT, name TEXT,
                PRIMARY KEY(snapshot_id, edge_id))""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS roots(
                snapshot_id TEXT, root_id TEXT, object_id TEXT, root_type TEXT,
                PRIMARY KEY(snapshot_id, root_id))""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS incomplete_blocks(
                snapshot_id TEXT, block_id TEXT, offset_raw TEXT, length INTEGER, reason TEXT, raw TEXT,
                PRIMARY KEY(snapshot_id, block_id))""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS analyses(
                run_id TEXT PRIMARY KEY, snapshot_id TEXT, params_version INTEGER, params_json TEXT,
                input_digest TEXT, parent_run_id TEXT, status TEXT, outcome_json TEXT, seq INTEGER, created_at TEXT)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS events(
                event_id TEXT PRIMARY KEY, seq INTEGER, case_id TEXT, kind TEXT,
                payload_json TEXT, created_at TEXT, parent_event_id TEXT)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS decisions(
                decision_id TEXT PRIMARY KEY, run_id TEXT, candidate_key TEXT,
                verdict TEXT, rationale TEXT, reviewer TEXT, created_at TEXT, supersedes TEXT)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS write_sessions(
                session_id TEXT PRIMARY KEY, started_at TEXT, status TEXT)""")
            st.executeUpdate("""CREATE TABLE IF NOT EXISTS archive_meta(
                case_id TEXT PRIMARY KEY, title TEXT, baseline_snapshot_id TEXT, json TEXT)""")
        }
    }

    fun now(): String = Instant.now().toString()

    /** 半写恢复：标记遗留的 in_progress 会话为 abandoned（事务保证无半成品行）。 */
    fun recover(): Int {
        val abandoned = conn.prepareStatement("SELECT COUNT(*) FROM write_sessions WHERE status='in_progress'")
            .use { it.executeQuery().run { if (next()) getInt(1) else 0 } }
        conn.prepareStatement("UPDATE write_sessions SET status='abandoned' WHERE status='in_progress'").use { it.executeUpdate() }
        return abandoned
    }

    /** 事务写入：session 行在事务外先落盘模拟崩溃窗口；块内异常整体回滚。 */
    fun <T> sessionedWrite(sessionId: String, block: () -> T): T {
        conn.prepareStatement("INSERT OR REPLACE INTO write_sessions(session_id, started_at, status) VALUES(?,?, 'in_progress')")
            .apply { setString(1, sessionId); setString(2, now()) }.use { it.executeUpdate() }
        try {
            val r = transaction { block() }
            conn.prepareStatement("UPDATE write_sessions SET status='committed' WHERE session_id=?")
                .apply { setString(1, sessionId) }.use { it.executeUpdate() }
            return r
        } catch (e: Exception) {
            conn.prepareStatement("UPDATE write_sessions SET status='abandoned' WHERE session_id=?")
                .apply { setString(1, sessionId) }.use { it.executeUpdate() }
            throw e
        }
    }

    fun <T> transaction(block: () -> T): T {
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun nextSeq(): Long =
        conn.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0) FROM events").run { next(); getLong(1) + 1 }

    // ---------- 快照 ----------
    fun saveSnapshot(b: SnapshotBundle, source: String = "import", rawJson: String = "") {
        val digest = bundleDigest(b)
        transaction {
            conn.prepareStatement(
                "INSERT OR IGNORE INTO snapshots VALUES(?,?,?,?,?,?,?)"
            ).apply {
                setString(1, b.snapshotId); setString(2, b.name); setString(3, b.parserVersion)
                setString(4, now()); setString(5, source); setString(6, digest); setString(7, rawJson)
            }.use { it.executeUpdate() }
            conn.prepareStatement("INSERT OR REPLACE INTO classes VALUES(?,?,?,?,?)").use { ps ->
                b.classes.forEach { c ->
                    ps.setString(1, b.snapshotId); ps.setString(2, c.classId); ps.setString(3, c.name)
                    ps.setString(4, c.superName)
                    ps.setString(5, CanonicalJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), c.metadata))
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("INSERT OR REPLACE INTO objects VALUES(?,?,?,?,?,?,?,?)").use { ps ->
                b.objects.forEach { o ->
                    ps.setString(1, b.snapshotId); ps.setString(2, o.objectId); ps.setString(3, o.addressRaw)
                    ps.setString(4, o.classId); ps.setObject(5, o.sizeBytes); ps.setString(6, o.sizeRaw)
                    ps.setString(7, o.allocSite); ps.setInt(8, if (o.incomplete) 1 else 0)
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("INSERT OR REPLACE INTO edges VALUES(?,?,?,?,?,?)").use { ps ->
                b.edges.forEach { e ->
                    ps.setString(1, b.snapshotId); ps.setString(2, e.edgeId); ps.setString(3, e.from)
                    ps.setString(4, e.to); ps.setString(5, e.kind.name); ps.setString(6, e.name)
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("INSERT OR REPLACE INTO roots VALUES(?,?,?,?)").use { ps ->
                b.roots.forEach { r ->
                    ps.setString(1, b.snapshotId); ps.setString(2, r.rootId)
                    ps.setString(3, r.objectId); ps.setString(4, r.rootType)
                    ps.executeUpdate()
                }
            }
            conn.prepareStatement("INSERT OR REPLACE INTO incomplete_blocks VALUES(?,?,?,?,?,?)").use { ps ->
                b.incompleteBlocks.forEach { bl ->
                    ps.setString(1, b.snapshotId); ps.setString(2, bl.blockId); ps.setString(3, bl.offsetRaw)
                    ps.setObject(4, bl.length); ps.setString(5, bl.reason); ps.setString(6, bl.raw)
                    ps.executeUpdate()
                }
            }
        }
    }

    fun listSnapshots(): List<SnapshotBundle> =
        conn.createStatement().executeQuery("SELECT snapshot_id FROM snapshots ORDER BY snapshot_id").use { rs ->
            buildList { while (rs.next()) add(loadSnapshot(rs.getString(1))) }
        }

    fun hasSnapshot(id: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM snapshots WHERE snapshot_id=?").apply { setString(1, id) }
            .use { it.executeQuery().next() }

    fun snapshotDigest(id: String): String? =
        conn.prepareStatement("SELECT digest FROM snapshots WHERE snapshot_id=?").apply { setString(1, id) }
            .use { it.executeQuery().run { if (next()) getString(1) else null } }

    fun loadSnapshot(id: String): SnapshotBundle {
        val (name, parserVersion) = conn.prepareStatement("SELECT name, parser_version FROM snapshots WHERE snapshot_id=?")
            .apply { setString(1, id) }.use { rs ->
                rs.executeQuery().run { require(next()) { "snapshot $id not found" }; getString(1) to getString(2) }
            }
        val classes = conn.prepareStatement("SELECT class_id,name,super_name,metadata_json FROM classes WHERE snapshot_id=? ORDER BY class_id")
            .apply { setString(1, id) }.use { rs ->
                buildList {
                    while (rs.next()) {
                        val meta = rs.getString(4)?.takeIf { it.isNotEmpty() }
                            ?.let { CanonicalJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) }
                            ?: emptyMap()
                        add(ClassMeta(rs.getString(1), rs.getString(2), rs.getString(3), meta))
                    }
                }
            }
        val objects = conn.prepareStatement("SELECT object_id,address_raw,class_id,size_bytes,size_raw,alloc_site,incomplete FROM objects WHERE snapshot_id=? ORDER BY object_id")
            .apply { setString(1, id) }.use { rs ->
                buildList {
                    while (rs.next()) add(
                        HeapObject(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(5), rs.getLong(4).takeIf { !rs.wasNull() },
                            rs.getString(6), rs.getInt(7) == 1)
                    )
                }
            }
        val edges = conn.prepareStatement("SELECT edge_id,from_obj,to_obj,kind,name FROM edges WHERE snapshot_id=? ORDER BY edge_id")
            .apply { setString(1, id) }.use { rs ->
                buildList {
                    while (rs.next()) add(
                        HeapEdge(rs.getString(1), rs.getString(2), rs.getString(3),
                            EdgeKind.valueOf(rs.getString(4)), rs.getString(5))
                    )
                }
            }
        val roots = conn.prepareStatement("SELECT root_id,object_id,root_type FROM roots WHERE snapshot_id=? ORDER BY root_id")
            .apply { setString(1, id) }.use { rs ->
                buildList { while (rs.next()) add(HeapRoot(rs.getString(1), rs.getString(2), rs.getString(3))) }
            }
        val blocks = conn.prepareStatement("SELECT block_id,offset_raw,length,reason,raw FROM incomplete_blocks WHERE snapshot_id=? ORDER BY block_id")
            .apply { setString(1, id) }.use { rs ->
                buildList {
                    while (rs.next()) add(
                        IncompleteBlock(rs.getString(1), rs.getString(2),
                            rs.getLong(3).takeIf { !rs.wasNull() }, rs.getString(4), rs.getString(5))
                    )
                }
            }
        return SnapshotBundle(id, name, parserVersion, classes, objects, edges, roots, blocks)
    }

    // ---------- 分析运行 ----------
    fun saveRun(runId: String, snapshotId: String, params: AnalysisParams,
                inputDigest: String, parentRunId: String?, outcome: RunOutcome) {
        val status = when (outcome) {
            is RunOutcome.Ok -> "OK"
            is RunOutcome.BudgetExhausted -> "BUDGET_EXHAUSTED"
            is RunOutcome.SemanticUnsolvable -> "SEMANTIC_UNSOLVABLE"
        }
        val seq = conn.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0) FROM analyses")
            .run { next(); getLong(1) + 1 }
        transaction {
            conn.prepareStatement("INSERT OR REPLACE INTO analyses VALUES(?,?,?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, runId); ps.setString(2, snapshotId); ps.setInt(3, params.version)
                ps.setString(4, CanonicalJson.encodeToString(AnalysisParams.serializer(), params))
                ps.setString(5, inputDigest); ps.setString(6, parentRunId); ps.setString(7, status)
                ps.setString(8, CanonicalJson.encodeToString(RunOutcome.serializer(), outcome))
                ps.setLong(9, seq); ps.setString(10, now())
                ps.executeUpdate()
            }
        }
    }

    data class RunRow(val runId: String, val snapshotId: String, val paramsVersion: Int,
                      val status: String, val inputDigest: String, val parentRunId: String?,
                      val seq: Long, val createdAt: String)

    fun listRuns(snapshotId: String? = null): List<RunRow> {
        val sql = if (snapshotId == null)
            "SELECT run_id,snapshot_id,params_version,status,input_digest,parent_run_id,seq,created_at FROM analyses ORDER BY seq"
        else
            "SELECT run_id,snapshot_id,params_version,status,input_digest,parent_run_id,seq,created_at FROM analyses WHERE snapshot_id=? ORDER BY seq"
        return conn.prepareStatement(sql).apply { if (snapshotId != null) setString(1, snapshotId) }
            .use { rs ->
                buildList {
                    while (rs.next()) add(RunRow(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8)))
                }
            }
    }

    fun loadRun(runId: String): Triple<AnalysisParams, RunOutcome, RunRow>? {
        conn.prepareStatement("SELECT params_json,outcome_json,snapshot_id,params_version,status,input_digest,parent_run_id,seq,created_at FROM analyses WHERE run_id=?")
            .apply { setString(1, runId) }.use { rs ->
                val q = rs.executeQuery()
                if (!q.next()) return null
                val params = CanonicalJson.decodeFromString(AnalysisParams.serializer(), q.getString(1))
                val outcome = CanonicalJson.decodeFromString(RunOutcome.serializer(), q.getString(2))
                val row = RunRow(runId, q.getString(3), q.getInt(4), q.getString(5),
                    q.getString(6), q.getString(7), q.getLong(8), q.getString(9))
                return Triple(params, outcome, row)
            }
    }

    /** 导出往返专用：保留原始 seq/createdAt 的写入。 */
    fun saveRunRaw(runId: String, snapshotId: String, params: AnalysisParams,
                   inputDigest: String, parentRunId: String?, outcome: RunOutcome,
                   seq: Long, createdAt: String) {
        val status = when (outcome) {
            is RunOutcome.Ok -> "OK"
            is RunOutcome.BudgetExhausted -> "BUDGET_EXHAUSTED"
            is RunOutcome.SemanticUnsolvable -> "SEMANTIC_UNSOLVABLE"
        }
        transaction {
            conn.prepareStatement("INSERT OR REPLACE INTO analyses VALUES(?,?,?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, runId); ps.setString(2, snapshotId); ps.setInt(3, params.version)
                ps.setString(4, CanonicalJson.encodeToString(AnalysisParams.serializer(), params))
                ps.setString(5, inputDigest); ps.setString(6, parentRunId); ps.setString(7, status)
                ps.setString(8, CanonicalJson.encodeToString(RunOutcome.serializer(), outcome))
                ps.setLong(9, seq); ps.setString(10, createdAt)
                ps.executeUpdate()
            }
        }
    }

    // ---------- 事件 ----------
    fun addEvent(caseId: String, kind: String, payloadJson: String, parentEventId: String? = null): JournalEvent {
        val seq = nextSeq()
        val e = JournalEvent("ev-%06d".format(seq), seq, caseId, kind, payloadJson, now(), parentEventId)
        transaction {
            conn.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, e.eventId); ps.setLong(2, e.seq); ps.setString(3, e.caseId)
                ps.setString(4, e.kind); ps.setString(5, e.payloadJson); ps.setString(6, e.createdAt)
                ps.setString(7, e.parentEventId); ps.executeUpdate()
            }
        }
        return e
    }

    fun insertEvent(e: JournalEvent) {
        transaction {
            conn.prepareStatement("INSERT OR IGNORE INTO events VALUES(?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, e.eventId); ps.setLong(2, e.seq); ps.setString(3, e.caseId)
                ps.setString(4, e.kind); ps.setString(5, e.payloadJson); ps.setString(6, e.createdAt)
                ps.setString(7, e.parentEventId); ps.executeUpdate()
            }
        }
    }

    fun listEvents(caseId: String? = null): List<JournalEvent> {
        val sql = if (caseId == null) "SELECT * FROM events ORDER BY seq"
        else "SELECT * FROM events WHERE case_id=? ORDER BY seq"
        return conn.prepareStatement(sql).apply { if (caseId != null) setString(1, caseId) }.use { rs ->
            buildList {
                while (rs.next()) add(JournalEvent(rs.getString(1), rs.getLong(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)))
            }
        }
    }

    fun loadEvent(eventId: String): JournalEvent? =
        conn.prepareStatement("SELECT * FROM events WHERE event_id=?").apply { setString(1, eventId) }.use { rs ->
            val q = rs.executeQuery()
            if (!q.next()) null else JournalEvent(q.getString(1), q.getLong(2), q.getString(3),
                q.getString(4), q.getString(5), q.getString(6), q.getString(7))
        }

    // ---------- 决定 ----------
    fun saveDecision(d: Decision) {
        transaction {
            conn.prepareStatement("INSERT OR REPLACE INTO decisions VALUES(?,?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, d.decisionId); ps.setString(2, d.runId); ps.setString(3, d.candidateKey)
                ps.setString(4, d.verdict); ps.setString(5, d.rationale); ps.setString(6, d.reviewer)
                ps.setString(7, d.createdAt); ps.setString(8, d.supersedes); ps.executeUpdate()
            }
        }
    }

    fun listDecisions(runId: String? = null): List<Decision> {
        val sql = if (runId == null) "SELECT * FROM decisions ORDER BY created_at, decision_id"
        else "SELECT * FROM decisions WHERE run_id=? ORDER BY created_at, decision_id"
        return conn.prepareStatement(sql).apply { if (runId != null) setString(1, runId) }.use { rs ->
            buildList {
                while (rs.next()) add(Decision(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
            }
        }
    }

    // ---------- 案例档案元数据 ----------
    fun saveArchiveMeta(caseId: String, title: String, baselineSnapshotId: String, json: String) {
        transaction {
            conn.prepareStatement("INSERT OR REPLACE INTO archive_meta VALUES(?,?,?,?)").use { ps ->
                ps.setString(1, caseId); ps.setString(2, title); ps.setString(3, baselineSnapshotId)
                ps.setString(4, json); ps.executeUpdate()
            }
        }
    }

    fun loadArchiveMeta(): Triple<String, String, String>? =
        conn.createStatement().executeQuery("SELECT case_id,title,json FROM archive_meta ORDER BY case_id LIMIT 1")
            .run { if (next()) Triple(getString(1), getString(2), getString(3)) else null }

    fun counts(): Map<String, Long> {
        val t = listOf("snapshots","classes","objects","edges","roots","incomplete_blocks","analyses","events","decisions")
        return t.associateWith { tbl ->
            conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tbl").run { next(); getLong(1) }
        }
    }

    fun wipeAll() {
        transaction {
            listOf("snapshots","classes","objectsS","objects","edges","roots","incomplete_blocks",
                "analyses","events","decisions","write_sessions","archive_meta").forEach { t ->
                runCatching { conn.createStatement().executeUpdate("DELETE FROM $t") }
            }
        }
    }
}
