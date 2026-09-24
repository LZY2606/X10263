package heapsleuth.events

import heapsleuth.analysis.*
import heapsleuth.db.*
import heapsleuth.ingest.*
import heapsleuth.model.*
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class AnalysisReport(
    val analysisId: String,
    val snapshotId: String,
    val params: AnalysisParams,
    val inputDigest: String,
    val reachable: List<String>,
    val unreachable: List<String>,
    val idom: Map<String, String>,
    val retainedBytes: Map<String, Long>,
    val rootPaths: Map<String, List<RootPathDto>>,
    val pathsTruncated: List<String>,
    val judgments: List<String>
)

@Serializable
data class RootPathDto(val rootObjectId: String, val rootType: String, val objectIds: List<String>)

sealed interface ImportOutcome {
    data class Ok(val snapshotId: String, val diagnostics: List<Diagnostic>, val eventSeq: Long) : ImportOutcome
    data class BudgetExhausted(val diagnostics: List<Diagnostic>) : ImportOutcome
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : ImportOutcome
    data class SemanticUnsolvable(val diagnostics: List<Diagnostic>) : ImportOutcome
}

class App(val db: Database) {

    private fun newId(prefix: String) = "$prefix-" + UUID.randomUUID().toString().substring(0, 8)

    fun digestOf(snapshot: HeapSnapshot): String =
        sha256(json.encodeToString(HeapSnapshot.serializer(), snapshot))

    /** Import a raw snapshot text. Immutable material: stored verbatim, never mutated later. */
    fun importSnapshot(text: String, params: AnalysisParams = AnalysisParams("v1")): ImportOutcome {
        return when (val r = HeapSnapshotParser.parse(text, params)) {
            is ParseResult.BudgetExhausted -> ImportOutcome.BudgetExhausted(r.diagnostics)
            is ParseResult.FormatCorrupt -> ImportOutcome.FormatCorrupt(r.diagnostics)
            is ParseResult.SemanticUnsolvable -> ImportOutcome.SemanticUnsolvable(r.diagnostics)
            is ParseResult.Success -> {
                val digest = digestOf(r.snapshot)
                db.insertSnapshot(r.snapshot, r.diagnostics, text, digest)
                val seq = db.insertEvent("IMPORT_SNAPSHOT", newId("evt"),
                    json.encodeToString(mapOf(
                        "snapshotId" to r.snapshot.snapshotId,
                        "parserVersion" to r.snapshot.parserVersion,
                        "objects" to r.snapshot.objects.size.toString(),
                        "edges" to r.snapshot.edges.size.toString(),
                        "roots" to r.snapshot.roots.size.toString(),
                        "incompleteBlocks" to r.snapshot.incompleteBlocks.size.toString(),
                        "diagnostics" to r.diagnostics.size.toString()
                    )), digest, null, emptyList())
                ImportOutcome.Ok(r.snapshot.snapshotId, r.diagnostics, seq)
            }
        }
    }

    /** Run analysis. Every run records input digest + param version + parent events. */
    fun runAnalysis(snapshotId: String, params: AnalysisParams, parents: List<String> = emptyList(),
                    analysisId: String = newId("ana")): Pair<AnalysisReport, Long>? {
        val snapshot = db.loadSnapshot(snapshotId) ?: return null
        val digest = digestOf(snapshot)
        val result = GraphEngine.analyze(snapshot, params)
        val report = AnalysisReport(
            analysisId, snapshotId, params, digest,
            result.reachable.sorted(), result.unreachable.sorted(),
            result.idom.toSortedMap(), result.retainedBytes.toSortedMap(),
            result.rootPaths.toSortedMap().mapValues { (_, ps) ->
                ps.map { RootPathDto(it.rootObjectId, it.rootType.name, it.objectIds) }
            },
            result.pathsTruncated.sorted(),
            result.judgments.map { it.name }
        )
        db.markStale(snapshotId, "superseded by paramVersion ${params.paramVersion}")
        db.insertAnalysis(Database.AnalysisRow(analysisId, snapshotId, params, digest,
            json.encodeToString(AnalysisReport.serializer(), report),
            report.judgments, null, Instant.now().toString()))
        val seq = db.insertEvent("RUN_ANALYSIS", newId("evt"),
            json.encodeToString(mapOf(
                "analysisId" to analysisId,
                "snapshotId" to snapshotId,
                "paramVersion" to params.paramVersion,
                "weakKeepAlive" to params.weakKeepAlive.toString(),
                "judgments" to report.judgments.joinToString(",")
            )), digest, params.paramVersion, parents)
        return report to seq
    }

    /** Human decision (accept / reject / param-adjust). Conflicts are kept, not merged. */
    fun decide(targetId: String, verdict: String, reviewer: String, rationale: String,
               parents: List<String> = emptyList()): Long {
        val existing = db.listDecisions().filter { it.targetId == targetId }
        val conflicting = existing.any { it.verdict != verdict }
        val seq = db.insertEvent("DECISION", newId("evt"),
            json.encodeToString(mapOf(
                "targetId" to targetId, "verdict" to verdict, "reviewer" to reviewer,
                "conflict" to conflicting.toString()
            )), null, null, parents)
        db.insertDecision(Database.DecisionRow(newId("dec"), targetId, verdict, reviewer, rationale,
            seq, Instant.now().toString()))
        if (conflicting) {
            db.insertEvent("JUDGMENT", newId("evt"),
                json.encodeToString(mapOf("code" to JudgmentCode.DECISION_CONFLICT.name, "targetId" to targetId)),
                null, null, listOf())
        }
        return seq
    }

    /** Class-group diff between two snapshots; rename matched via classKey. */
    fun classDiff(aId: String, bId: String): Pair<List<ClassGroup>, List<JudgmentCode>>? {
        val a = db.loadSnapshot(aId) ?: return null
        val b = db.loadSnapshot(bId) ?: return null
        return ClassDiff.diff(a, b)
    }

    fun recordJudgmentEvent(code: JudgmentCode, detail: Map<String, String>, digest: String? = null,
                            paramVersion: String? = null, parents: List<String> = emptyList()): String {
        val id = newId("evt")
        db.insertEvent("JUDGMENT", id,
            json.encodeToString(mapOf("code" to code.name) + detail), digest, paramVersion, parents)
        return id
    }

    fun recordEvent(type: String, detail: Map<String, String>, parents: List<String> = emptyList()): String {
        val id = newId("evt")
        db.insertEvent(type, id, json.encodeToString(detail), null, null, parents)
        return id
    }

    fun exportArchive(): String = ArchiveCodec.export(db)

    fun importArchive(text: String): ImportResult {
        val r = ArchiveCodec.import(db, text)
        if (r is ImportResult.Ok) {
            db.insertEvent("IMPORT_ARCHIVE", newId("evt"),
                json.encodeToString(mapOf("snapshots" to r.snapshots.toString(), "events" to r.events.toString())),
                null, null, emptyList())
        }
        return r
    }
}
