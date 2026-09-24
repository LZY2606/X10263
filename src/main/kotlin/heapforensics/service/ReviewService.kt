package heapforensics.service

import heapforensics.core.*
import heapforensics.store.Database
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

class ReviewService(val db: Database) {

    @Serializable
    data class ImportReport(
        val snapshotId: String, val eventSeq: Int, val eventId: String,
        val objectCount: Int, val edgeCount: Int, val rootCount: Int,
        val incompleteBlockCount: Int, val diagnostics: List<Diagnostic>,
    )

    @Serializable
    data class EventEnvelope(
        val seq: Int, val eventId: String, val branch: String, val parentSeq: Int?,
        val kind: String, val createdAt: String, val actor: String, val payload: JsonObject,
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun <T> fromAnalysis(r: AnalysisResult<T>): ServiceResult<T> = when (r) {
        is AnalysisResult.Ok -> ServiceResult.Ok(r.value)
        is AnalysisResult.BudgetExhausted -> ServiceResult.BudgetExhausted(r.budget, r.detail)
        is AnalysisResult.FormatCorrupt -> ServiceResult.FormatCorrupt(r.diagnostics)
        is AnalysisResult.SemanticallyUnsolvable -> ServiceResult.SemanticallyUnsolvable(r.reasons)
    }

    fun currentParamsVersion(): Int = db.getMeta("params_version", "1")!!.toInt()

    fun currentParams(): AnalysisParams {
        val version = currentParamsVersion()
        val json = db.getMeta("params_json_v$version")
            ?: return AnalysisParams(version = 1)
        return reviewJson.decodeFromString(AnalysisParams.serializer(), json).copy(version = version)
    }

    fun paramsAt(version: Int): AnalysisParams {
        val json = db.getMeta("params_json_v$version")
            ?: error("参数版本 v$version 不存在")
        return reviewJson.decodeFromString(AnalysisParams.serializer(), json).copy(version = version)
    }

    fun loadSnapshot(id: String): HeapSnapshot? = db.loadSnapshot(id)

    /** 导入原始 HSF：材料不可变，写一个 IMPORT_SNAPSHOT 事件；整体事务。 */
    fun importSnapshot(sourceName: String, text: String, suggestedId: String? = null, actor: String = "runtime-team"):
        ServiceResult<ImportReport> {
        try {
            db.conn.autoCommit = false
            val parsed = HsfParser.parse(HsfParser.ParseInput(text, suggestedId ?: sourceName))
            when (parsed) {
                is AnalysisResult.FormatCorrupt -> { db.conn.rollback(); return ServiceResult.FormatCorrupt(parsed.diagnostics) }
                is AnalysisResult.BudgetExhausted -> { db.conn.rollback(); return ServiceResult.BudgetExhausted(parsed.budget, parsed.detail) }
                is AnalysisResult.SemanticallyUnsolvable -> error("解析阶段不应产生语义无解")
                is AnalysisResult.Ok -> {
                    val (snap, raw) = parsed.value
                    if (db.snapshotExists(snap.snapshotId)) {
                        db.conn.rollback()
                        return ServiceResult.FormatCorrupt(
                            listOf(Diagnostic(0, Severity.ERROR, "snapshot id '${snap.snapshotId}' 已存在，材料不可覆盖", ""))
                        )
                    }
                    val digest = sha256(raw)
                    val payload = buildJsonObject {
                        put("snapshotId", snap.snapshotId)
                        put("sourceName", sourceName)
                        put("rawSha256", digest)
                        put("parserVersion", snap.parserVersion)
                        put("capturedAt", snap.capturedAt)
                        put("epochMillis", snap.epochMillis)
                        put("objectCount", snap.objects.size)
                        put("edgeCount", snap.edges.size)
                        put("rootCount", snap.roots.size)
                        put("incompleteBlockCount", snap.incompleteBlocks.size)
                    }
                    val event = db.appendEvent("IMPORT_SNAPSHOT", payload.toString(), actor = actor)
                    db.insertSnapshot(snap, raw, digest, sourceName, null, event.seq)
                    db.conn.commit()
                    return ServiceResult.Ok(
                        ImportReport(snap.snapshotId, event.seq, event.eventId, snap.objects.size, snap.edges.size,
                            snap.roots.size, snap.incompleteBlocks.size, snap.diagnostics)
                    )
                }
            }
        } catch (t: Throwable) {
            db.conn.rollback()
            throw t
        } finally {
            db.conn.autoCommit = true
        }
    }

    /** 参数修订：产生新版本，旧版本永久保留，历史事件回放时钉住旧版本。 */
    fun reviseParams(next: AnalysisParams, reason: String, actor: String = "reviewer"): EventEnvelope {
        val oldVersion = currentParamsVersion()
        val newVersion = oldVersion + 1
        val saved = next.copy(version = newVersion)
        db.setMeta("params_json_v$newVersion", reviewJson.encodeToString(AnalysisParams.serializer(), saved))
        db.setMeta("params_version", newVersion.toString())
        val payload = buildJsonObject {
            put("fromVersion", oldVersion)
            put("toVersion", newVersion)
            put("reason", reason)
            put("params", kotlinx.serialization.json.Json.parseToJsonElement(
                reviewJson.encodeToString(AnalysisParams.serializer(), saved)
            ))
        }
        val e = db.appendEvent("REVISE_PARAMS", payload.toString(), actor)
        return envelope(e)
    }

    /**
     * 只改动一个直接来源：在父快照上应用 HSF 片段（只允许追加 class/object/edge/root 行），
     * 得到新快照版本与父子血缘；父快照和它的历史结论原样保留。
     */
    fun applyPatch(parentSnapshotId: String, patchName: String, patchText: String, description: String,
                   actor: String = "runtime-team"): ServiceResult<ImportReport> {
        val parentRaw = db.rawSnapshot(parentSnapshotId)
            ?: return ServiceResult.FormatCorrupt(listOf(Diagnostic(0, Severity.ERROR, "父快照不存在: $parentSnapshotId", "")))
        val newId = "$parentSnapshotId+" + patchName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val header = "snapshot $newId\n"
        val merged = parentRaw.first.replace(Regex("(?m)^snapshot\\s+.*$"), header.trim()) +
            "\n# --- patch: $patchName ---\n" + patchText.trim()
        try {
            db.conn.autoCommit = false
            val parsed = HsfParser.parse(HsfParser.ParseInput(merged, newId))
            when (parsed) {
                is AnalysisResult.FormatCorrupt -> { db.conn.rollback(); return ServiceResult.FormatCorrupt(parsed.diagnostics) }
                is AnalysisResult.BudgetExhausted -> { db.conn.rollback(); return ServiceResult.BudgetExhausted(parsed.budget, parsed.detail) }
                is AnalysisResult.SemanticallyUnsolvable -> error("解析阶段不应产生语义无解")
                is AnalysisResult.Ok -> {
                    val (snap, raw) = parsed.value
                    val digest = sha256(raw)
                    val payload = buildJsonObject {
                        put("snapshotId", snap.snapshotId)
                        put("parentSnapshotId", parentSnapshotId)
                        put("patchName", patchName)
                        put("description", description)
                        put("rawSha256", digest)
                        put("affectedObjectCount", snap.objects.size)
                    }
                    val event = db.appendEvent("APPLY_SOURCE_PATCH", payload.toString(), actor)
                    db.insertSnapshot(snap, raw, digest, patchName, parentSnapshotId, event.seq)
                    db.conn.commit()
                    return ServiceResult.Ok(
                        ImportReport(snap.snapshotId, event.seq, event.eventId, snap.objects.size, snap.edges.size,
                            snap.roots.size, snap.incompleteBlocks.size, snap.diagnostics)
                    )
                }
            }
        } catch (t: Throwable) {
            db.conn.rollback()
            throw t
        } finally {
            db.conn.autoCommit = true
        }
    }

    /** 接受/否定/参数调整都是新事件；互相冲突的人工决定同时保留，不互相覆盖。 */
    fun recordDecision(subject: String, verdict: String, rationale: String,
                       reviewer: String = "reviewer", paramsVersion: Int? = null): EventEnvelope {
        require(verdict in setOf("ACCEPT", "REJECT", "ADJUST")) { "verdict 必须为 ACCEPT/REJECT/ADJUST" }
        val pv = paramsVersion ?: currentParamsVersion()
        val payload = buildJsonObject {
            put("subject", subject)
            put("verdict", verdict)
            put("rationale", rationale)
            put("paramsVersion", pv)
        }
        return envelope(db.appendEvent("RECORD_DECISION", payload.toString(), reviewer))
    }

    @Serializable
    data class ComputedView(
        val status: String,
        val computationId: String,
        val kind: String,
        val snapshotId: String,
        val paramsVersion: Int,
        val inputDigest: String,
        val eventSeq: Int,
        val cached: Boolean,
        val analysis: GraphAnalysis.FullAnalysis? = null,
        val error: JsonObject? = null,
    )

    private fun envelope(e: Database.EventRow): EventEnvelope =
        EventEnvelope(e.seq, e.eventId, e.branch, e.parentSeq, e.kind, e.createdAt, e.actor,
            kotlinx.serialization.json.Json.parseToJsonElement(e.payload).jsonObject)

    fun events(): List<EventEnvelope> = db.listEvents().map { envelope(it) }

    fun event(seq: Int): EventEnvelope? = db.getEvent(seq)?.let { envelope(it) }

    /** 全量分析：输入摘要=原始快照sha256+参数json；同摘要命中缓存即复用历史计算。 */
    fun analyze(snapshotId: String, paramsVersion: Int? = null, parentEventSeq: Int? = null):
        ServiceResult<ComputedView> {
        val snap = db.loadSnapshot(snapshotId)
            ?: return ServiceResult.FormatCorrupt(listOf(Diagnostic(0, Severity.ERROR, "快照不存在: $snapshotId", "")))
        val pv = paramsVersion ?: currentParamsVersion()
        val params = paramsAt(pv)
        db.findComputation("FULL_ANALYSIS", snapshotId, pv)?.let { row ->
            val body = reviewJson.parseToJsonElement(row.resultJson).jsonObject
            val analysis = body["analysis"]?.let {
                reviewJson.decodeFromJsonElement(GraphAnalysis.FullAnalysis.serializer(), it)
            }
            return when (row.status) {
                "OK" -> ServiceResult.Ok(ComputedView("OK", row.computationId, row.kind, snapshotId, pv,
                    row.inputDigest, row.eventSeq, true, analysis))
                "BUDGET_EXHAUSTED" -> ServiceResult.BudgetExhausted(
                    body["budget"]!!.jsonPrimitive.content, body["detail"]!!.jsonPrimitive.content)
                "FORMAT_CORRUPT" -> ServiceResult.FormatCorrupt(
                    reviewJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Diagnostic.serializer()), body["diagnostics"]!!))
                else -> ServiceResult.SemanticallyUnsolvable(
                    (body["reasons"] as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content })
            }
        }
        val rawSha = db.rawSnapshot(snapshotId)!!.second
        val paramsJson = reviewJson.encodeToString(AnalysisParams.serializer(), params)
        val inputDigest = sha256("full-analysis:v$pv:$rawSha:$paramsJson")
        val result = GraphAnalysis.analyze(snap, params)
        val (status, resultJson) = when (result) {
            is AnalysisResult.Ok -> "OK" to buildJsonObject {
                put("analysis", kotlinx.serialization.json.Json.parseToJsonElement(
                    reviewJson.encodeToString(GraphAnalysis.FullAnalysis.serializer(), result.value)))
            }.toString()
            is AnalysisResult.BudgetExhausted -> "BUDGET_EXHAUSTED" to buildJsonObject {
                put("budget", result.budget); put("detail", result.detail)
            }.toString()
            is AnalysisResult.FormatCorrupt -> "FORMAT_CORRUPT" to buildJsonObject {
                put("diagnostics", kotlinx.serialization.json.Json.parseToJsonElement(
                    reviewJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(Diagnostic.serializer()), result.diagnostics)))
            }.toString()
            is AnalysisResult.SemanticallyUnsolvable -> "SEMANTICALLY_UNSOLVABLE" to buildJsonObject {
                put("reasons", kotlinx.serialization.json.JsonArray(result.reasons.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }.toString()
        }
        val eventPayload = buildJsonObject {
            put("kind", "FULL_ANALYSIS"); put("snapshotId", snapshotId); put("paramsVersion", pv)
            put("inputDigest", inputDigest); put("status", status); put("parentEventSeq", parentEventSeq)
        }
        val ev = db.appendEvent("COMPUTED", eventPayload.toString(), parentSeq = parentEventSeq)
        val comp = Database.ComputationRow(
            computationId = "C%04d".format(ev.seq), kind = "FULL_ANALYSIS", snapshotId = snapshotId,
            paramsVersion = pv, paramsJson = paramsJson, inputDigest = inputDigest,
            parentEventSeq = parentEventSeq, status = status, resultJson = resultJson,
            eventSeq = ev.seq, createdAt = ev.createdAt,
        )
        db.insertComputation(comp)
        return when (result) {
            is AnalysisResult.Ok -> ServiceResult.Ok(ComputedView("OK", comp.computationId, comp.kind,
                snapshotId, pv, inputDigest, ev.seq, false, result.value))
            is AnalysisResult.BudgetExhausted -> ServiceResult.BudgetExhausted(result.budget, result.detail)
            is AnalysisResult.FormatCorrupt -> ServiceResult.FormatCorrupt(result.diagnostics)
            is AnalysisResult.SemanticallyUnsolvable -> ServiceResult.SemanticallyUnsolvable(result.reasons)
        }
    }

