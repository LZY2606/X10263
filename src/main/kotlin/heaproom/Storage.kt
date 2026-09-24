package heaproom

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.util.UUID

data class StoredSnapshot(
    val doc: SnapshotDoc,
    val sourceLabel: String,
    val importedSeq: Int
)

data class EventRow(
    val seq: Int,
    val eventId: String,
    val kind: String,
    val payload: String,
    val parents: List<Int>,
    val createdAt: String
)

data class ParamRow(val version: String, val weakPolicy: WeakPolicy, val nodeBudget: Int, val eventSeq: Int)

data class ComputationRow(
    val computeId: String,
    val snapshotId: String,
    val inputDigest: String,
    val paramVersion: String,
    val parentEventSeq: Int,
    val status: String,
    val resultJson: String,
    val validFromEvent: Int,
    val validUntilEvent: Int?,
    val invalidationReason: String?
)

data class DecisionRow(
    val decisionId: String,
    val candidateId: String,
    val snapshotId: String,
    val action: String,
    val rationale: String,
    val eventSeq: Int,
    val retractedByEvent: Int?
)

class Storage(private val dbPath: String) {
    init { require(dbPath != ":memory:") || true }

    private fun connect(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").apply {
            createStatement().execute("PRAGMA foreign_keys=ON")
        }

    fun <T> tx(block: (Connection) -> T): T {
        connect().use { conn ->
            conn.autoCommit = false
            try {
                val v = block(conn)
                conn.commit()
                return v
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            }
        }
    }

