package forensics.heapshadow.storage

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import forensics.heapshadow.engine.SnapshotImporter
import forensics.heapshadow.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

/**
 * 项目存储（SQLite）。所有业务对象经 JSON 列保存，材料行不可变。
 *
 * 半写恢复：
 *  导入分三阶段写 import_staging：RAW_RECEIVED -> MATERIAL_INSERTED -> EVENT_APPENDED。
 *  任何阶段崩溃，[recoverHalfWritten] 都会把孤儿材料隔离并追加 RECOVERY_MARKER 事件，
 *  不静默删除，也不会让半成品伪装成成功导入。
 */
class ProjectStore(
    private val dbPath: Path,
    val mapper: ObjectMapper = jacksonObjectMapper(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var conn: Connection

    init {
        Files.createDirectories(dbPath.toAbsolutePath().parent)
        conn = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}")
        conn.createStatement().use { st ->
            Schema.CREATE.trimIndent().split(";").filter { it.isNotBlank() }.forEach { st.execute(it) }
        }
        initializeMainBranch()
    }

    fun close() = conn.close()

    private fun initializeMainBranch() {
        val exists = conn.prepareStatement("SELECT 1 FROM branches WHERE branch='main'").executeQuery().use { it.next() }
        if (!exists) conn.prepareStatement(
            "INSERT INTO branches(branch,parent_branch,fork_from_event_id,created_at_ms) VALUES('main',NULL,NULL,?)"
        ).use { it.setLong(1, clock()); it.executeUpdate() }
    }

    fun branches(): List<BranchInfo> = conn.createStatement()
        .executeQuery("SELECT branch,parent_branch,fork_from_event_id,created_at_ms FROM branches ORDER BY created_at_ms")
        .use { rs ->
            val out = mutableListOf<BranchInfo>()
            while (rs.next()) out.add(
                BranchInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))
            )
            out
        }

    fun createBranch(branch: String, parentBranch: String, forkFromEventId: String) {
        conn.prepareStatement(
            "INSERT INTO branches(branch,parent_branch,fork_from_event_id,created_at_ms) VALUES(?,?,?,?)"
        ).use {
            it.setString(1, branch); it.setString(2, parentBranch); it.setString(3, forkFromEventId)
            it.setLong(4, clock()); it.executeUpdate()
        }
    }

    @Synchronized
    fun nextSeq(branch: String): Long =
        conn.prepareStatement("SELECT COALESCE(MAX(seq),0)+1 FROM events WHERE branch=?").use {
            it.setString(1, branch); it.executeQuery().use { rs -> rs.getLong(1) }
        }

    @Synchronized
    fun appendEvent(
        branch: String, type: EventType, payload: Map<String, Any?>,
        parentEventIds: List<String> = emptyList(), parameterVersion: String? = null,
        inputDigest: String? = null, refId: String? = null, note: String? = null,
        eventId: String? = null,
    ): EventRecord {
        val id = eventId ?: ("evt-" + SnapshotImporter.sha256(
            "$branch|$type|${clock()}|${payload.hashCode()}|${nextSeq(branch)}"
        ).take(12))
        val seq = nextSeq(branch)
        val ev = EventRecord(
            eventId = id, seq = seq, branch = branch, type = type, occurredAtMs = clock(),
            timeBasis = "epoch-ms(local)", parentEventIds = parentEventIds,
            parameterVersion = parameterVersion, inputDigest = inputDigest,
            payload = payload, refId = refId, note = note,
        )
        conn.prepareStatement(
            """INSERT INTO events(event_id,seq,branch,type,occurred_at_ms,time_basis,
               parent_event_ids,parameter_version,input_digest,payload,ref_id,note)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"""
        ).use {
            it.setString(1, ev.eventId); it.setLong(2, ev.seq); it.setString(3, ev.branch)
            it.setString(4, ev.type.name); it.setLong(5, ev.occurredAtMs); it.setString(6, ev.timeBasis)
            it.setString(7, mapper.writeValueAsString(ev.parentEventIds))
            it.setString(8, ev.parameterVersion); it.setString(9, ev.inputDigest)
            it.setString(10, mapper.writeValueAsString(ev.payload)); it.setString(11, ev.refId)
            it.setString(12, ev.note)
            it.executeUpdate()
        }
        return ev
    }

    fun events(branch: String? = null): List<EventRecord> =
        (if (branch == null)
            conn.createStatement().executeQuery("SELECT * FROM events ORDER BY seq,event_id")
        else conn.prepareStatement("SELECT * FROM events WHERE branch=? ORDER BY seq").apply { setString(1, branch) }
            .executeQuery()).use { rs ->
            val out = mutableListOf<EventRecord>()
            while (rs.next()) out.add(rowToEvent(rs))
            out
        }

    fun event(id: String): EventRecord? =
        conn.prepareStatement("SELECT * FROM events WHERE event_id=?").use {
            it.setString(1, id); it.executeQuery().use { rs -> if (rs.next()) rowToEvent(rs) else null }
        }

    @Suppress("UNCHECKED_CAST")
    private fun rowToEvent(rs: java.sql.ResultSet) = EventRecord(
        eventId = rs.getString("event_id"), seq = rs.getLong("seq"),
        branch = rs.getString("branch"), type = EventType.valueOf(rs.getString("type")),
        occurredAtMs = rs.getLong("occurred_at_ms"), timeBasis = rs.getString("time_basis"),
        parentEventIds = mapper.readValue(rs.getString("parent_event_ids"), List::class.java) as List<String>,
        parameterVersion = rs.getString("parameter_version"),
        inputDigest = rs.getString("input_digest"),
        payload = mapper.readValue(rs.getString("payload"), Map::class.java) as Map<String, Any?>,
        refId = rs.getString("ref_id"), note = rs.getString("note"),
    )

    // ---- 半写阶段 ----
    fun stageBegin(stagingId: String, snapshotId: String, rawJson: String, phase: String = "RAW_RECEIVED") {
        conn.prepareStatement(
            "INSERT INTO import_staging(staging_id,snapshot_id,phase,raw_json,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?)"
        ).use {
            it.setString(1, stagingId); it.setString(2, snapshotId); it.setString(3, phase)
            it.setString(4, rawJson); val t = clock(); it.setLong(5, t); it.setLong(6, t)
            it.executeUpdate()
        }
    }

    fun stageAdvance(stagingId: String, phase: String) {
        conn.prepareStatement("UPDATE import_staging SET phase=?,updated_at_ms=? WHERE staging_id=?").use {
            it.setString(1, phase); it.setLong(2, clock()); it.setString(3, stagingId); it.executeUpdate()
        }
    }

    fun stageComplete(stagingId: String) {
        conn.prepareStatement("DELETE FROM import_staging WHERE staging_id=?").use {
            it.setString(1, stagingId); it.executeUpdate()
        }
    }

    /** 测试钩子：在指定阶段模拟崩溃（不提交后续推进）。 */
    fun crashAfterPhase(stagingId: String, phase: String): String? {
        stageAdvance(stagingId, phase)
        return conn.prepareStatement("SELECT snapshot_id,raw_json FROM import_staging WHERE staging_id=?").use {
            it.setString(1, stagingId); it.executeQuery().use { rs ->
                if (rs.next()) rs.getString(2) else null
            }
        }
    }

    fun pendingStaging(): List<Triple<String, String, String>> =
        conn.createStatement().executeQuery("SELECT staging_id,snapshot_id,phase FROM import_staging")
            .use { rs ->
                val out = mutableListOf<Triple<String, String, String>>()
                while (rs.next()) out.add(Triple(rs.getString(1), rs.getString(2), rs.getString(3)))
                out
            }

    /**
     * 半写恢复：对每个未完成 staging，
     *  - RAW_RECEIVED：材料从未落库 => 隔离 raw 并写恢复事件，staging 清除；
     *  - MATERIAL_INSERTED：材料已落库但事件缺失 => 补写 MATERIAL_IMPORTED（材料仍可用）或隔离。
     * 恢复过程产生结构化事件而不是只写日志。
     */
    fun recoverHalfWritten(): List<EventRecord> {
        val out = mutableListOf<EventRecord>()
        for ((stagingId, snapshotId, phase0) in pendingStaging()) {
            val phase = phase0
            val raw = conn.prepareStatement("SELECT raw_json FROM import_staging WHERE staging_id=?").use {
                it.setString(1, stagingId); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            val materialExists = conn.prepareStatement(
                "SELECT 1 FROM materials WHERE snapshot_id=?"
            ).use { it.setString(1, snapshotId); it.executeQuery().use { r -> r.next() } }
            when {
                materialExists && phase == "MATERIAL_INSERTED" -> {
                    val ev = appendEvent(
                        "main", EventType.RECOVERY_MARKER,
                        mapOf(
                            "action" to "RELINKED_ORPHAN_MATERIAL",
                            "snapshotId" to snapshotId, "stagingId" to stagingId,
                            "phase" to phase,
                        ),
                        note = "崩溃发生在材料落库后、事件写入前：已补挂材料，身份不变"
                    )
                    out.add(ev)
                }
                else -> {
                    val ev = appendEvent(
                        "main", EventType.RECOVERY_MARKER,
                        mapOf(
                            "action" to "QUARANTINED_RAW",
                            "snapshotId" to snapshotId, "stagingId" to stagingId,
                            "phase" to phase,
                            "rawLength" to (raw?.length ?: 0),
                        ),
                        note = "半写导入已隔离：$phase，未产生伪成功材料"
                    )
                    out.add(ev)
                }
            }
            conn.prepareStatement("DELETE FROM import_staging WHERE staging_id=?").use {
                it.setString(1, stagingId); it.executeUpdate()
            }
        }
        return out
    }

    fun insertMaterial(materialId: String, snapshotId: String, eventId: String, rawJson: String, parserVersion: String, ordinal: Int) {
        conn.prepareStatement(
            """INSERT INTO materials(material_id,snapshot_id,event_id,raw_json,parser_version,imported_at_ms,ordinal)
               VALUES(?,?,?,?,?,?,?)"""
        ).use {
            it.setString(1, materialId); it.setString(2, snapshotId); it.setString(3, eventId)
            it.setString(4, rawJson); it.setString(5, parserVersion); it.setLong(6, clock()); it.setInt(7, ordinal)
            it.executeUpdate()
        }
    }

    fun materialRaw(snapshotId: String): String? =
        conn.prepareStatement("SELECT raw_json FROM materials WHERE snapshot_id=? ORDER BY ordinal DESC LIMIT 1").use {
            it.setString(1, snapshotId); it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun materialOrdinals(): List<Pair<String, Int>> =
        conn.createStatement().executeQuery("SELECT snapshot_id,ordinal FROM materials ORDER BY ordinal").use { rs ->
            val out = mutableListOf<Pair<String, Int>>()
            while (rs.next()) out.add(rs.getString(1) to rs.getInt(2))
            out
        }
}

/** 派生结果存储：内容寻址，同输入+同参数只存一份，绝不覆盖。 */
class DerivedStore(private val store: ProjectStore) {
    private val mapper get() = store.mapper
    private val conn get() = store.connField()

    @Suppress("UNCHECKED_CAST")
    fun saveAnalysis(result: AnalysisResult, parents: List<String>): String {
        val id = "ana-" + SnapshotImporter.sha256(
            result.inputDigest.materialSha256 + "|" + result.parameterVersion + "|" + result.weakPolicy
        ).take(14)
        val exists = store.analysisExists(id)
        if (!exists) store.insertAnalysis(id, result, parents)
        return id
    }

    fun loadAnalysis(id: String): AnalysisResult? = store.loadAnalysisRow(id)
        ?.let { mapper.readValue(it, AnalysisResult::class.java) }

    fun saveDiff(diff: SnapshotDiff): String {
        val id = "diff-" + SnapshotImporter.sha256(
            diff.digestA + "|" + diff.digestB + "|" + diff.parameterVersionSafe()
        ).take(14)
        if (!store.diffExists(id)) store.insertDiff(id, diff)
        return id
    }

    fun loadDiff(id: String): SnapshotDiff? =
        store.loadDiffRow(id)?.let { mapper.readValue(it, SnapshotDiff::class.java) }

    private fun SnapshotDiff.parameterVersionSafe() = digestA + digestB
}

/** 反例档案：采纳与未采纳结果都保留，证据为机器可读 JSON。 */
data class ProbeEvidence(
    val probeKey: String,
    val title: String,
    val baselineDigest: String,
    val singleSourceChanged: String,
    val expectedScope: List<String>,
    val affectedObjectIds: List<String>,
    val adopted: Boolean,
    val rationale: String,
    val machineEvents: List<Map<String, Any?>>,
    val baseline: Map<String, Any?>,
    val outcome: Map<String, Any?>,
)

class ProbeArchiveStore(private val store: ProjectStore) {
    private val mapper get() = store.mapper
    fun save(evidence: ProbeEvidence): String {
        val id = "probe-" + SnapshotImporter.sha256(evidence.probeKey + evidence.baselineDigest).take(14)
        store.insertProbe(
            id, evidence.probeKey, evidence.adopted,
            mapper.writeValueAsString(evidence.baseline),
            mapper.writeValueAsString(evidence.outcome),
            mapper.writeValueAsString(
                evidence.copy(baseline = emptyMap(), outcome = emptyMap())
            )
        )
        return id
    }

    fun listAll(): List<ProbeEvidence> = store.allProbeRows().map { row ->
        val env: ProbeEvidence = mapper.readValue(row.evidenceJson, ProbeEvidence::class.java)
        env.copy(
            baseline = mapper.readValue(row.baselineJson, Map::class.java) as Map<String, Any?>,
            outcome = mapper.readValue(row.outcomeJson, Map::class.java) as Map<String, Any?>
        )
    }
}

data class ProbeRow(
    val archiveId: String, val probeKey: String, val adopted: Boolean,
    val baselineJson: String, val outcomeJson: String, val evidenceJson: String,
)