    @Serializable
    data class DiffView(
        val computationId: String, val eventSeq: Int, val paramsVersion: Int,
        val inputDigest: String, val diff: ClassDiff.DiffResult,
    )

    fun diff(snapshotA: String, snapshotB: String, paramsVersion: Int? = null): ServiceResult<DiffView> {
        val a = db.loadSnapshot(snapshotA)
            ?: return ServiceResult.FormatCorrupt(listOf(Diagnostic(0, Severity.ERROR, "快照不存在: $snapshotA", "")))
        val b = db.loadSnapshot(snapshotB)
            ?: return ServiceResult.FormatCorrupt(listOf(Diagnostic(0, Severity.ERROR, "快照不存在: $snapshotB", "")))
        val pv = paramsVersion ?: currentParamsVersion()
        val digest = sha256("class-diff:v$pv:${db.rawSnapshot(snapshotA)!!.second}:${db.rawSnapshot(snapshotB)!!.second}")
        val result = ClassDiff.diff(a, b)
        val eventPayload = buildJsonObject {
            put("kind", "CLASS_DIFF"); put("snapshotId", snapshotA); put("snapshotB", snapshotB)
            put("paramsVersion", pv); put("inputDigest", digest); put("status", "OK")
        }
        val ev = db.appendEvent("COMPUTED", eventPayload.toString())
        val resultJson = buildJsonObject {
            put("diff", kotlinx.serialization.json.Json.parseToJsonElement(
                reviewJson.encodeToString(ClassDiff.DiffResult.serializer(), result)))
        }.toString()
        val comp = Database.ComputationRow("C%04d".format(ev.seq), "CLASS_DIFF", "$snapshotA|$snapshotB",
            pv, reviewJson.encodeToString(AnalysisParams.serializer(), paramsAt(pv)), digest,
            null, "OK", resultJson, ev.seq, ev.createdAt)
        db.insertComputation(comp)
        return ServiceResult.Ok(DiffView(comp.computationId, ev.seq, pv, digest, result))
    }