    fun init() {
        tx { c ->
            c.createStatement().use { s ->
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS snapshots(
                    snapshot_id TEXT PRIMARY KEY, parser_version TEXT, captured_at TEXT,
                    format_version INTEGER, source_label TEXT, imported_seq INTEGER)""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS classes(
                    snapshot_id TEXT, class_id TEXT, name TEXT, super_name TEXT, renamed_from TEXT,
                    fields_json TEXT, ord INTEGER, PRIMARY KEY(snapshot_id, class_id))""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS objects(
                    snapshot_id TEXT, object_id TEXT, address TEXT, class_id TEXT,
                    size_raw TEXT, size_unit TEXT, alloc_json TEXT, ord INTEGER,
                    PRIMARY KEY(snapshot_id, object_id))""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS edges(
                    snapshot_id TEXT, from_id TEXT, to_id TEXT, kind TEXT, name TEXT, ord INTEGER,
                    PRIMARY KEY(snapshot_id, from_id, to_id, kind, name))""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS roots(
                    snapshot_id TEXT, object_id TEXT, root_type TEXT, ord INTEGER,
                    PRIMARY KEY(snapshot_id, object_id, root_type))""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS incomplete_blocks(
                    snapshot_id TEXT, position TEXT, reason TEXT, raw_hex TEXT, ord INTEGER,
                    PRIMARY KEY(snapshot_id, position))""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS diagnostics(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, snapshot_id TEXT, position TEXT,
                    severity TEXT, message TEXT, ord INTEGER)""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS param_sets(
                    version TEXT PRIMARY KEY, weak_policy TEXT, node_budget INTEGER, event_seq INTEGER)""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS events(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE, kind TEXT,
                    payload TEXT, parents TEXT, created_at TEXT)""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS computations(
                    compute_id TEXT PRIMARY KEY, snapshot_id TEXT, input_digest TEXT,
                    param_version TEXT, parent_event_seq INTEGER, status TEXT, result_json TEXT,
                    valid_from_event INTEGER, valid_until_event INTEGER, invalidation_reason TEXT)""")
                s.executeUpdate("""CREATE TABLE IF NOT EXISTS decisions(
                    decision_id TEXT PRIMARY KEY, candidate_id TEXT, snapshot_id TEXT, action TEXT,
                    rationale TEXT, event_seq INTEGER, retracted_by_event INTEGER)""")
            }
        }
    }

    fun snapshotExists(c: Connection, snapshotId: String): Boolean =
        c.prepareStatement("SELECT 1 FROM snapshots WHERE snapshot_id=?").use {
            it.setString(1, snapshotId)
            it.executeQuery().use { r -> r.next() }
        }

    /** 校验 + 导入原始材料；任何失败整体回滚。返回带位置的诊断。 */
    fun importSnapshot(c: Connection, docIn: SnapshotDoc, sourceLabel: String): ImportOutcome {
        if (docIn.format != "heaproom-snapshot")
            return ImportOutcome.CorruptFormat("format", "无法识别的格式: ${docIn.format}")
        if (docIn.snapshotId.isBlank())
            return ImportOutcome.CorruptFormat("snapshotId", "snapshotId 为空")
        if (snapshotExists(c, docIn.snapshotId))
            return ImportOutcome.CorruptFormat("snapshotId", "快照身份 ${docIn.snapshotId} 已存在，不可覆盖")

        val doc = Engine.canonical(docIn)
        val diagnostics = mutableListOf<Diagnostic>()
        val ids = doc.objects.map { it.objectId }.toHashSet()
        val classIds = doc.classes.map { it.classId }.toHashSet()
        val seenAddress = HashMap<String, MutableList<String>>()
        for (o in doc.objects) {
            seenAddress.getOrPut(o.address) { mutableListOf() } += o.objectId
            if (o.classId !in classIds)
                diagnostics += Diagnostic("object:${o.objectId}.classId", "error",
                    "对象引用未知类元数据 ${o.classId}")
            if (Units.normalizeSize(o.size) == null)
                diagnostics += Diagnostic("object:${o.objectId}.size", "warning",
                    "大小 ${o.size.raw} ${o.size.unit} 无法转换为字节基准，原始表示保留在原位")
        }
        seenAddress.filter { it.value.size > 1 }.forEach { (addr, objs) ->
            diagnostics += Diagnostic("objects:${objs.joinToString(",")}", "warning",
                "地址 $addr 重复出现于 ${objs.size} 个对象；地址不是稳定身份，按 objectId 分别保留")
        }
        for (e in doc.edges) {
            if (e.from !in ids) diagnostics += Diagnostic("edge:${e.from}->${e.to}", "error",
                "边起点缺失（不完整对象），位置 edge:${e.from}->${e.to}")
            if (e.to !in ids) diagnostics += Diagnostic("edge:${e.from}->${e.to}", "error",
                "边终点 ${e.to} 缺失（悬空引用/不完整对象），位置 edge:${e.from}->${e.to}")
        }
        for (r in doc.roots) {
            if (r.objectId !in ids) diagnostics += Diagnostic("root:${r.objectId}", "error",
                "root 分类指向缺失对象，位置 root:${r.objectId}")
        }

        val seq = nextEventSeq(c)
        c.prepareStatement("INSERT INTO snapshots VALUES(?,?,?,?,?,?)").use { p ->
            p.setString(1, doc.snapshotId); p.setString(2, doc.parserVersion)
            p.setString(3, doc.capturedAt); p.setInt(4, doc.formatVersion)
            p.setString(5, sourceLabel); p.setInt(6, seq)
            p.executeUpdate()
        }
        doc.classes.forEachIndexed { i, cl ->
            c.prepareStatement("INSERT INTO classes VALUES(?,?,?,?,?,?,?)").use { p ->
                p.setString(1, doc.snapshotId); p.setString(2, cl.classId); p.setString(3, cl.name)
                p.setString(4, cl.superName); p.setString(5, cl.renamedFrom)
                p.setString(6, JsonFmt.encodeToString(cl.fields)); p.setInt(7, i)
                p.executeUpdate()
            }
        }
        doc.objects.forEachIndexed { i, o ->
            c.prepareStatement("INSERT INTO objects VALUES(?,?,?,?,?,?,?,?)").use { p ->
                p.setString(1, doc.snapshotId); p.setString(2, o.objectId); p.setString(3, o.address)
                p.setString(4, o.classId); p.setString(5, o.size.raw); p.setString(6, o.size.unit)
                p.setString(7, o.allocationSite?.let { JsonFmt.encodeToString(it) }); p.setInt(8, i)
                p.executeUpdate()
            }
        }
        doc.edges.forEachIndexed { i, e ->
            c.prepareStatement("INSERT INTO edges VALUES(?,?,?,?,?,?)").use { p ->
                p.setString(1, doc.snapshotId); p.setString(2, e.from); p.setString(3, e.to)
                p.setString(4, e.kind.name); p.setString(5, e.name); p.setInt(6, i)
                p.executeUpdate()
            }
        }
        doc.roots.forEachIndexed { i, r ->
            c.prepareStatement("INSERT INTO roots VALUES(?,?,?,?)").use { p ->
                p.setString(1, doc.snapshotId); p.setString(2, r.objectId)
                p.setString(3, r.rootType.name); p.setInt(4, i)
                p.executeUpdate()
            }
        }
        doc.incompleteBlocks.forEachIndexed { i, b ->
            c.prepareStatement("INSERT INTO incomplete_blocks VALUES(?,?,?,?,?)").use { p ->
                p.setString(1, doc.snapshotId); p.setString(2, b.position); p.setString(3, b.reason)
                p.setString(4, b.rawHex); p.setInt(5, i)
                p.executeUpdate()
            }
        }
        diagnostics.forEachIndexed { i, d ->
            c.prepareStatement("INSERT INTO diagnostics(snapshot_id,position,severity,message,ord) VALUES(?,?,?,?,?)").use { p ->
                p.setString(1, doc.snapshotId); p.setString(2, d.position)
                p.setString(3, d.severity); p.setString(4, d.message); p.setInt(5, i)
                p.executeUpdate()
            }
        }
        return ImportOutcome.Ok(doc.snapshotId, diagnostics)
    }

    fun nextEventSeq(c: Connection): Int =
        c.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events").use {
            if (it.next()) it.getInt(1) else 1
        }

    fun appendEvent(c: Connection, kind: String, payload: String, parents: List<Int>): EventRow {
        val eventId = "evt-${UUID.randomUUID()}"
        val createdAt = java.time.Instant.now().toString()
        c.prepareStatement("INSERT INTO events(event_id,kind,payload,parents,created_at) VALUES(?,?,?,?,?)")
            .use { p ->
                p.setString(1, eventId); p.setString(2, kind); p.setString(3, payload)
                p.setString(4, parents.joinToString(",")); p.setString(5, createdAt)
                p.executeUpdate()
            }
        val seq = c.createStatement().executeQuery("SELECT seq FROM events WHERE event_id='$eventId'").use {
            it.next(); it.getInt(1)
        }
        return EventRow(seq, eventId, kind, payload, parents.sorted(), createdAt)
    }

    fun events(): List<EventRow> = tx { c -> readEvents(c) }

    private fun readEvents(c: Connection): List<EventRow> {
        val out = mutableListOf<EventRow>()
        c.createStatement().executeQuery("SELECT seq,event_id,kind,payload,parents,created_at FROM events ORDER BY seq")
            .use { rs ->
                while (rs.next()) {
                    out += EventRow(
                        rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)?.takeIf { it.isNotEmpty() }?.split(",")?.map(String::toInt) ?: emptyList(),
                        rs.getString(6)
                    )
                }
            }
        return out
    }

    fun listSnapshots(): List<StoredSnapshot> = tx { c ->
        c.createStatement().executeQuery("SELECT snapshot_id, source_label, imported_seq FROM snapshots ORDER BY imported_seq, snapshot_id")
            .use { rs ->
                val ids = mutableListOf<Triple<String, String, Int>>()
                while (rs.next()) ids += Triple(rs.getString(1), rs.getString(2), rs.getInt(3))
                ids.map { (id, label, seq) -> StoredSnapshot(loadSnapshot(c, id), label, seq) }
            }
    }

    fun loadSnapshot(snapshotId: String): SnapshotDoc = tx { c -> loadSnapshot(c, snapshotId) }

    private fun loadSnapshot(c: Connection, id: String): SnapshotDoc {
        val classes = mutableListOf<ClassRec>()
        c.prepareStatement("SELECT class_id,name,super_name,renamed_from,fields_json FROM classes WHERE snapshot_id=? ORDER BY ord")
            .use { p ->
                p.setString(1, id)
                p.executeQuery().use { rs ->
                    while (rs.next()) classes += ClassRec(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        JsonFmt.decodeFromString(rs.getString(5))
                    )
                }
            }
        val objects = mutableListOf<ObjectRec>()
        c.prepareStatement("SELECT object_id,address,class_id,size_raw,size_unit,alloc_json FROM objects WHERE snapshot_id=? ORDER BY ord")
            .use { p ->
                p.setString(1, id)
                p.executeQuery().use { rs ->
                    while (rs.next()) objects += ObjectRec(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        Quantity(rs.getString(4), rs.getString(5)),
                        rs.getString(6)?.let { JsonFmt.decodeFromString<AllocSite>(it) }
                    )
                }
            }
        val edges = mutableListOf<EdgeRec>()
        c.prepareStatement("SELECT from_id,to_id,kind,name FROM edges WHERE snapshot_id=? ORDER BY ord")
            .use { p ->
                p.setString(1, id)
                p.executeQuery().use { rs ->
                    while (rs.next()) edges += EdgeRec(rs.getString(1), rs.getString(2), EdgeKind.valueOf(rs.getString(3)), rs.getString(4))
                }
            }
        val roots = mutableListOf<RootRec>()
        c.prepareStatement("SELECT object_id,root_type FROM roots WHERE snapshot_id=? ORDER BY ord")
            .use { p ->
                p.setString(1, id)
                p.executeQuery().use { rs ->
                    while (rs.next()) roots += RootRec(rs.getString(1), RootType.valueOf(rs.getString(2)))
                }
            }
        val blocks = mutableListOf<IncompleteBlock>()
        c.prepareStatement("SELECT position,reason,raw_hex FROM incomplete_blocks WHERE snapshot_id=? ORDER BY ord")
            .use { p ->
                p.setString(1, id)
                p.executeQuery().use { rs ->
                    while (rs.next()) blocks += IncompleteBlock(rs.getString(1), rs.getString(2), rs.getString(3))
                }
            }
        c.prepareStatement("SELECT parser_version,captured_at,format_version FROM snapshots WHERE snapshot_id=?").use { p ->
            p.setString(1, id)
            p.executeQuery().use { rs ->
                rs.next()
                return@use SnapshotDoc(
                    format = "heaproom-snapshot",
                    formatVersion = rs.getInt(3),
                    snapshotId = id,
                    parserVersion = rs.getString(1),
                    capturedAt = rs.getString(2),
                    classes = classes, objects = objects, edges = edges, roots = roots,
                    incompleteBlocks = blocks
                )
            }
        }
    }

    fun diagnostics(snapshotId: String): List<Diagnostic> = tx { c ->
        val out = mutableListOf<Diagnostic>()
        c.prepareStatement("SELECT position,severity,message FROM diagnostics WHERE snapshot_id=? ORDER BY ord")
            .use { p ->
                p.setString(1, snapshotId)
                p.executeQuery().use { rs ->
                    while (rs.next()) out += Diagnostic(rs.getString(1), rs.getString(2), rs.getString(3))
                }
            }
        out
    }

    fun putParamSet(c: Connection, p: ParamRow) {
        c.prepareStatement("INSERT OR REPLACE INTO param_sets VALUES(?,?,?,?)").use { st ->
            st.setString(1, p.version); st.setString(2, p.weakPolicy.name)
            st.setInt(3, p.nodeBudget); st.setInt(4, p.eventSeq)
            st.executeUpdate()
        }
    }

    fun paramSets(): List<ParamRow> = tx { c -> readParams(c) }

    private fun readParams(c: Connection): List<ParamRow> {
        val out = mutableListOf<ParamRow>()
        c.createStatement().executeQuery("SELECT version,weak_policy,node_budget,event_seq FROM param_sets ORDER BY event_seq")
            .use { rs ->
                while (rs.next()) out += ParamRow(
                    rs.getString(1), WeakPolicy.valueOf(rs.getString(2)), rs.getInt(3), rs.getInt(4)
                )
            }
        return out
    }

    fun paramAt(atSeq: Int?): ParamRow? {
        val params = paramSets()
        return params.filter { atSeq == null || it.eventSeq <= atSeq }.maxByOrNull { it.eventSeq }
    }

    fun putComputation(c: Connection, row: ComputationRow) {
        c.prepareStatement(
            "INSERT OR REPLACE INTO computations VALUES(?,?,?,?,?,?,?,?,?,?)"
        ).use { st ->
            st.setString(1, row.computeId); st.setString(2, row.snapshotId)
            st.setString(3, row.inputDigest); st.setString(4, row.paramVersion)
            st.setInt(5, row.parentEventSeq); st.setString(6, row.status)
            st.setString(7, row.resultJson); st.setInt(8, row.validFromEvent)
            if (row.validUntilEvent == null) st.setNull(9, java.sql.Types.INTEGER) else st.setInt(9, row.validUntilEvent)
            st.setString(10, row.invalidationReason)
            st.executeUpdate()
        }
    }

    fun computations(snapshotId: String): List<ComputationRow> = tx { c ->
        val out = mutableListOf<ComputationRow>()
        c.prepareStatement("SELECT compute_id,snapshot_id,input_digest,param_version,parent_event_seq,status,result_json,valid_from_event,valid_until_event,invalidation_reason FROM computations WHERE snapshot_id=? ORDER BY parent_event_seq, compute_id")
            .use { p ->
                p.setString(1, snapshotId)
                p.executeQuery().use { rs ->
                    while (rs.next()) {
                        val until = rs.getObject(9)
                        out += ComputationRow(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                            if (until == null) null else (until as Number).toInt(),
                            rs.getString(10)
                        )
                    }
                }
            }
        out
    }

    fun putDecision(c: Connection, d: DecisionRow) {
        c.prepareStatement("INSERT OR REPLACE INTO decisions VALUES(?,?,?,?,?,?,?)").use { st ->
            st.setString(1, d.decisionId); st.setString(2, d.candidateId); st.setString(3, d.snapshotId)
            st.setString(4, d.action); st.setString(5, d.rationale); st.setInt(6, d.eventSeq)
            if (d.retractedByEvent == null) st.setNull(7, java.sql.Types.INTEGER) else st.setInt(7, d.retractedByEvent)
            st.executeUpdate()
        }
    }

    fun retractDecision(c: Connection, decisionId: String, atEvent: Int) {
        c.prepareStatement("UPDATE decisions SET retracted_by_event=? WHERE decision_id=?").use { st ->
            st.setInt(1, atEvent); st.setString(2, decisionId); st.executeUpdate()
        }
    }

    fun decisions(): List<DecisionRow> = tx { c ->
        val out = mutableListOf<DecisionRow>()
        c.createStatement().executeQuery("SELECT decision_id,candidate_id,snapshot_id,action,rationale,event_seq,retracted_by_event FROM decisions ORDER BY event_seq")
            .use { rs ->
                while (rs.next()) {
                    val r = rs.getObject(7)
                    out += DecisionRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), if (r == null) null else (r as Number).toInt()
                    )
                }
            }
        out
    }

fun closeAndDelete() {
    if (dbPath != ":memory:") java.io.File(dbPath).delete()
}
