package heapforensics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager

@Serializable
data class Material(val id: String, val kind: String, val content: String, val seq: Long)

@Serializable
data class Event(val seq: Long, val kind: String, val payload: String, val parents: String, val created: String)

@Serializable
data class Computation(
    val id: String, val kind: String, val inputDigest: String, val paramVersion: Int,
    val parents: String, val status: String, val result: String, val eventSeq: Long,
)

@Serializable
data class ExportBundle(
    val format: String = "heapforensics-export",
    val version: Int = 1,
    val materials: List<Material>,
    val events: List<Event>,
    val computations: List<Computation>,
)

class ImportException(message: String) : Exception(message)

/**
 * 分层存储：
 *  - materials：不可变材料（原始 JSON，内容寻址 digest）
 *  - events：机器可读事件流（导入/参数/补丁/决定/导出……），seq 保序
 *  - computations：计算结果，记录输入摘要、参数版本、父级依赖与触发事件
 */
class Store(path: String) : AutoCloseable {
    private val conn: Connection

    init {
        val f = java.io.File(path)
        f.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}")
        conn.autoCommit = true
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA foreign_keys=ON")
        exec("PRAGMA busy_timeout=5000")
        exec("""CREATE TABLE IF NOT EXISTS materials(
            id TEXT PRIMARY KEY, kind TEXT NOT NULL, content TEXT NOT NULL, seq INTEGER NOT NULL)""")
        exec("""CREATE TABLE IF NOT EXISTS events(
            seq INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, payload TEXT NOT NULL,
            parents TEXT NOT NULL DEFAULT '[]', created TEXT NOT NULL)""")
        exec("""CREATE TABLE IF NOT EXISTS computations(
            id TEXT PRIMARY KEY, kind TEXT NOT NULL, input_digest TEXT NOT NULL,
            param_version INTEGER NOT NULL, parents TEXT NOT NULL, status TEXT NOT NULL,
            result TEXT NOT NULL, event_seq INTEGER NOT NULL)""")
    }

    private fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }

    fun isEmpty(): Boolean = query("SELECT COUNT(*) FROM events") { it.getLong(1) }.first() == 0L

    // ---- materials（不可变）----
    fun putMaterial(id: String, kind: String, content: String, seq: Long) {
        val ps = conn.prepareStatement("INSERT OR IGNORE INTO materials(id,kind,content,seq) VALUES(?,?,?,?)")
        ps.use { it.setString(1, id); it.setString(2, kind); it.setString(3, content); it.setLong(4, seq); it.executeUpdate() }
    }

    fun material(id: String): Material? {
        val ps = conn.prepareStatement("SELECT id,kind,content,seq FROM materials WHERE id=?")
        ps.use {
            it.setString(1, id)
            val rs = it.executeQuery()
            return if (rs.next()) Material(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else null
        }
    }

    /** 清空全部数据（演示用重置；随后可重新回放案例包）。 */
    fun reset() {
        exec("DELETE FROM computations")
        exec("DELETE FROM events")
        exec("DELETE FROM materials")
        exec("DELETE FROM sqlite_sequence WHERE name='events'")
    }

    fun materials(): List<Material> =
        query("SELECT id,kind,content,seq FROM materials ORDER BY seq, id") {
            Material(it.getString(1), it.getString(2), it.getString(3), it.getLong(4))
        }

    // ---- events ----
    fun appendEvent(kind: String, payload: String, parents: List<Long> = emptyList()): Long {
        val ps = conn.prepareStatement("INSERT INTO events(kind,payload,parents,created) VALUES(?,?,?,?)")
        ps.use {
            it.setString(1, kind); it.setString(2, payload)
            it.setString(3, parents.joinToString(",", "[", "]"))
            it.setString(4, java.time.Instant.now().toString())
            it.executeUpdate()
        }
        return query("SELECT last_insert_rowid()") { rs -> rs.getLong(1) }.first()
    }

    fun events(): List<Event> =
        query("SELECT seq,kind,payload,parents,created FROM events ORDER BY seq") {
            Event(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }

    fun eventsUpTo(seq: Long): List<Event> =
        query("SELECT seq,kind,payload,parents,created FROM events WHERE seq<=? ORDER BY seq", listOf(seq)) {
            Event(it.getLong(1), it.getString(2), it.getString(3), it.getString(4), it.getString(5))
        }

    // ---- computations ----
    fun putComputation(c: Computation) {
        val ps = conn.prepareStatement("INSERT OR REPLACE INTO computations(id,kind,input_digest,param_version,parents,status,result,event_seq) VALUES(?,?,?,?,?,?,?,?)")
        ps.use {
            it.setString(1, c.id); it.setString(2, c.kind); it.setString(3, c.inputDigest)
            it.setInt(4, c.paramVersion); it.setString(5, c.parents); it.setString(6, c.status)
            it.setString(7, c.result); it.setLong(8, c.eventSeq); it.executeUpdate()
        }
    }

    fun computation(id: String): Computation? {
        val ps = conn.prepareStatement("SELECT id,kind,input_digest,param_version,parents,status,result,event_seq FROM computations WHERE id=?")
        ps.use {
            it.setString(1, id); val rs = it.executeQuery()
            return if (rs.next()) Computation(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)) else null
        }
    }

    fun computations(): List<Computation> =
        query("SELECT id,kind,input_digest,param_version,parents,status,result,event_seq FROM computations ORDER BY event_seq, id") {
            Computation(it.getString(1), it.getString(2), it.getString(3), it.getInt(4), it.getString(5), it.getString(6), it.getString(7), it.getLong(8))
        }

    // ---- 导出 / 导入（往返保持身份、顺序、来源关系）----
    fun exportBundle(): ExportBundle = ExportBundle(materials = materials(), events = events(), computations = computations())

    fun exportJson(): String = Json { prettyPrint = true }.encodeToString(ExportBundle.serializer(), exportBundle())

    /** 导入整体在一个事务中；任何一条失败即回滚（半写恢复）。 */
    fun importBundle(bundle: ExportBundle) {
        if (bundle.format != "heapforensics-export") throw ImportException("未知导出格式：${bundle.format}")
        conn.autoCommit = false
        try {
            val existingEvents = events().map { it.seq }.toSet()
            val existingMaterials = materials().map { it.id }.toSet()
            val existingComps = computations().map { it.id }.toSet()
            for (m in bundle.materials) {
                if (m.id in existingMaterials) throw ImportException("材料冲突：${m.id} 已存在")
                putMaterial(m.id, m.kind, m.content, m.seq)
            }
            for (e in bundle.events) {
                if (e.seq in existingEvents) throw ImportException("事件冲突：seq=${e.seq} 已存在")
                val ps = conn.prepareStatement("INSERT INTO events(seq,kind,payload,parents,created) VALUES(?,?,?,?,?)")
                ps.use { it.setLong(1, e.seq); it.setString(2, e.kind); it.setString(3, e.payload); it.setString(4, e.parents); it.setString(5, e.created); it.executeUpdate() }
            }
            for (c in bundle.computations) {
                if (c.id in existingComps) throw ImportException("计算记录冲突：${c.id} 已存在")
                putComputation(c)
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw if (e is ImportException) e else ImportException("导入失败已回滚：${e.message}")
        } finally {
            conn.autoCommit = true
        }
    }

    fun importJson(text: String) {
        val bundle = try {
            Json.decodeFromString(ExportBundle.serializer(), text)
        } catch (e: Exception) {
            throw ImportException("导出文件格式损坏：${e.message}")
        }
        importBundle(bundle)
    }

    /** 测试钩子：模拟半写（写入一半时崩溃），调用方验证回滚。 */
    fun importBundleWithCrash(bundle: ExportBundle, crashAfterEvents: Int) {
        conn.autoCommit = false
        try {
            for (m in bundle.materials) putMaterial(m.id, m.kind, m.content, m.seq)
            bundle.events.forEachIndexed { i, e ->
                if (i >= crashAfterEvents) throw RuntimeException("模拟崩溃：写入事件 $i 时断电")
                val ps = conn.prepareStatement("INSERT INTO events(seq,kind,payload,parents,created) VALUES(?,?,?,?,?)")
                ps.use { it.setLong(1, e.seq); it.setString(2, e.kind); it.setString(3, e.payload); it.setString(4, e.parents); it.setString(5, e.created); it.executeUpdate() }
            }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun counts(): Triple<Int, Int, Int> =
        Triple(
            query("SELECT COUNT(*) FROM materials") { it.getInt(1) }.first(),
            query("SELECT COUNT(*) FROM events") { it.getInt(1) }.first(),
            query("SELECT COUNT(*) FROM computations") { it.getInt(1) }.first(),
        )

    private fun <T> query(sql: String, args: List<Any> = emptyList(), map: (java.sql.ResultSet) -> T): List<T> {
        val ps = conn.prepareStatement(sql)
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.use {
            val rs = it.executeQuery()
            val out = mutableListOf<T>()
            while (rs.next()) out += map(rs)
            return out
        }
    }

    override fun close() = conn.close()
}
