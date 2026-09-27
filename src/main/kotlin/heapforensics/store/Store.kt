package heapforensics.store

import heapforensics.analysis.AnalysisParams
import heapforensics.analysis.AnalysisResult
import heapforensics.domain.sha256
import heapforensics.ingest.ParseOutcome
import heapforensics.ingest.SnapshotParser
import heapforensics.domain.Diagnostic
import heapforensics.domain.RawSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant

val JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 机器可读取事件：类型 + JSON 负载 + 因果父级。 */
data class Event(
    val id: String,
    val seq: Long,
    val at: String,
    val type: String,
    val payload: JsonObject,
    val parents: List<String>
)

data class SnapshotRow(
    val snapshotId: String,
    val digest: String,
    val sourceName: String,
    val capturedAt: String,
    val parserVersion: String,
    val eventId: String?,
    val seq: Long
)

data class AnalysisRow(
    val id: String,
    val snapshotId: String,
    val digest: String,
    val paramVersion: String,
    val paramsJson: String,
    val outcome: String,
    val resultJson: String?,
    val outcomeDetail: String?,
    val eventId: String,
    val supersededBy: String?,
    val invalidationReason: String?,
    val seq: Long
) {
    fun params(): AnalysisParams = JSON.decodeFromString(paramsJson)
    fun result(): AnalysisResult? = resultJson?.let { JSON.decodeFromString(it) }
}

data class DecisionRow(
    val id: String,
    val target: String,
    val verdict: String,
    val rationale: String,
    val author: String,
    val eventId: String,
    val seq: Long
)

