package forensics.heapsnapshot.storage

import forensics.heapsnapshot.engine.AnalysisParams
import forensics.heapsnapshot.engine.AnalysisResult
import forensics.heapsnapshot.engine.ClassGroupDiff
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.UUID

@Serializable
data class ProjectRow(
    val id: String,
    val name: String,
    val casePackage: String?,
    val createdAt: String,
    val tipEventId: String?,
)

class Repository(private val db: Database) {
    private val conn get() = db.connection
    private val json get() = db.json

    // ---- 半写恢复 ----------------------------------------------------------
    /**
     * 启动时恢复：回滚任何未完成事务，检查 write_journal 中遗留的
     * APPEND 阶段记录 —— 该项目的追加写入视为崩溃中断，清理半成品。
     */
    fun recoverHalfWrites(): List<String> {
        val recovered = mutableListOf<String>()
        conn.rollback()
        val dangling = conn.prepareStatement("SELECT tx_id, project_id, stage FROM write_journal").executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getString(3)))
            }
        }
        val danglingTxEvent = HashMap<String, String?>()
        dangling.forEach { (txId, projectId, stage) ->
            // 崩溃在 EVENTS_APPENDED 之后、COMMITTED 之前：
            // 删除该事务写入的 event / snapshot / blob 半成品，保留可解析状态。
            val eventId = conn.prepareStatement("SELECT event_id FROM write_journal WHERE tx_id=?")
                .apply { setString(1, txId) }.executeQuery().use { if (it.next()) it.getString(1) else null }
            if (stage == "EVENTS_APPENDED" && eventId != null) {
                conn.prepareStatement("DELETE FROM events WHERE project_id=? AND event_id=?")
                    .apply { setString(1, projectId); setString(2, eventId) }.executeUpdate()
                conn.prepareStatement("DELETE FROM snapshots WHERE project_id=? AND import_seq > (SELECT COALESCE(MAX(seq),0) FROM events WHERE project_id=?)")
                    .apply { setString(1, projectId); setString(2, projectId) }.executeUpdate()
                conn.prepareStatement("DELETE FROM analysis_blobs WHERE project_id=? AND event_id=?")
                    .apply { setString(1, projectId); setString(2, eventId) }.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM write_journal WHERE tx_id=?").apply { setString(1, txId) }.executeUpdate()
            recovered.add(projectId)
        }
        conn.commit()
        return recovered
    }

    fun listProjects(): List<ProjectRow> {
        val rows = mutableListOf<ProjectRow>()
        conn.prepareStatement("SELECT id,name,case_package,created_at,tip_event_id FROM projects ORDER BY created_at,id").executeQuery().use { rs ->
            while (rs.next()) rows.add(ProjectRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
        }
        return rows
    }

    fun projectExists(id: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM projects WHERE id=?").apply { setString(1, id) }.executeQuery().use { it.next() }

    fun createProject(id: String, name: String, casePackage: String?): ProjectRow {
        val at = Instant.now().toString()
        conn.prepareStatement("INSERT INTO projects(id,name,case_package,created_at,tip_event_id) VALUES(?,?,?,?,NULL)")
            .apply { setString(1, id); setString(2, name); setString(3, casePackage); setString(4, at) }.executeUpdate()
        conn.commit()
        return ProjectRow(id, name, casePackage, at, null)
    }

    fun insertSnapshot(projectId: String, snapshotId: String, identity: String, raw: String, importSeq: Long) {
        conn.prepareStatement("INSERT INTO snapshots(project_id,snapshot_id,identity,raw,import_seq) VALUES(?,?,?,?,?)")
            .apply {
                setString(1, projectId); setString(2, snapshotId); setString(3, identity)
                setString(4, raw); setLong(5, importSeq)
            }.executeUpdate()
    }

    fun rawSnapshot(snapshotId: String): StoredSnapshot? =
        conn.prepareStatement("SELECT snapshot_id,identity,raw,import_seq FROM snapshots WHERE snapshot_id=?")
            .apply { setString(1, snapshotId) }.executeQuery().use { rs ->
                if (!rs.next()) null else StoredSnapshot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))
            }

    fun listSnapshots(projectId: String): List<StoredSnapshot> =
        conn.prepareStatement("SELECT snapshot_id,identity,raw,import_seq FROM snapshots WHERE project_id=? ORDER BY import_seq,snapshot_id")
            .apply { setString(1, projectId) }.executeQuery().use { rs ->
                buildList { while (rs.next()) add(StoredSnapshot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))) }
            }

    fun nextSeq(projectId: String): Long {
        conn.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 AS n FROM events WHERE project_id=?").apply {
            setString(1, projectId)
        }.executeQuery().use { rs ->
            return if (rs.next()) rs.getLong("n") else 1L
        }
    }

    /**
     * 带显式两阶段日志的事件追加：
     *  1) BEGIN -> journal(PREPARED)
     *  2) 写行   -> journal(EVENTS_APPENDED)
     *  3) 更新 tip + journal(COMMITTED) -> COMMIT
     * 崩溃在 2 后、3 前可由 recoverHalfWrites 清理。
     */
    fun appendEventWithSnapshots(
        projectId: String,
        event: StoredEvent,
        newSnapshots: List<StoredSnapshot>,
        analysis: Pair<String, AnalysisResult>? = null,
        diff: Triple<String, String, ClassGroupDiff>? = null,
        simulateCrashAfterAppend: Boolean = false,
    ) {
        val tx = "tx-${UUID.randomUUID()}"
        runCatching {
            conn.prepareStatement("INSERT INTO write_journal(tx_id,project_id,stage,created_at) VALUES(?,?,?,?)")
                .apply { setString(1, tx); setString(2, projectId); setString(3, "PREPARED"); setString(4, Instant.now().toString()) }
                .executeUpdate()
            conn.commit()

            newSnapshots.forEach { s -> insertSnapshot(projectId, s.snapshotId, s.identity, s.raw, event.seq) }

            conn.prepareStatement(
                "INSERT INTO events(project_id,event_id,seq,parent_event_id,branch,at,actor,type,payload_json) VALUES(?,?,?,?,?,?,?,?,?)",
            ).apply {
                setString(1, projectId); setString(2, event.eventId); setLong(3, event.seq)
                setString(4, event.parentEventId); setString(5, event.branch); setString(6, event.at)
                setString(7, event.actor); setString(8, event.type); setString(9, json.encodeToString(event.payload))
            }.executeUpdate()

            analysis?.let { (analysisEventId, result) ->
                conn.prepareStatement(
                    "INSERT INTO analysis_blobs(project_id,event_id,snapshot_id,params_version,result_json) VALUES(?,?,?,?,?)",
                ).apply {
                    setString(1, projectId); setString(2, analysisEventId); setString(3, result.snapshotIdentity)
                    setLong(4, result.paramsVersion.toLong()); setString(5, json.encodeToString(result))
                }.executeUpdate()
            }
            diff?.let { (id, afterId, d) ->
                conn.prepareStatement(
                    "INSERT INTO diff_blobs(project_id,id,before_id,after_id,result_json) VALUES(?,?,?,?,?)",
                ).apply {
                    setString(1, projectId); setString(2, id); setString(3, d.snapshotBefore)
                    setString(4, d.snapshotAfter); setString(5, json.encodeToString(d))
                }.executeUpdate()
            }

            conn.prepareStatement("UPDATE write_journal SET stage='EVENTS_APPENDED', event_id=? WHERE tx_id=?")
                .apply { setString(1, event.eventId); setString(2, tx) }.executeUpdate()
            conn.commit()

            if (simulateCrashAfterAppend) throw SimulatedHalfWrite("在 EVENTS_APPENDED 后、COMMITTED 前模拟崩溃")

            conn.prepareStatement("UPDATE projects SET tip_event_id=? WHERE id=?")
                .apply { setString(1, event.eventId); setString(2, projectId) }.executeUpdate()
            conn.prepareStatement("UPDATE write_journal SET stage='COMMITTED' WHERE tx_id=?")
                .apply { setString(1, tx) }.executeUpdate()
            conn.commit()
        }.onFailure {
            conn.rollback()
            if (it is SimulatedHalfWrite) throw it
        }
    }

    fun events(projectId: String, branch: String? = null): List<StoredEvent> {
        val sql = "SELECT event_id,seq,parent_event_id,branch,at,actor,type,payload_json FROM events WHERE project_id=?" +
            if (branch != null) " AND branch=?" else ""
        return conn.prepareStatement(sql).apply {
            setString(1, projectId); if (branch != null) setString(2, branch)
        }.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        StoredEvent(
                            eventId = rs.getString(1), seq = rs.getLong(2),
                            parentEventId = rs.getString(3), branch = rs.getString(4),
                            at = rs.getString(5), actor = rs.getString(6), type = rs.getString(7),
                            payload = json.decodeFromString<EventPayload>(rs.getString(8)),
                        ),
                    )
                }
            }.sortedWith(compareBy({ it.branch }, { it.seq }))
        }
    }

    fun tipEventId(projectId: String): String? =
        conn.prepareStatement("SELECT tip_event_id FROM projects WHERE id=?").apply { setString(1, projectId) }
            .executeQuery().use { if (it.next()) it.getString(1) else null }

    fun setTip(projectId: String, eventId: String?) {
        conn.prepareStatement("UPDATE projects SET tip_event_id=? WHERE id=?")
            .apply { setString(1, eventId); setString(2, projectId) }.executeUpdate()
        conn.commit()
    }

    fun analysisResult(eventId: String): AnalysisResult? =
        conn.prepareStatement("SELECT result_json FROM analysis_blobs WHERE event_id=?").apply { setString(1, eventId) }
            .executeQuery().use { if (it.next()) json.decodeFromString<AnalysisResult>(it.getString(1)) else null }

    fun allAnalysis(projectId: String): List<Pair<String, AnalysisResult>> =
        conn.prepareStatement("SELECT event_id,result_json FROM analysis_blobs WHERE project_id=? ORDER BY event_id")
            .apply { setString(1, projectId) }.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to json.decodeFromString<AnalysisResult>(rs.getString(2))) }
            }

    fun diffFor(projectId: String, before: String, after: String): ClassGroupDiff? =
        conn.prepareStatement("SELECT result_json FROM diff_blobs WHERE project_id=? AND before_id=? AND after_id=?")
            .apply { setString(1, projectId); setString(2, before); setString(3, after) }.executeQuery()
            .use { if (it.next()) json.decodeFromString<ClassGroupDiff>(it.getString(1)) else null }

    fun saveParams(projectId: String, params: AnalysisParams) {
        // 参数修订全部通过事件历史保存；这里不做旁路写入。
    }
}

class SimulatedHalfWrite(message: String) : RuntimeException(message)
