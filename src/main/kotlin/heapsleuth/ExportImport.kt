package heapsleuth

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ExportFile(
    val format: String = "heap-sleuth-export",
    val version: Int = 1,
    val exportedAt: String,
    val snapshots: List<SnapshotBundle>,
    val snapshotSources: Map<String, String> = emptyMap(),
    val runs: List<ExportedRun>,
    val events: List<JournalEvent>,
    val decisions: List<Decision>,
    val archiveMeta: ExportedArchiveMeta? = null,
)

@Serializable
data class ExportedRun(
    val runId: String,
    val snapshotId: String,
    val params: AnalysisParams,
    val inputDigest: String,
    val parentRunId: String?,
    val outcome: RunOutcome,
    val seq: Long,
    val createdAt: String,
)

@Serializable
data class ExportedArchiveMeta(val caseId: String, val title: String, val baselineSnapshotId: String, val json: String)

sealed interface ImportOutcome {
    data class Ok(val importedSnapshots: Int, val importedRuns: Int, val importedEvents: Int, val skipped: Int) : ImportOutcome
    data class Corrupt(val diagnostics: List<Diagnostic>) : ImportOutcome
    data class Conflict(val diagnostics: List<Diagnostic>) : ImportOutcome
}

object ExportImport {

    fun export(store: Store): String {
        val snapshots = store.listSnapshots().map { it.canonical() }
        val runs = store.listRuns().map { row ->
            val (params, outcome, full) = store.loadRun(row.runId)!!
            ExportedRun(full.runId, full.snapshotId, params, full.inputDigest, full.parentRunId,
                outcome, full.seq, full.createdAt)
        }
        val meta = store.loadArchiveMeta()?.let { (caseId, title, json) ->
            val baseline = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject["baselineSnapshotId"]
                    ?.toString()?.trim('"') ?: ""
            }.getOrDefault("")
            ExportedArchiveMeta(caseId, title, baseline, json)
        }
        val file = ExportFile(
            exportedAt = store.now(),
            snapshots = snapshots,
            runs = runs,
            events = store.listEvents(),
            decisions = store.listDecisions(),
            archiveMeta = meta,
        )
        return CanonicalJson.encodeToString(ExportFile.serializer(), file)
    }

    fun import(store: Store, jsonText: String): ImportOutcome {
        val file: ExportFile = try {
            CanonicalJson.decodeFromString(ExportFile.serializer(), jsonText)
        } catch (e: Exception) {
            return ImportOutcome.Corrupt(listOf(
                Diagnostic("/", "FORMAT_CORRUPT", "导出文件解析失败: ${e.message}", Severity.ERROR)))
        }
        if (file.format != "heap-sleuth-export") {
            return ImportOutcome.Corrupt(listOf(
                Diagnostic("/format", "FORMAT_CORRUPT", "未知格式 '${file.format}'", Severity.ERROR)))
        }
        // 身份冲突检查：同 id 但内容不同 => 冲突，不覆盖。
        val conflicts = mutableListOf<Diagnostic>()
        file.snapshots.forEach { b ->
            val existing = store.snapshotDigest(b.snapshotId)
            if (existing != null && existing != bundleDigest(b.canonical())) {
                conflicts += Diagnostic("/snapshots/${b.snapshotId}", "IDENTITY_CONFLICT",
                    "快照 ${b.snapshotId} 已存在但内容摘要不同 ($existing != ${bundleDigest(b.canonical())})", Severity.ERROR)
            }
        }
        if (conflicts.isNotEmpty()) return ImportOutcome.Conflict(conflicts)

        var importedSnapshots = 0
        var importedRuns = 0
        var importedEvents = 0
        var skipped = 0
        store.sessionedWrite("import-${file.exportedAt}") {
            file.snapshots.forEach { b ->
                if (store.hasSnapshot(b.snapshotId)) skipped++
                else { store.saveSnapshot(b.canonical(), source = "import"); importedSnapshots++ }
            }
            file.runs.forEach { r ->
                if (store.loadRun(r.runId) != null) { skipped++; return@forEach }
                store.saveRunRaw(r.runId, r.snapshotId, r.params, r.inputDigest, r.parentRunId, r.outcome, r.seq, r.createdAt)
                importedRuns++
            }
            file.events.forEach { e ->
                if (store.loadEvent(e.eventId) != null) { skipped++; return@forEach }
                store.insertEvent(e); importedEvents++
            }
            file.decisions.forEach { d -> store.saveDecision(d) }
            file.archiveMeta?.let { m ->
                store.saveArchiveMeta(m.caseId, m.title, m.baselineSnapshotId, m.json)
            }
        }
        return ImportOutcome.Ok(importedSnapshots, importedRuns, importedEvents, skipped)
    }
}