    @Serializable
    data class EventTimeline(
        val at: EventEnvelope,
        val pinnedComputation: ComputedView? = null,
        val note: String,
    )

    /** 回到任意旧事件：返回当时钉住的参数版本和存储中的可见结论，不用最新结果覆盖历史。 */
    fun eventTimeline(seq: Int): ServiceResult<EventTimeline> {
        val ev = db.getEvent(seq)
            ?: return ServiceResult.FormatCorrupt(listOf(Diagnostic(0, Severity.ERROR, "事件不存在: E$seq", "")))
        val pinned = db.computationForEvent(seq)
        var view: ComputedView? = null
        if (pinned != null && pinned.kind == "FULL_ANALYSIS") {
            val body = reviewJson.parseToJsonElement(pinned.resultJson).jsonObject
            view = if (pinned.status == "OK") {
                ComputedView("OK", pinned.computationId, pinned.kind, pinned.snapshotId, pinned.paramsVersion,
                    pinned.inputDigest, pinned.eventSeq, true,
                    reviewJson.decodeFromJsonElement(GraphAnalysis.FullAnalysis.serializer(), body["analysis"]!!))
            } else {
                ComputedView(pinned.status, pinned.computationId, pinned.kind, pinned.snapshotId,
                    pinned.paramsVersion, pinned.inputDigest, pinned.eventSeq, true, error = body)
            }
        }
        return ServiceResult.Ok(EventTimeline(envelope(ev), view,
            "该视图恢复事件发生时的参数版本与已保存结论；材料不可变，结论只追加不覆盖"))
    }