class Store(path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
            migrate(st)
        }
        recoverIncompleteBatches()
    }

    private fun migrate(st: Statement) {
        st.executeUpdate("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        st.executeUpdate("CREATE TABLE IF NOT EXISTS batches(batch_id TEXT PRIMARY KEY, status TEXT NOT NULL, started_at TEXT NOT NULL, note TEXT)")
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS snapshots(
                 snapshot_id TEXT NOT NULL, input_digest TEXT NOT NULL, source_name TEXT NOT NULL,
                 captured_at TEXT NOT NULL, parser_version TEXT NOT NULL, raw_json TEXT NOT NULL,
                 event_id TEXT, batch_id TEXT NOT NULL, seq INTEGER NOT NULL,
                 PRIMARY KEY(snapshot_id, input_digest))"""
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS events(
                 event_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, at TEXT NOT NULL,
                 type TEXT NOT NULL, payload TEXT NOT NULL, parents TEXT NOT NULL, batch_id TEXT NOT NULL)"""
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS analyses(
                 analysis_id TEXT PRIMARY KEY, snapshot_id TEXT NOT NULL, input_digest TEXT NOT NULL,
                 param_version TEXT NOT NULL, params_json TEXT NOT NULL, outcome TEXT NOT NULL,
                 result_json TEXT, outcome_detail TEXT, event_id TEXT NOT NULL,
                 superseded_by TEXT, invalidation_reason TEXT, batch_id TEXT NOT NULL, seq INTEGER NOT NULL)"""
        )
        st.executeUpdate(
            """CREATE TABLE IF NOT EXISTS decisions(
                 decision_id TEXT PRIMARY KEY, target TEXT NOT NULL, verdict TEXT NOT NULL,
                 rationale TEXT NOT NULL, author TEXT NOT NULL, event_id TEXT NOT NULL,
                 batch_id TEXT NOT NULL, seq INTEGER NOT NULL)"""
        )
    }

    // ---------- 低层助手 ----------

    fun exec(sql: String, vararg params: Any?) {
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeUpdate()
        }
    }

    fun <T> query(sql: String, vararg params: Any?, map: (java.sql.ResultSet) -> T): List<T> {
        conn.prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            val rs = ps.executeQuery()
            val out = mutableListOf<T>()
            while (rs.next()) out += map(rs)
            return out
        }
    }

    fun <T> queryOne(sql: String, vararg params: Any?, map: (java.sql.ResultSet) -> T): T? =
        query(sql, *params, map = map).firstOrNull()

    private fun nextSeq(table: String): Long =
        queryOne("SELECT COALESCE(MAX(seq),0)+1 FROM $table") { it.getLong(1) }!!

    /** 所有变更都在批次内完成：批次行先记为 in_progress，提交前标记 done。 */
    fun <T> inBatch(note: String, block: (batchId: String) -> T): T {
        val auto = conn.autoCommit
        conn.autoCommit = false
        try {
            val batchId = "batch-" + sha256(note + System.nanoTime()).take(12)
            exec("INSERT INTO batches(batch_id,status,started_at,note) VALUES(?,?,?,?)",
                batchId, "in_progress", Instant.now().toString(), note)
            val result = block(batchId)
            exec("UPDATE batches SET status='done' WHERE batch_id=?", batchId)
            conn.commit()
            return result
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = auto
        }
    }

    /** 测试专用：模拟崩溃的写入方——批次与部分行已提交，但批次永远停在 in_progress。 */
    fun simulateCrashedBatch(note: String, writes: (batchId: String) -> Unit): String {
        val batchId = "batch-crash-" + sha256(note + System.nanoTime()).take(12)
        exec("INSERT INTO batches(batch_id,status,started_at,note) VALUES(?,?,?,?)",
            batchId, "in_progress", Instant.now().toString(), note)
        writes(batchId)
        return batchId
    }

    /** 启动恢复：回滚所有半写批次，并记录机器可读恢复事件。 */
    fun recoverIncompleteBatches(): List<String> {
        val crashed = query("SELECT batch_id FROM batches WHERE status='in_progress'") { it.getString(1) }
        if (crashed.isEmpty()) return emptyList()
        for (b in crashed) {
            exec("DELETE FROM events WHERE batch_id=?", b)
            exec("DELETE FROM snapshots WHERE batch_id=?", b)
            exec("DELETE FROM analyses WHERE batch_id=?", b)
            exec("DELETE FROM decisions WHERE batch_id=?", b)
            exec("UPDATE batches SET status='recovered' WHERE batch_id=?", b)
        }
        inBatch("startup-recovery") { bid ->
            appendEvent(
                "db.recovered",
                kotlinx.serialization.json.buildJsonObject {
                    put("rolledBackBatches", JsonArray(crashed.map { JsonPrimitive(it) }))
                    put("reason", JsonPrimitive("检测到半写批次，已回滚未完成写入并恢复一致状态"))
                },
                emptyList(),
                bid
            )
        }
        return crashed
    }

    // ---------- 事件 ----------

    fun appendEvent(type: String, payload: JsonObject, parents: List<String>, batchId: String): Event {
        val seq = nextSeq("events")
        val id = "evt-%04d".format(seq)
        val at = Instant.now().toString()
        exec(
            "INSERT INTO events(event_id,seq,at,type,payload,parents,batch_id) VALUES(?,?,?,?,?,?,?)",
            id, seq, at, type, payload.toString(),
            JsonArray(parents.map { JsonPrimitive(it) }).toString(), batchId
        )
        return Event(id, seq, at, type, payload, parents)
    }

    fun listEvents(): List<Event> = query("SELECT * FROM events ORDER BY seq") { rs ->
        Event(
            rs.getString("event_id"), rs.getLong("seq"), rs.getString("at"), rs.getString("type"),
            JSON.parseToJsonElement(rs.getString("payload")) as JsonObject,
            (JSON.parseToJsonElement(rs.getString("parents")) as JsonArray).map { it.jsonPrimitiveOrNull() }.filterNotNull()
        )
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): String? =
        (this as? JsonPrimitive)?.content

    fun event(id: String): Event? = listEvents().firstOrNull { it.id == id }

    // ---------- 快照 ----------

    fun putSnapshot(snapshot: RawSnapshot, rawText: String, digest: String, eventId: String?, batchId: String): Boolean {
        val seq = nextSeq("snapshots")
        val changed = conn.prepareStatement(
            "INSERT OR IGNORE INTO snapshots(snapshot_id,input_digest,source_name,captured_at,parser_version,raw_json,event_id,batch_id,seq) VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, snapshot.snapshotId); ps.setString(2, digest); ps.setString(3, snapshot.sourceName)
            ps.setString(4, snapshot.capturedAt); ps.setString(5, snapshot.parserVersion)
            ps.setString(6, rawText); ps.setString(7, eventId); ps.setString(8, batchId); ps.setLong(9, seq)
            ps.executeUpdate()
        }
        return changed > 0
    }

    fun snapshotRows(): List<SnapshotRow> = query("SELECT * FROM snapshots ORDER BY seq") { rs ->
        SnapshotRow(
            rs.getString("snapshot_id"), rs.getString("input_digest"), rs.getString("source_name"),
            rs.getString("captured_at"), rs.getString("parser_version"), rs.getString("event_id"), rs.getLong("seq")
        )
    }

    fun hasSnapshot(snapshotId: String, digest: String? = null): Boolean =
        if (digest == null) queryOne("SELECT 1 FROM snapshots WHERE snapshot_id=? LIMIT 1", snapshotId) { 1 } != null
        else queryOne("SELECT 1 FROM snapshots WHERE snapshot_id=? AND input_digest=? LIMIT 1", snapshotId, digest) { 1 } != null

    fun snapshotDigests(snapshotId: String): List<String> =
        query("SELECT input_digest FROM snapshots WHERE snapshot_id=? ORDER BY seq", snapshotId) { it.getString(1) }

    /** 读取快照：重新解析保存的原始文本（原始表示保留，派生层可重现）。 */
    fun readSnapshot(snapshotId: String, digest: String? = null): RawSnapshot? {
        val row = if (digest == null)
            queryOne("SELECT raw_json, source_name FROM snapshots WHERE snapshot_id=? ORDER BY seq DESC", snapshotId) {
                it.getString(1) to it.getString(2)
            }
        else
            queryOne("SELECT raw_json, source_name FROM snapshots WHERE snapshot_id=? AND input_digest=?", snapshotId, digest) {
                it.getString(1) to it.getString(2)
            }
        row ?: return null
        return when (val outcome = SnapshotParser.parse(row.first, row.second)) {
            is ParseOutcome.Ok -> outcome.snapshot
            is ParseOutcome.Corrupt -> null
        }
    }

    fun rawText(snapshotId: String, digest: String? = null): String? =
        if (digest == null)
            queryOne("SELECT raw_json FROM snapshots WHERE snapshot_id=? ORDER BY seq DESC", snapshotId) { it.getString(1) }
        else
            queryOne("SELECT raw_json FROM snapshots WHERE snapshot_id=? AND input_digest=?", snapshotId, digest) { it.getString(1) }

    fun snapshotImportEvent(snapshotId: String, digest: String): String? =
        queryOne("SELECT event_id FROM snapshots WHERE snapshot_id=? AND input_digest=?", snapshotId, digest) { it.getString(1) }

    // ---------- 分析 ----------

    fun analysisId(snapshotId: String, digest: String, paramVersion: String): String =
        "ana-" + sha256("$snapshotId|$digest|$paramVersion").take(16)

    fun putAnalysis(
        snapshotId: String, digest: String, params: AnalysisParams,
        outcome: String, resultJson: String?, outcomeDetail: String?,
        eventId: String, batchId: String
    ): AnalysisRow {
        val id = analysisId(snapshotId, digest, params.paramVersion)
        val seq = nextSeq("analyses")
        conn.prepareStatement(
            """INSERT OR IGNORE INTO analyses(analysis_id,snapshot_id,input_digest,param_version,params_json,outcome,result_json,outcome_detail,event_id,superseded_by,invalidation_reason,batch_id,seq)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, snapshotId); ps.setString(3, digest)
            ps.setString(4, params.paramVersion); ps.setString(5, JSON.encodeToString(AnalysisParams.serializer(), params))
            ps.setString(6, outcome); ps.setString(7, resultJson); ps.setString(8, outcomeDetail)
            ps.setString(9, eventId); ps.setString(10, null); ps.setString(11, null)
            ps.setString(12, batchId); ps.setLong(13, seq)
            ps.executeUpdate()
        }
        return analysis(id)!!
    }

    fun analysis(id: String): AnalysisRow? =
        queryOne("SELECT * FROM analyses WHERE analysis_id=?", id, ::analysisRow)

    fun analyses(snapshotId: String? = null): List<AnalysisRow> =
        if (snapshotId == null) query("SELECT * FROM analyses ORDER BY seq", map = ::analysisRow)
        else query("SELECT * FROM analyses WHERE snapshot_id=? ORDER BY seq", snapshotId, ::analysisRow)

    private fun analysisRow(rs: java.sql.ResultSet) = AnalysisRow(
        rs.getString("analysis_id"), rs.getString("snapshot_id"), rs.getString("input_digest"),
        rs.getString("param_version"), rs.getString("params_json"), rs.getString("outcome"),
        rs.getString("result_json"), rs.getString("outcome_detail"), rs.getString("event_id"),
        rs.getString("superseded_by"), rs.getString("invalidation_reason"), rs.getLong("seq")
    )

    /** 失效标记：记录有效区间终点与失效原因，历史结论不被覆盖。 */
    fun invalidateAnalyses(
        snapshotId: String, digest: String?, keepAnalysisId: String?,
        reason: String, byEvent: String, batchId: String
    ): List<String> {
        val rows = analyses(snapshotId).filter {
            it.supersededBy == null && it.id != keepAnalysisId && (digest == null || it.digest == digest)
        }
        for (row in rows) {
            exec("UPDATE analyses SET superseded_by=?, invalidation_reason=? WHERE analysis_id=?",
                byEvent, reason, row.id)
        }
        return rows.map { it.id }
    }

    fun distinctParamVersions(snapshotId: String): List<String> =
        query("SELECT DISTINCT param_version FROM analyses WHERE snapshot_id=? ORDER BY param_version", snapshotId) { it.getString(1) }

    // ---------- 决定 ----------

    fun putDecision(target: String, verdict: String, rationale: String, author: String, eventId: String, batchId: String): DecisionRow {
        val seq = nextSeq("decisions")
        val id = "dec-%04d".format(seq)
        exec(
            "INSERT INTO decisions(decision_id,target,verdict,rationale,author,event_id,batch_id,seq) VALUES(?,?,?,?,?,?,?,?)",
            id, target, verdict, rationale, author, eventId, batchId, seq
        )
        return DecisionRow(id, target, verdict, rationale, author, eventId, seq)
    }

    fun decisions(): List<DecisionRow> = query("SELECT * FROM decisions ORDER BY seq") { rs ->
        DecisionRow(
            rs.getString("decision_id"), rs.getString("target"), rs.getString("verdict"),
            rs.getString("rationale"), rs.getString("author"), rs.getString("event_id"), rs.getLong("seq")
        )
    }

    // ---------- meta ----------

    fun meta(key: String): String? = queryOne("SELECT value FROM meta WHERE key=?", key) { it.getString(1) }
    fun setMeta(key: String, value: String) {
        exec("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value)
    }
    fun allMeta(): Map<String, String> =
        query("SELECT key,value FROM meta ORDER BY key") { it.getString(1) to it.getString(2) }.toMap()

    fun batches(): List<Map<String, String?>> = query("SELECT * FROM batches ORDER BY started_at") { rs ->
        mapOf(
            "batch_id" to rs.getString("batch_id"), "status" to rs.getString("status"),
            "started_at" to rs.getString("started_at"), "note" to rs.getString("note")
        )
    }

    override fun close() {
        conn.close()
    }
}
