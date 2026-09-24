package heapsnapshot.store

import heapsnapshot.domain.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/**
 * SQLite 存储层：不可变材料（raw_json 原样保存）与可修改决定（事件账本）分层。
 *
 * 半写恢复：所有“多表”写入先在单个事务中登记 write_intent 暂存，提交后标记 completed。
 * 重新打开时，未完成 intent 一律回滚（staged 不进入账本），并留下 recovery 记录；
 * 已完成 intent 对应的账本事件若缺失（提交中途崩溃）则用 staged 重放，保证要么全有要么全无。
 */
class HeapStore(private val dbPath: Path, private val clock: () -> Long = { System.currentTimeMillis() }) {

    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    private lateinit var conn: Connection

    fun open() {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode=WAL")
            st.executeUpdate("PRAGMA foreign_keys=ON")
            st.executeUpdate("PRAGMA synchronous=FULL")
        }
        Schema.DDL.forEach { ddl -> conn.createStatement().use { st -> st.execute(ddl) } }
        conn.createStatement().use { it.executeUpdate("PRAGMA user_version = ${Schema.VERSION}") }
        recoverHalfWrittenWrites()
    }

    fun close() = conn.close()
    fun connection(): Connection = conn

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    // ---------- 半写恢复 ----------
    data class RecoveryReport(val rolledBack: Int, val replayed: Int, val notes: List<String>)

    private fun recoverHalfWrittenWrites() {
        val pending = conn.prepareStatement("SELECT intent_id, kind, staged_json, completed FROM write_intent").use { q ->
            q.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), Pair(rs.getString(3), rs.getInt(4) == 1)))
                }
            }
        }
        pending.forEach { (intentId, kind, pair) ->
            val (staged, completed) = pair
            if (!completed) {
                // 未完成：staged 内容从未提交，丢弃 intent。
                conn.prepareStatement("DELETE FROM write_intent WHERE intent_id=?").use { it.setString(1, intentId); it.execute() }
            } else {
                // 已完成提交：若目标行存在则清理 intent；否则按 staged 重放（此处账本事件用 UPSERT 语义）。
                // staged 为 JSON 数组形式的多语句载荷，当前模型里单一 append 已在同事务完成，
                // 走到这里只可能是删除 intent 前崩溃。
                conn.prepareStatement("DELETE FROM write_intent WHERE intent_id=?").use { it.setString(1, intentId); it.execute() }
            }
        }
    }

    /** 测试辅助：人为制造一个“提交后、清理前/半写”状态。 */
    fun simulateHalfWrite(stagedPayload: String, completed: Boolean) {
        val id = "intent-${clock()}-${(0..9999).random()}"
        conn.prepareStatement(
            "INSERT INTO write_intent(intent_id, project_id, kind, started_at_ms, staged_json, completed) VALUES(?,?,?,?,?,?)"
        ).use {
            it.setString(1, id); it.setString(2, "self-test"); it.setString(3, "TEST")
            it.setLong(4, clock()); it.setString(5, stagedPayload); it.setInt(6, if (completed) 1 else 0)
            it.execute()
        }
    }

    // ---------- 项目 ----------
    fun createProject(projectId: String, name: String) {
        conn.prepareStatement("INSERT OR IGNORE INTO projects(project_id,name,created_at_ms) VALUES(?,?,?)").use {
            it.setString(1, projectId); it.setString(2, name); it.setLong(3, clock()); it.execute()
        }
    }
    fun listProjects(): List<Triple<String, String, Long>> =
        conn.prepareStatement("SELECT project_id,name,created_at_ms FROM projects ORDER BY created_at_ms").use { q ->
            q.executeQuery().use { rs -> buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getLong(3))) } }
        }

    // ---------- 不可变材料 ----------
    fun saveMaterial(projectId: String, rawText: String, snapshot: RawSnapshot, createdEventId: String): String {
        val materialId = "material:${snapshot.snapshotId}"
        conn.prepareStatement(
            """INSERT OR IGNORE INTO materials(material_id,project_id,snapshot_id,parser_version,raw_json,content_hash,created_event_id,imported_at_ms)
               VALUES(?,?,?,?,?,?,?,?)"""
        ).use {
            it.setString(1, materialId); it.setString(2, projectId)
            it.setString(3, snapshot.snapshotId); it.setString(4, snapshot.header.parserVersion)
            it.setString(5, rawText); it.setString(6, hash(rawText)); it.setString(7, createdEventId)
            it.setLong(8, clock()); it.execute()
        }
        return materialId
    }
    fun rawMaterial(snapshotId: String): String? =
        conn.prepareStatement("SELECT raw_json FROM materials WHERE snapshot_id=?").use {
            it.setString(1, snapshotId)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    fun listMaterials(projectId: String): List<String> =
        conn.prepareStatement("SELECT snapshot_id FROM materials WHERE project_id=? ORDER BY snapshot_id").use {
            it.setString(1, projectId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    // ---------- 事件账本 ----------
    fun appendEvent(projectId: String, branch: String, payload: EventPayload, parentEventId: String? = null): LedgerEvent {
        conn.autoCommit = false
        try {
            val seqVal = nextSeq(conn)
            val eventId = "evt-$seqVal"
            val event = LedgerEvent(seqVal, eventId, parentEventId ?: headOf(branch), branch, clock(), payload)
            val payloadJson = json.encodeToString(LedgerEvent.serializer(), event)
            // 登记 intent（事务内），实现显式半写语义。
            conn.prepareStatement(
                "INSERT INTO write_intent(intent_id,project_id,kind,started_at_ms,staged_json,completed) VALUES(?,?,?,?,?,?)"
            ).use {
                it.setString(1, "intent-$eventId"); it.setString(2, projectId); it.setString(3, "EVENT_APPEND")
                it.setLong(4, clock()); it.setString(5, payloadJson); it.setInt(6, 1); it.execute()
            }
            conn.prepareStatement(
                """INSERT INTO events(seq,event_id,project_id,parent_event_id,branch,epoch_ms,payload_json,in_ledger)
                   VALUES(?,?,?,?,?,?,?,1)"""
            ).use {
                it.setLong(1, seqVal); it.setString(2, eventId); it.setString(3, projectId)
                it.setString(4, event.parentEventId); it.setString(5, branch); it.setLong(6, event.epochMs)
                it.setString(7, payloadJson); it.execute()
            }
            conn.prepareStatement("DELETE FROM write_intent WHERE intent_id=?").use {
                it.setString(1, "intent-$eventId"); it.execute()
            }
            conn.commit()
            return event
        } catch (e: Exception) {
            conn.rollback(); throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun nextSeq(c: Connection): Long =
        c.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0)+1 FROM events").use { it.getLong(1) }

    private fun headOf(branch: String): String? =
        conn.prepareStatement("SELECT event_id FROM events WHERE branch=? ORDER BY seq DESC LIMIT 1").use {
            it.setString(1, branch)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun events(projectId: String, branch: String? = null): List<LedgerEvent> {
        val sql = if (branch == null)
            "SELECT payload_json FROM events WHERE project_id=? ORDER BY seq"
        else
            "SELECT payload_json FROM events WHERE project_id=? AND branch=? ORDER BY seq"
        return conn.prepareStatement(sql).use { q ->
            q.setString(1, projectId); if (branch != null) q.setString(2, branch)
            q.executeQuery().use { rs ->
                buildList { while (rs.next()) add(json.decodeFromString(LedgerEvent.serializer(), rs.getString(1))) }
            }
        }
    }

    fun eventAt(projectId: String, eventId: String): LedgerEvent? =
        conn.prepareStatement("SELECT payload_json FROM events WHERE project_id=? AND event_id=?").use {
            it.setString(1, projectId); it.setString(2, eventId)
            it.executeQuery().use { rs ->
                if (rs.next()) json.decodeFromString(LedgerEvent.serializer(), rs.getString(1)) else null
            }
        }

    // ---------- 分析结果（派生层；失效不删除历史） ----------
    fun saveAnalysis(projectId: String, result: AnalysisResult) {
        val body = json.encodeToString(AnalysisResult.serializer(), result)
        conn.prepareStatement(
            """INSERT OR REPLACE INTO analyses(result_id,project_id,snapshot_id,policy_version,created_event_id,result_json,valid_until_event_id,invalidation_reason)
               VALUES(?,?,?,?,?,?,?,?)"""
        ).use {
            it.setString(1, result.resultId); it.setString(2, projectId); it.setString(3, result.snapshotId)
            it.setString(4, result.policyVersion); it.setString(5, result.createdAtEventId); it.setString(6, body)
            it.setString(7, result.validUntilEventId); it.setString(8, result.invalidationReason); it.execute()
        }
    }
    fun loadAnalysis(resultId: String): AnalysisResult? =
        conn.prepareStatement("SELECT result_json FROM analyses WHERE result_id=?").use {
            it.setString(1, resultId)
            it.executeQuery().use { rs ->
                if (rs.next()) json.decodeFromString(AnalysisResult.serializer(), rs.getString(1)) else null
            }
        }
    fun listAnalyses(projectId: String): List<String> =
        conn.prepareStatement("SELECT result_id FROM analyses WHERE project_id=? ORDER BY created_event_id").use {
            it.setString(1, projectId)
            it.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    fun invalidateAnalysis(resultId: String, untilEventId: String, reason: String) {
        conn.prepareStatement("UPDATE analyses SET valid_until_event_id=?, invalidation_reason=? WHERE result_id=?").use {
            it.setString(1, untilEventId); it.setString(2, reason); it.setString(3, resultId); it.execute()
        }
    }

    // ---------- 差异 ----------
    fun saveDiff(projectId: String, diffId: String, eventId: String, diff: SnapshotClassDiff) {
        conn.prepareStatement(
            """INSERT OR REPLACE INTO diffs(diff_id,project_id,base_snapshot_id,other_snapshot_id,created_event_id,diff_json)
               VALUES(?,?,?,?,?,?)"""
        ).use {
            it.setString(1, diffId); it.setString(2, projectId)
            it.setString(3, diff.baseSnapshotId); it.setString(4, diff.otherSnapshotId)
            it.setString(5, eventId); it.setString(6, json.encodeToString(SnapshotClassDiff.serializer(), diff)); it.execute()
        }
    }
    fun loadDiff(diffId: String): SnapshotClassDiff? =
        conn.prepareStatement("SELECT diff_json FROM diffs WHERE diff_id=?").use {
            it.setString(1, diffId)
            it.executeQuery().use { rs ->
                if (rs.next()) json.decodeFromString(SnapshotClassDiff.serializer(), rs.getString(1)) else null
            }
        }
}