    @Serializable
    data class SnapshotRecord(
        val snapshotId: String, val parentSnapshotId: String?, val sourceName: String,
        val parserVersion: String, val capturedAt: String, val epochMillis: Long,
        val rawText: String, val rawSha256: String, val importEventSeq: Int,
        val snapshot: HeapSnapshot,
    )

    @Serializable
    data class ComputationRecord(
        val computationId: String, val kind: String, val snapshotId: String,
        val paramsVersion: Int, val paramsJson: String, val inputDigest: String,
        val parentEventSeq: Int?, val status: String, val resultJson: String,
        val eventSeq: Int, val createdAt: String,
    )

    @Serializable
    data class ExportBundle(
        val format: String,
        val schemaVersion: Int,
        val exportedAt: String,
        val currentParamsVersion: Int,
        val events: List<EventEnvelope>,
        val snapshots: List<SnapshotRecord>,
        val computations: List<ComputationRecord>,
    )

    /** 导出：完整项目（材料、事件、决定、计算）一个 JSON；临时文件+原子改名由调用方负责。 */
    fun exportProject(): ExportBundle {
        val snaps = db.listSnapshots().map { s ->
            val raw = db.rawSnapshot(s.snapshotId)!!
            SnapshotRecord(s.snapshotId, s.parentSnapshotId, s.sourceName, s.parserVersion,
                s.capturedAt, s.epochMillis, raw.first, raw.second,
                s.importEventSeq ?: 0, db.loadSnapshot(s.snapshotId)!!)
        }
        val comps = db.listComputations().map {
            ComputationRecord(it.computationId, it.kind, it.snapshotId, it.paramsVersion,
                it.paramsJson, it.inputDigest, it.parentEventSeq, it.status, it.resultJson,
                it.eventSeq, it.createdAt)
        }
        return ExportBundle(
            format = "heapforensics-export",
            schemaVersion = 1,
            exportedAt = java.time.Instant.now().toString(),
            currentParamsVersion = currentParamsVersion(),
            events = events(),
            snapshots = snaps,
            computations = comps,
        )
    }

