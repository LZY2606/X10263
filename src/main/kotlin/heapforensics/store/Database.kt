package heapforensics.store

import heapforensics.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

/** SQLite 存储：材料表规范化、事件日志只追加、计算结果按输入摘要+参数版本寻址。 */
class Database private constructor(val conn: Connection, val dbPath: Path) {

    companion object {
        fun open(path: Path): Database {
            Files.createDirectories(path.toAbsolutePath().parent)
            val conn = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            conn.createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("PRAGMA foreign_keys=ON")
                it.execute("PRAGMA synchronous=NORMAL")
            }
            val db = Database(conn, path)
            db.initSchema()
            return db
        }

        fun memory(): Database = open(
            Path.of(System.getProperty("java.io.tmpdir"),
                "heapforensics-mem-${ProcessHandle.current().pid()}-${Instant.now().toEpochMilli()}.db")
        )
    }

    fun close() = conn.close()

    private fun initSchema() = conn.createStatement().use { st ->
        st.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS events (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              event_id TEXT NOT NULL UNIQUE,
              branch TEXT NOT NULL DEFAULT 'main',
              parent_seq INTEGER,
              kind TEXT NOT NULL,
              created_at TEXT NOT NULL,
              actor TEXT NOT NULL,
              payload TEXT NOT NULL
            )
            """.trimIndent()
        )
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS snapshots (
              snapshot_id TEXT PRIMARY KEY,
              parent_snapshot_id TEXT,
              source_name TEXT NOT NULL,
              parser_version TEXT NOT NULL,
              captured_at TEXT NOT NULL,
              epoch_millis INTEGER NOT NULL,
              raw_text TEXT NOT NULL,
              raw_sha256 TEXT NOT NULL,
              import_event_seq INTEGER,
              created_at TEXT NOT NULL
            )
            """.trimIndent()
        )
        listOf(
            """CREATE TABLE IF NOT EXISTS classes (
              snapshot_id TEXT NOT NULL, class_id TEXT NOT NULL, name TEXT NOT NULL,
              super_class_id TEXT, loader_id TEXT, instance_size INTEGER NOT NULL,
              PRIMARY KEY (snapshot_id, class_id))""",
            """CREATE TABLE IF NOT EXISTS objects (
              snapshot_id TEXT NOT NULL, object_id TEXT NOT NULL, address TEXT NOT NULL,
              class_id TEXT NOT NULL, size_bytes INTEGER NOT NULL, alloc_site TEXT, incomplete INTEGER NOT NULL,
              ord INTEGER NOT NULL, PRIMARY KEY (snapshot_id, object_id))""",
            """CREATE TABLE IF NOT EXISTS edges (
              snapshot_id TEXT NOT NULL, ord INTEGER NOT NULL,
              from_id TEXT NOT NULL, to_id TEXT NOT NULL, kind TEXT NOT NULL, field_name TEXT,
              PRIMARY KEY (snapshot_id, ord))""",
            """CREATE TABLE IF NOT EXISTS roots (
              snapshot_id TEXT NOT NULL, ord INTEGER NOT NULL,
              object_id TEXT NOT NULL, kind TEXT NOT NULL, note TEXT,
              PRIMARY KEY (snapshot_id, ord))""",
            """CREATE TABLE IF NOT EXISTS incomplete_blocks (
              snapshot_id TEXT NOT NULL, block_id TEXT NOT NULL, reason TEXT NOT NULL,
              line INTEGER NOT NULL, raw_text TEXT NOT NULL,
              PRIMARY KEY (snapshot_id, block_id))""",
            """CREATE TABLE IF NOT EXISTS diagnostics (
              snapshot_id TEXT NOT NULL, ord INTEGER NOT NULL,
              line INTEGER NOT NULL, severity TEXT NOT NULL, message TEXT NOT NULL, raw TEXT NOT NULL,
              PRIMARY KEY (snapshot_id, ord))""",
        ).forEach { st.executeUpdate(it) }
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS computations (
              computation_id TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              snapshot_id TEXT NOT NULL,
              params_version INTEGER NOT NULL,
              params_json TEXT NOT NULL,
              input_digest TEXT NOT NULL,
              parent_event_seq INTEGER,
              status TEXT NOT NULL,
              result_json TEXT NOT NULL,
              event_seq INTEGER NOT NULL,
              created_at TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    fun getMeta(key: String, default: String? = null): String? =
        conn.prepareStatement("SELECT value FROM meta WHERE key=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { if (it.next()) it.getString(1) else default }
        }

    fun setMeta(key: String, value: String) {
        conn.prepareStatement(
            "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value"
        ).use {
            it.setString(1, key); it.setString(2, value); it.executeUpdate()
        }
    }

    data class EventRow(
        val seq: Int, val eventId: String, val branch: String, val parentSeq: Int?,
        val kind: String, val createdAt: String, val actor: String, val payload: String,
    )

    fun appendEvent(
        kind: String, payloadJson: String, actor: String = "reviewer",
        parentSeq: Int? = null, branch: String = "main",
    ): EventRow {
        val seq = getMeta("event_counter", "0")!!.toInt() + 1
        setMeta("event_counter", seq.toString())
        val eventId = "E%04d".format(seq)
        val now = Instant.now().toString()
        conn.prepareStatement(
            """INSERT INTO events(seq,event_id,branch,parent_seq,kind,created_at,actor,payload)
               VALUES(?,?,?,?,?,?,?,?)""".trimIndent()
        ).use { ps ->
            ps.setInt(1, seq); ps.setString(2, eventId); ps.setString(3, branch)
            if (parentSeq == null) ps.setNull(4, java.sql.Types.INTEGER) else ps.setInt(4, parentSeq)
            ps.setString(5, kind); ps.setString(6, now); ps.setString(7, actor); ps.setString(8, payloadJson)
            ps.executeUpdate()
        }
        return EventRow(seq, eventId, branch, parentSeq, kind, now, actor, payloadJson)
    }

    fun listEvents(): List<EventRow> = conn.createStatement().executeQuery(
        "SELECT seq,event_id,branch,parent_seq,kind,created_at,actor,payload FROM events ORDER BY seq"
    ).use { rs -> buildList { while (rs.next()) add(rs.toEventRow()) } }

    fun getEvent(seq: Int): EventRow? =
        conn.prepareStatement(
            "SELECT seq,event_id,branch,parent_seq,kind,created_at,actor,payload FROM events WHERE seq=?"
        ).use { ps ->
            ps.setInt(1, seq)
            ps.executeQuery().use { if (it.next()) it.toEventRow() else null }
        }

    /** 导出再导入：在空库中按原始 seq 重建事件日志，身份与顺序保持。 */
    fun insertEventAt(row: EventRow) {
        if (row.seq <= getMeta("event_counter", "0")!!.toInt()) {
            error("重建事件 seq=${row.seq} 必须在空库/计数器回滚后执行")
        }
        setMeta("event_counter", row.seq.toString())
        conn.prepareStatement(
            """INSERT INTO events(seq,event_id,branch,parent_seq,kind,created_at,actor,payload)
               VALUES(?,?,?,?,?,?,?,?)""".trimIndent()
        ).use { ps ->
            ps.setInt(1, row.seq); ps.setString(2, row.eventId); ps.setString(3, row.branch)
            if (row.parentSeq == null) ps.setNull(4, java.sql.Types.INTEGER) else ps.setInt(4, row.parentSeq)
            ps.setString(5, row.kind); ps.setString(6, row.createdAt); ps.setString(7, row.actor)
            ps.setString(8, row.payload); ps.executeUpdate()
        }
    }

    private fun ResultSet.toEventRow() = EventRow(
        getInt("seq"), getString("event_id"), getString("branch"),
        getObject("parent_seq")?.let { (it as Number).toInt() },
        getString("kind"), getString("created_at"), getString("actor"), getString("payload"),
    )

    // ---- 不可变材料：快照及规范化实体 ----

    fun insertSnapshot(
        snap: HeapSnapshot, rawText: String, rawSha256: String,
        sourceName: String, parentSnapshotId: String?, importEventSeq: Int,
    ) {
        conn.prepareStatement(
            """INSERT INTO snapshots(snapshot_id,parent_snapshot_id,source_name,parser_version,
               captured_at,epoch_millis,raw_text,raw_sha256,import_event_seq,created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?)""".trimIndent()
        ).use { ps ->
            ps.setString(1, snap.snapshotId)
            ps.setObject(2, parentSnapshotId)
            ps.setString(3, sourceName)
            ps.setString(4, snap.parserVersion)
            ps.setString(5, snap.capturedAt)
            ps.setLong(6, snap.epochMillis)
            ps.setString(7, rawText)
            ps.setString(8, rawSha256)
            ps.setInt(9, importEventSeq)
            ps.setString(10, Instant.now().toString())
            ps.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO classes(snapshot_id,class_id,name,super_class_id,loader_id,instance_size) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            for (c in snap.classes) {
                ps.setString(1, snap.snapshotId); ps.setString(2, c.classId); ps.setString(3, c.name)
                ps.setObject(4, c.superClassId); ps.setObject(5, c.loaderId)
                ps.setLong(6, c.instanceSize); ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO objects(snapshot_id,object_id,address,class_id,size_bytes,alloc_site,incomplete,ord) VALUES(?,?,?,?,?,?,?,?)"
        ).use { ps ->
            snap.objects.forEachIndexed { i, o ->
                ps.setString(1, snap.snapshotId); ps.setString(2, o.objectId); ps.setString(3, o.address)
                ps.setString(4, o.classId); ps.setLong(5, o.sizeBytes)
                ps.setObject(6, o.allocSite); ps.setInt(7, if (o.incomplete) 1 else 0); ps.setInt(8, i)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO edges(snapshot_id,ord,from_id,to_id,kind,field_name) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            snap.edges.forEachIndexed { i, e ->
                ps.setString(1, snap.snapshotId); ps.setInt(2, i)
                ps.setString(3, e.fromId); ps.setString(4, e.toId)
                ps.setString(5, e.kind.name); ps.setObject(6, e.fieldName); ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO roots(snapshot_id,ord,object_id,kind,note) VALUES(?,?,?,?,?)"
        ).use { ps ->
            snap.roots.forEachIndexed { i, r ->
                ps.setString(1, snap.snapshotId); ps.setInt(2, i)
                ps.setString(3, r.objectId); ps.setString(4, r.kind.name); ps.setObject(5, r.note)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO incomplete_blocks(snapshot_id,block_id,reason,line,raw_text) VALUES(?,?,?,?,?)"
        ).use { ps ->
            for (b in snap.incompleteBlocks) {
                ps.setString(1, snap.snapshotId); ps.setString(2, b.blockId); ps.setString(3, b.reason)
                ps.setInt(4, b.line); ps.setString(5, b.rawText); ps.addBatch()
            }
            ps.executeBatch()
        }
        conn.prepareStatement(
            "INSERT INTO diagnostics(snapshot_id,ord,line,severity,message,raw) VALUES(?,?,?,?,?,?)"
        ).use { ps ->
            snap.diagnostics.forEachIndexed { i, d ->
                ps.setString(1, snap.snapshotId); ps.setInt(2, i); ps.setInt(3, d.line)
                ps.setString(4, d.severity.name); ps.setString(5, d.message); ps.setString(6, d.raw); ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    fun snapshotExists(id: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM snapshots WHERE snapshot_id=?").use {
            it.setString(1, id); it.executeQuery().use { q -> q.next() }
        }

    data class SnapshotSummary(
        val snapshotId: String, val parentSnapshotId: String?, val sourceName: String,
        val parserVersion: String, val capturedAt: String, val epochMillis: Long,
        val rawSha256: String, val importEventSeq: Int?,
        val objectCount: Int, val edgeCount: Int, val rootCount: Int,
        val incompleteBlockCount: Int, val diagnosticCount: Int,
    )

    fun listSnapshots(): List<SnapshotSummary> {
        val sql = """
            SELECT s.*,
              (SELECT COUNT(*) FROM objects o WHERE o.snapshot_id=s.snapshot_id) AS oc,
              (SELECT COUNT(*) FROM edges e WHERE e.snapshot_id=s.snapshot_id) AS ec,
              (SELECT COUNT(*) FROM roots r WHERE r.snapshot_id=s.snapshot_id) AS rc,
              (SELECT COUNT(*) FROM incomplete_blocks b WHERE b.snapshot_id=s.snapshot_id) AS bc,
              (SELECT COUNT(*) FROM diagnostics d WHERE d.snapshot_id=s.snapshot_id) AS dc
            FROM snapshots s ORDER BY s.import_event_seq, s.snapshot_id"""
        return conn.createStatement().executeQuery(sql).use { rs ->
            buildList {
                while (rs.next()) add(
                    SnapshotSummary(
                        rs.getString("snapshot_id"), rs.getString("parent_snapshot_id"),
                        rs.getString("source_name"), rs.getString("parser_version"),
                        rs.getString("captured_at"), rs.getLong("epoch_millis"),
                        rs.getString("raw_sha256"),
                        rs.getObject("import_event_seq")?.let { (it as Number).toInt() },
                        rs.getInt("oc"), rs.getInt("ec"), rs.getInt("rc"), rs.getInt("bc"), rs.getInt("dc"),
                    )
                )
            }
        }
    }

    fun loadSnapshot(id: String): HeapSnapshot? {
        val meta = conn.prepareStatement(
            "SELECT parser_version,captured_at,epoch_millis FROM snapshots WHERE snapshot_id=?"
        ).use { ps ->
            ps.setString(1, id); ps.executeQuery().use { if (!it.next()) return null else Triple(it.getString(1), it.getString(2), it.getLong(3)) }
        }
        val classes = conn.prepareStatement("SELECT class_id,name,super_class_id,loader_id,instance_size FROM classes WHERE snapshot_id=? ORDER BY class_id")
            .use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            HeapClass(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5))
                        )
                    }
                }
            }
        val objects = conn.prepareStatement("SELECT object_id,address,class_id,size_bytes,alloc_site,incomplete FROM objects WHERE snapshot_id=? ORDER BY ord")
            .use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            HeapObject(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                                rs.getString(5), rs.getInt(6) == 1)
                        )
                    }
                }
            }
        val edges = conn.prepareStatement("SELECT from_id,to_id,kind,field_name FROM edges WHERE snapshot_id=? ORDER BY ord")
            .use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            HeapEdge(rs.getString(1), rs.getString(2), EdgeKind.valueOf(rs.getString(3)), rs.getString(4))
                        )
                    }
                }
            }
        val roots = conn.prepareStatement("SELECT object_id,kind,note FROM roots WHERE snapshot_id=? ORDER BY ord")
            .use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(RootRef(rs.getString(1), RootKind.valueOf(rs.getString(2)), rs.getString(3)))
                    }
                }
            }
        val blocks = conn.prepareStatement("SELECT block_id,reason,line,raw_text FROM incomplete_blocks WHERE snapshot_id=? ORDER BY line")
            .use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(IncompleteBlock(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4))) }
                }
            }
        val diags = conn.prepareStatement("SELECT line,severity,message,raw FROM diagnostics WHERE snapshot_id=? ORDER BY ord")
            .use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(Diagnostic(rs.getInt(1), Severity.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4))) }
                }
            }
        return HeapSnapshot(id, meta.first, meta.second, meta.third, classes, objects, edges, roots, blocks, diags)
    }

    fun rawSnapshot(id: String): Pair<String, String>? =
        conn.prepareStatement("SELECT raw_text,raw_sha256 FROM snapshots WHERE snapshot_id=?").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { if (it.next()) it.getString(1) to it.getString(2) else null }
        }

    // ---- 计算结果：记录输入摘要、参数版本与父级依赖 ----

    data class ComputationRow(
        val computationId: String, val kind: String, val snapshotId: String,
        val paramsVersion: Int, val paramsJson: String, val inputDigest: String,
        val parentEventSeq: Int?, val status: String, val resultJson: String,
        val eventSeq: Int, val createdAt: String,
    )

    fun findComputation(kind: String, snapshotId: String, paramsVersion: Int): ComputationRow? =
        conn.prepareStatement(
            "SELECT * FROM computations WHERE kind=? AND snapshot_id=? AND params_version=? ORDER BY event_seq DESC LIMIT 1"
        ).use { ps ->
            ps.setString(1, kind); ps.setString(2, snapshotId); ps.setInt(3, paramsVersion)
            ps.executeQuery().use { if (it.next()) it.toComputationRow() else null }
        }

    fun insertComputation(row: ComputationRow) {
        conn.prepareStatement(
            """INSERT INTO computations(computation_id,kind,snapshot_id,params_version,params_json,
               input_digest,parent_event_seq,status,result_json,event_seq,created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?)""".trimIndent()
        ).use { ps ->
            ps.setString(1, row.computationId); ps.setString(2, row.kind); ps.setString(3, row.snapshotId)
            ps.setInt(4, row.paramsVersion); ps.setString(5, row.paramsJson); ps.setString(6, row.inputDigest)
            ps.setObject(7, row.parentEventSeq); ps.setString(8, row.status); ps.setString(9, row.resultJson)
            ps.setInt(10, row.eventSeq); ps.setString(11, row.createdAt); ps.executeUpdate()
        }
    }

    fun listComputations(): List<ComputationRow> =
        conn.createStatement().executeQuery("SELECT * FROM computations ORDER BY event_seq").use { rs ->
            buildList { while (rs.next()) add(rs.toComputationRow()) }
        }

    fun computationForEvent(eventSeq: Int): ComputationRow? =
        conn.prepareStatement("SELECT * FROM computations WHERE event_seq=?").use { ps ->
            ps.setInt(1, eventSeq); ps.executeQuery().use { if (it.next()) it.toComputationRow() else null }
        }

    private fun ResultSet.toComputationRow() = ComputationRow(
        getString("computation_id"), getString("kind"), getString("snapshot_id"),
        getInt("params_version"), getString("params_json"), getString("input_digest"),
        getObject("parent_event_seq")?.let { (it as Number).toInt() },
        getString("status"), getString("result_json"), getInt("event_seq"), getString("created_at"),
    )

    fun clearAll() {
        conn.createStatement().use {
            listOf("events", "snapshots", "classes", "objects", "edges", "roots",
                "incomplete_blocks", "diagnostics", "computations", "meta").forEach { t ->
                it.executeUpdate("DELETE FROM $t")
            }
        }
    }
}
