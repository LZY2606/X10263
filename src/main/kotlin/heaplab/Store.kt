package heaplab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant

@Serializable
data class StoredRun(
    val runId: String,
    val snapshotId: String,
    val params: AnalysisParams,
    val inputDigest: String,
    val status: RunStatus,
    val result: AnalysisResultDto? = null,
    val diagnostics: List<Diagnostic> = emptyList(),
    val createdAt: String,
)

@Serializable
data class AnalysisResultDto(
    val reachable: List<String>,
    val idomParent: Map<String, String>,
    val retainedBytes: Map<String, Long>,
    val depth: Map<String, Int>,
    val rootPaths: Map<String, List<List<PathHop>>>,
    val pathsTruncated: List<String>,
) {
    fun toResult() = AnalysisResult(
        reachable = reachable.toSortedSet(),
        idomParent = idomParent,
        retainedBytes = retainedBytes,
        depth = depth,
        rootPaths = rootPaths,
        pathsTruncated = pathsTruncated.toSortedSet(),
    )
}

@Serializable
data class StoredEvent(val seq: Long, val event: EventRecord)

@Serializable
data class ExportDocument(
    val schemaVersion: Int,
    val exportedAt: String,
    val snapshots: List<Pair<String, Snapshot>>,
    val runs: List<StoredRun>,
    val cases: List<CaseRecord>,
    val events: List<StoredEvent>,
    val checksum: String = "",
)

class Store(private val path: Path, private val clock: Clock = Clock.systemUTC()) : AutoCloseable {

    private val conn: Connection