    fun exportProjectJson(): String =
        reviewJson.encodeToString(ExportBundle.serializer(), exportProject())

    /** 导入项目：必须在空库执行；任何损坏都整体回滚（半写恢复），库内容保持原状。 */
    fun importProject(jsonText: String): ServiceResult<String> {
        val bundle = try {
            reviewJson.decodeFromString(ExportBundle.serializer(), jsonText)
        } catch (t: Throwable) {
            return ServiceResult.FormatCorrupt(
                listOf(Diagnostic(0, Severity.ERROR, "导出包无法解析（半写/损坏）: ${t.message?.take(200)}", "")))
        }
        if (bundle.format != "heapforensics-export" || bundle.schemaVersion != 1) {
            return ServiceResult.FormatCorrupt(
                listOf(Diagnostic(0, Severity.ERROR, "导出包格式或版本不支持: ${bundle.format}/v${bundle.schemaVersion}", "")))
        }
        if (db.listEvents().isNotEmpty()) {
            return ServiceResult.FormatCorrupt(
                listOf(Diagnostic(0, Severity.ERROR, "目标库非空：为保证身份与顺序，只允许导入到空库", "")))
        }
        try {
            db.conn.autoCommit = false
            for (i in 1..bundle.currentParamsVersion) {
                val found = bundle.events.firstOrNull {
                    it.kind == "REVISE_PARAMS" && (it.payload["toVersion"]?.jsonPrimitive?.content?.toIntOrNull() == i)
                }
                val params = found?.payload?.get("params")?.let {
                    reviewJson.decodeFromJsonElement(AnalysisParams.serializer(), it)
                } ?: AnalysisParams(version = 1)
                db.setMeta("params_json_v$i", reviewJson.encodeToString(AnalysisParams.serializer(), params.copy(version = i)))
            }
            db.setMeta("params_version", bundle.currentParamsVersion.toString())
            for (e in bundle.events.sortedBy { it.seq }) {
                db.insertEventAt(Database.EventRow(e.seq, e.eventId, e.branch, e.parentSeq,
                    e.kind, e.createdAt, e.actor, e.payload.toString()))
            }
            for (s in bundle.snapshots) {
                if (sha256(s.rawText) != s.rawSha256) {
                    throw IllegalStateException("快照 ${s.snapshotId} 原始文本摘要不匹配")
                }
                db.insertSnapshot(s.snapshot, s.rawText, s.rawSha256, s.sourceName,
                    s.parentSnapshotId, s.importEventSeq)
            }
            for (c in bundle.computations.sortedBy { it.eventSeq }) {
                db.insertComputation(Database.ComputationRow(c.computationId, c.kind, c.snapshotId,
                    c.paramsVersion, c.paramsJson, c.inputDigest, c.parentEventSeq, c.status,
                    c.resultJson, c.eventSeq, c.createdAt))
            }
            db.conn.commit()
            return ServiceResult.Ok("已恢复 ${bundle.events.size} 个事件、${bundle.snapshots.size} 份快照、${bundle.computations.size} 条计算")
        } catch (t: Throwable) {
            db.conn.rollback()
            return ServiceResult.FormatCorrupt(
                listOf(Diagnostic(0, Severity.ERROR, "导入整体回滚: ${t.message?.take(200)}", "")))
        } finally {
            db.conn.autoCommit = true
        }
    }
}