    init {
        val parent = path.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.autoCommit = false
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
        }
        conn.commit()
        schema()
    }

    private fun schema() {
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS snapshot(
                  snapshot_id TEXT PRIMARY KEY,
                  snapshot_json TEXT NOT NULL,
                  imported_seq INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS run(
                  run_id TEXT PRIMARY KEY,
                  snapshot_id TEXT NOT NULL,
                  run_json TEXT NOT NULL,
                  created_seq INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS case_record(
                  case_id TEXT PRIMARY KEY,
                  case_json TEXT NOT NULL,
                  created_seq INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS event(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT,
                  event_id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  at TEXT NOT NULL,
                  snapshot_id TEXT,
                  run_id TEXT,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            st.executeUpdate("CREATE TABLE IF NOT EXISTS kv(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
        }
        conn.commit()
    }

    private fun nowIso() = Instant.now(clock).toString()

    private fun nextId(prefix: String, key: String): String {
        val n = (kv(key) ?: "0").toLong() + 1
        conn.createStatement().use { it.executeUpdate("INSERT OR REPLACE INTO kv(k,v) VALUES('$key','$n')") }
        return "$prefix-%06d".format(n)
    }

    private fun kv(key: String): String? =
        conn.prepareStatement("SELECT v FROM kv WHERE k=?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { if (it.next()) it.getString(1) else null }
        }

    private fun digest(s: Snapshot): String {
        val canon = heapJson.encodeToString(Snapshot.serializer(), s)
        val md = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    // ---------- 不可变材料 ----------

    fun snapshotExists(id: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM snapshot WHERE snapshot_id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { it.next() }
        }

    fun importSnapshot(s: Snapshot, diagnostics: List<Diagnostic>, note: String = "初始导入"): EventRecord {
        require(!snapshotExists(s.snapshotId)) { "快照 id 已存在：${s.snapshotId}" }
        val ev = appendEvent(
            EventKinds.SNAPSHOT_IMPORTED,
            snapshotId = s.snapshotId,
            payload = buildJsonObject {
                put("name", s.name)
                put("parserVersion", s.parserVersion)
                put("note", note)
                put("objectCount", s.objects.size)
                put("edgeCount", s.edges.size)
                put("diagnostics", heapJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Diagnostic.serializer()), diagnostics))
            },
        )
        conn.prepareStatement("INSERT INTO snapshot(snapshot_id, snapshot_json, imported_seq) VALUES(?,?,?)").use { ps ->
            ps.setString(1, s.snapshotId)
            ps.setString(2, heapJson.encodeToString(Snapshot.serializer(), s))
            ps.setLong(3, evSeq(ev.eventId))
            ps.executeUpdate()
        }
        conn.commit()
        return ev
    }

    fun recordRejectedImport(s: Snapshot?, diagnostics: List<Diagnostic>, rawRef: String): EventRecord {
        val ev = appendEvent(
            EventKinds.IMPORT_REJECTED,
            snapshotId = s?.snapshotId,
            payload = buildJsonObject {
                put("rawRef", rawRef)
                put("diagnostics", heapJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Diagnostic.serializer()), diagnostics))
            },
        )
        conn.commit()
        return ev
    }

    fun getSnapshot(id: String): Snapshot? =
        conn.prepareStatement("SELECT snapshot_json FROM snapshot WHERE snapshot_id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use {
                if (it.next()) heapJson.decodeFromString(Snapshot.serializer(), it.getString(1)) else null
            }
        }

    fun listSnapshots(): List<Snapshot> =
        conn.createStatement().executeQuery("SELECT snapshot_json FROM snapshot ORDER BY imported_seq, snapshot_id")
            .use { rs ->
                buildList { while (rs.next()) add(heapJson.decodeFromString(Snapshot.serializer(), rs.getString(1))) }
            }

    // ---------- 冻结计算结果（输入摘要 + 参数版本 + 父级依赖） ----------

    fun recordRun(snapshotId: String, params: AnalysisParams, outcome: AnalysisOutcome): Pair<StoredRun, EventRecord> {
        val snapshot = getSnapshot(snapshotId) ?: error("快照不存在：$snapshotId")
        val runId = nextId("run", "counter.run")
        val dto = outcome.result?.let {
            AnalysisResultDto(
                reachable = it.reachable.toList(),
                idomParent = it.idomParent,
                retainedBytes = it.retainedBytes,
                depth = it.depth,
                rootPaths = it.rootPaths,
                pathsTruncated = it.pathsTruncated.toList(),
            )
        }
        val run = StoredRun(runId, snapshotId, params, digest(snapshot), outcome.status, dto, outcome.diagnostics, nowIso())
        val ev = appendEvent(
            if (outcome.status == RunStatus.OK) EventKinds.ANALYSIS_COMPLETED else EventKinds.ANALYSIS_FAILED,
            snapshotId = snapshotId,
            runId = runId,
            payload = buildJsonObject {
                put("paramsVersion", params.paramsVersion)
                put("inputDigest", run.inputDigest)
                put("status", outcome.status.name)
                put("reachableCount", outcome.result?.reachable?.size ?: 0)
                put("diagnostics", heapJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Diagnostic.serializer()), outcome.diagnostics))
            },
        )
        conn.prepareStatement("INSERT INTO run(run_id, snapshot_id, run_json, created_seq) VALUES(?,?,?,?)").use { ps ->
            ps.setString(1, runId)
            ps.setString(2, snapshotId)
            ps.setString(3, heapJson.encodeToString(StoredRun.serializer(), run))
            ps.setLong(4, evSeq(runId))
            ps.executeUpdate()
        }
        conn.commit()
        return run to ev
    }

    fun getRun(runId: String): StoredRun? =
        conn.prepareStatement("SELECT run_json FROM run WHERE run_id=?").use { ps ->
            ps.setString(1, runId)
            ps.executeQuery().use {
                if (it.next()) heapJson.decodeFromString(StoredRun.serializer(), it.getString(1)) else null
            }
        }

    fun listRuns(): List<StoredRun> =
        conn.createStatement().executeQuery("SELECT run_json FROM run ORDER BY created_seq, run_id")
            .use { rs -> buildList { while (rs.next()) add(heapJson.decodeFromString(StoredRun.serializer(), rs.getString(1))) } }

    // ---------- 决定与规则判断 ----------

    fun recordDecision(runId: String, accept: Boolean, rationale: String, caseId: String? = null): EventRecord {
        val run = getRun(runId) ?: error("运行不存在：$runId")
        val kind = if (accept) EventKinds.REVIEW_ACCEPTED else EventKinds.REVIEW_REJECTED
        val ev = appendEvent(
            kind,
            snapshotId = run.snapshotId,
            runId = runId,
            payload = buildJsonObject {
                put("rationale", rationale)
                if (caseId != null) put("caseId", caseId)
                put("paramsVersion", run.params.paramsVersion)
                put("inputDigest", run.inputDigest)
            },
        )
        maybeMarkConflict(caseId, runId, accept, ev)
        conn.commit()
        return ev
    }

    fun recordRuleJudgment(ruleId: String, basis: String, snapshotId: String?, runId: String?, affected: List<String>): EventRecord {
        val ev = appendEvent(
            EventKinds.RULE_JUDGMENT,
            snapshotId = snapshotId,
            runId = runId,
            payload = buildJsonObject {
                put("ruleId", ruleId)
                put("basis", basis)
                put("affectedObjects", heapJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), affected))
            },
        )
        conn.commit()
        return ev
    }

    fun recordParamsRevision(runId: String, newParams: AnalysisParams, note: String): EventRecord {
        val ev = appendEvent(
            EventKinds.PARAMS_REVISED,
            runId = runId,
            payload = buildJsonObject {
                put("note", note)
                put("newParams", heapJson.encodeToJsonElement(AnalysisParams.serializer(), newParams))
            },
        )
        conn.commit()
        return ev
    }

    fun recordSourceAmended(beforeSnapshotId: String, afterSnapshotId: String, changedField: String, affected: List<String>): EventRecord {
        val ev = appendEvent(
            EventKinds.SOURCE_AMENDED,
            snapshotId = afterSnapshotId,
            payload = buildJsonObject {
                put("beforeSnapshotId", beforeSnapshotId)
                put("afterSnapshotId", afterSnapshotId)
                put("changedField", changedField)
                put("affectedObjects", heapJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), affected))
            },
        )
        conn.commit()
        return ev
    }

    private fun maybeMarkConflict(caseId: String?, runId: String, accept: Boolean, ev: EventRecord) {
        if (caseId == null || !accept) return
        val prior = events().filter {
            it.kind == EventKinds.REVIEW_ACCEPTED &&
                (it.payload["caseId"] as? JsonPrimitive)?.content == caseId
        }.mapNotNull { it.runId }.distinct()
        if (prior.any { it != runId }) {
            appendEventNoCommit(
                EventKinds.DECISION_CONFLICT,
                runId = runId,
                payload = buildJsonObject {
                    put("caseId", caseId)
                    put("candidateRunIds", heapJson.encodeToJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()),
                        (prior + runId).distinct(),
                    ))
                    put("basis", "同一反例存在互相冲突的人工采纳决定，结论语义无解，保留全部合法候选")
                },
            )
        }
    }

    private fun evSeq(idOrRunId: String): Long {
        conn.createStatement().executeQuery("SELECT COALESCE(MAX(seq),0) FROM event").use { rs ->
            rs.next(); return rs.getLong(1)
        }
    }

    private fun appendEvent(kind: String, snapshotId: String? = null, runId: String? = null, payload: JsonObject): EventRecord {
        val ev = appendEventNoCommit(kind, snapshotId, runId, payload)
        conn.commit()
        return ev
    }

    private fun appendEventNoCommit(kind: String, snapshotId: String? = null, runId: String? = null, payload: JsonObject): EventRecord {
        val at = nowIso()
        conn.prepareStatement(
            "INSERT INTO event(event_id, kind, at, snapshot_id, run_id, payload_json) VALUES(?,?,?,?,?,?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, "pending")
            ps.setString(2, kind)
            ps.setString(3, at)
            if (snapshotId == null) ps.setNull(4, java.sql.Types.VARCHAR) else ps.setString(4, snapshotId)
            if (runId == null) ps.setNull(5, java.sql.Types.VARCHAR) else ps.setString(5, runId)
            ps.setString(6, payload.toString())
            ps.executeUpdate()
            ps.generatedKeys.use { keys ->
                keys.next()
                val seq = keys.getLong(1)
                val eventId = "evt-%06d".format(seq)
                conn.prepareStatement("UPDATE event SET event_id=? WHERE seq=?").use { up ->
                    up.setString(1, eventId)
                    up.setLong(2, seq)
                    up.executeUpdate()
                }
                return EventRecord(eventId, kind, at, snapshotId, runId, payload)
            }
        }
    }

    fun events(): List<EventRecord> =
        conn.createStatement().executeQuery("SELECT event_id, kind, at, snapshot_id, run_id, payload_json FROM event ORDER BY seq")
            .use { rs ->
                buildList {
                    while (rs.next()) add(
                        EventRecord(
                            eventId = rs.getString(1),
                            kind = rs.getString(2),
                            at = rs.getString(3),
                            snapshotId = rs.getString(4),
                            runId = rs.getString(5),
                            payload = heapJson.parseToJsonElement(rs.getString(6)) as JsonObject,
                        )
                    )
                }
            }

    fun eventAt(seq: Long): EventRecord? =
        conn.prepareStatement("SELECT event_id, kind, at, snapshot_id, run_id, payload_json FROM event WHERE seq=?").use { ps ->
            ps.setLong(1, seq)
            ps.executeQuery().use { rs ->
                if (!rs.next()) null
                else EventRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), heapJson.parseToJsonElement(rs.getString(6)) as JsonObject)
            }
        }

    fun eventSeqOf(eventId: String): Long? =
        conn.prepareStatement("SELECT seq FROM event WHERE event_id=?").use { ps ->
            ps.setString(1, eventId)
            ps.executeQuery().use { if (it.next()) it.getLong(1) else null }
        }

    /**
     * 历史可见结论：事件 N 时刻冻结的依赖版本。
     * 结论 = 该事件引用的 run（若事件本身不带 run，则取当时最近一条带 run 的事件）；
     * 失效区间 = 同一快照上之后出现的修订/重算事件。
     */
    fun historicalView(seq: Long): HistoricalView? {
        val ev = eventAt(seq) ?: return null
        val all = events()
        val upto = all.filter { eventSeqOf(it.eventId)!! <= seq }
        val anchor = if (ev.runId != null) ev else upto.lastOrNull { it.runId != null }
        val runId = anchor?.runId
        val run = runId?.let { getRun(it) }
        val snapshotId = ev.snapshotId ?: run?.snapshotId
        val later = all.filter { eventSeqOf(it.eventId)!! > seq }
        val invalid = later.firstOrNull { e ->
            e.kind in setOf(EventKinds.PARAMS_REVISED, EventKinds.SOURCE_AMENDED, EventKinds.ANALYSIS_COMPLETED, EventKinds.ANALYSIS_FAILED) &&
                (e.snapshotId == snapshotId || (runId != null && e.runId == runId))
        }
        return HistoricalView(
            event = ev,
            runId = runId,
            paramsVersion = run?.params?.paramsVersion,
            validFromSeq = runId?.let { eventSeqOf(all.firstOrNull { it.runId == runId }?.eventId ?: "") },
            invalidAtSeq = invalid?.let { eventSeqOf(it.eventId) },
            invalidationReason = invalid?.let { "被后续事件 ${it.eventId}（${it.kind}）取代" },
        )
    }

    // ---------- 反例档案 ----------

    fun saveCase(c: CaseRecord): EventRecord {
        conn.prepareStatement("INSERT OR REPLACE INTO case_record(case_id, case_json, created_seq) VALUES(?,?,?)").use { ps ->
            ps.setString(1, c.caseId)
            ps.setString(2, heapJson.encodeToString(CaseRecord.serializer(), c))
            ps.setLong(3, evSeq(c.caseId))
            ps.executeUpdate()
        }
        val ev = appendEvent(
            EventKinds.CASE_RECORDED,
            snapshotId = c.baselineSnapshotId,
            payload = buildJsonObject {
                put("caseId", c.caseId)
                put("ruleId", c.ruleId)
                put("title", c.title)
                put("directSourceChange", c.directSourceChange)
            },
        )
        conn.commit()
        return ev
    }

    fun cases(): List<CaseRecord> =
        conn.createStatement().executeQuery("SELECT case_json FROM case_record ORDER BY case_id")
            .use { rs -> buildList { while (rs.next()) add(heapJson.decodeFromString(CaseRecord.serializer(), rs.getString(1))) } }

    fun case(id: String): CaseRecord? =
        conn.prepareStatement("SELECT case_json FROM case_record WHERE case_id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { if (it.next()) heapJson.decodeFromString(CaseRecord.serializer(), it.getString(1)) else null }
        }

    sealed interface VerdictState {
        data class Open(val rejected: List<EventRecord>) : VerdictState
        data class Resolved(val acceptedRunId: String, val accepted: List<EventRecord>, val rejected: List<EventRecord>) : VerdictState
        data class Conflict(val candidateRunIds: List<String>, val accepted: List<EventRecord>) : VerdictState
    }

    fun caseVerdict(caseId: String): VerdictState {
        val evs = events()
        val accepted = evs.filter { it.kind == EventKinds.REVIEW_ACCEPTED && (it.payload["caseId"] as? JsonPrimitive)?.content == caseId }
        val rejected = evs.filter { it.kind == EventKinds.REVIEW_REJECTED && (it.payload["caseId"] as? JsonPrimitive)?.content == caseId }
        val acceptedRuns = accepted.mapNotNull { it.runId }.distinct()
        return when {
            acceptedRuns.size > 1 -> VerdictState.Conflict(acceptedRuns, accepted)
            acceptedRuns.size == 1 -> VerdictState.Resolved(acceptedRuns.single(), accepted, rejected)
            else -> VerdictState.Open(rejected)
        }
    }

    // ---------- 导出 / 导入（身份、顺序、来源关系保持；半写回滚） ----------

    fun exportDocument(at: String = Instant.now(clock).toString()): ExportDocument {
        val payload = ExportDocument(
            schemaVersion = SCHEMA_VERSION,
            exportedAt = at,
            snapshots = listSnapshots().map { it.snapshotId to it },
            runs = listRuns(),
            cases = cases(),
            events = events().map { e ->
                StoredEvent(eventSeqOf(e.eventId)!!, e)
            },
        )
        val body = heapJson.encodeToString(ExportDocument.serializer(), payload)
        return payload.copy(checksum = sha256Hex(body))
    }

    fun importDocument(doc: ExportDocument, crashAfterSnapshots: Boolean = false): List<EventRecord> {
        val body = heapJson.encodeToString(ExportDocument.serializer(), doc.copy(checksum = ""))
        if (sha256Hex(body) != doc.checksum) throw DocumentException("校验和不匹配：导出文件可能半写或损坏，拒绝导入")
        if (doc.schemaVersion != SCHEMA_VERSION) throw DocumentException("不支持的导出 schema 版本：${doc.schemaVersion}")
        if (!isEmpty()) throw DocumentException("目标数据库非空：为保证身份不被覆盖，只允许向空库导入")

        val snapIds = doc.snapshots.map { it.first }.toSet()
        doc.runs.forEach { if (it.snapshotId !in snapIds) throw DocumentException("run ${it.runId} 引用缺失快照 ${it.snapshotId}") }
        val runIds = doc.runs.map { it.runId }.toSet()
        doc.events.forEach { e ->
            e.event.snapshotId?.let { if (it !in snapIds) throw DocumentException("事件 ${e.event.eventId} 引用缺失快照 $it") }
            e.event.runId?.let { if (it !in runIds) throw DocumentException("事件 ${e.event.eventId} 引用缺失 run $it") }
        }

        conn.autoCommit = false
        try {
            doc.snapshots.forEach { (id, s) ->
                conn.prepareStatement("INSERT INTO snapshot(snapshot_id, snapshot_json, imported_seq) VALUES(?,?,?)").use { ps ->
                    ps.setString(1, id)
                    ps.setString(2, heapJson.encodeToString(Snapshot.serializer(), s))
                    ps.setLong(3, 0)
                    ps.executeUpdate()
                }
            }
            if (crashAfterSnapshots) throw RuntimeException("SIMULATED_CRASH_MID_IMPORT")
            doc.runs.forEach { r ->
                conn.prepareStatement("INSERT INTO run(run_id, snapshot_id, run_json, created_seq) VALUES(?,?,?,?)").use { ps ->
                    ps.setString(1, r.runId)
                    ps.setString(2, r.snapshotId)
                    ps.setString(3, heapJson.encodeToString(StoredRun.serializer(), r))
                    ps.setLong(4, 0)
                    ps.executeUpdate()
                }
            }
            doc.cases.forEach { c ->
                conn.prepareStatement("INSERT INTO case_record(case_id, case_json, created_seq) VALUES(?,?,?)").use { ps ->
                    ps.setString(1, c.caseId)
                    ps.setString(2, heapJson.encodeToString(CaseRecord.serializer(), c))
                    ps.setLong(3, 0)
                    ps.executeUpdate()
                }
            }
            val inserted = mutableListOf<EventRecord>()
            doc.events.forEach { e ->
                conn.prepareStatement("INSERT INTO event(seq, event_id, kind, at, snapshot_id, run_id, payload_json) VALUES(?,?,?,?,?,?,?)").use { ps ->
                    ps.setLong(1, e.seq)
                    ps.setString(2, e.event.eventId)
                    ps.setString(3, e.event.kind)
                    ps.setString(4, e.event.at)
                    if (e.event.snapshotId == null) ps.setNull(5, java.sql.Types.VARCHAR) else ps.setString(5, e.event.snapshotId)
                    if (e.event.runId == null) ps.setNull(6, java.sql.Types.VARCHAR) else ps.setString(6, e.event.runId)
                    ps.setString(7, e.event.payload.toString())
                    ps.executeUpdate()
                }
                inserted.add(e.event)
            }
            val maxSeq = doc.events.maxOfOrNull { it.seq } ?: 0L
            if (maxSeq > 0) conn.createStatement().executeUpdate("UPDATE sqlite_sequence SET seq=$maxSeq WHERE name='event'")
            val maxRunSeq = doc.runs.mapNotNull { it.runId.removePrefix("run-").toLongOrNull() }.maxOrNull() ?: 0L
            conn.createStatement().executeUpdate("INSERT OR REPLACE INTO kv(k,v) VALUES('counter.run','$maxRunSeq')")
            conn.commit()
            return inserted
        } catch (e: Throwable) {
            conn.rollback()
            if (e is RuntimeException && e.message == "SIMULATED_CRASH_MID_IMPORT")
                throw DocumentException("模拟半写：事务已回滚，数据库无残留")
            throw e
        }
    }

    private fun isEmpty(): Boolean {
        conn.createStatement().executeQuery(
            "SELECT (SELECT COUNT(*) FROM snapshot)+(SELECT COUNT(*) FROM run)+(SELECT COUNT(*) FROM event)+(SELECT COUNT(*) FROM case_record)"
        ).use { rs -> rs.next(); return rs.getLong(1) == 0L }
    }

    class DocumentException(message: String) : Exception(message)

    override fun close() {
        conn.close()
    }
}
