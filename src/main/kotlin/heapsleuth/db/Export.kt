package heapsleuth.db

import heapsleuth.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Archive(
    val format: String,
    val formatVersion: Int,
    val exportedAt: String,
    val checksum: String,
    val snapshots: List<HeapSnapshot>,
    val snapshotMeta: List<SnapshotMetaRow>,
    val analyses: List<AnalysisRow>,
    val events: List<EventRow>,
    val decisions: List<DecisionRow>
)

@Serializable
data class SnapshotMetaRow(val snapshotId: String, val createdAt: String, val inputDigest: String,
                           val diagnostics: List<Diagnostic>)

@Serializable
data class AnalysisRow(val analysisId: String, val snapshotId: String, val params: AnalysisParams,
                       val inputDigest: String, val resultJson: String, val judgments: List<String>,
                       val staleReason: String?, val createdAt: String)

@Serializable
data class EventRow(val seq: Long, val eventId: String, val type: String, val payloadJson: String,
                    val inputDigest: String?, val paramVersion: String?, val parents: List<String>,
                    val createdAt: String)

@Serializable
data class DecisionRow(val decisionId: String, val targetId: String, val verdict: String,
                       val reviewer: String, val rationale: String, val eventSeq: Long, val createdAt: String)

sealed interface ImportResult {
    data class Ok(val snapshots: Int, val events: Int, val decisions: Int) : ImportResult
    data class FormatCorrupt(val reason: String) : ImportResult
    data class SemanticUnsolvable(val reason: String) : ImportResult
}

object ArchiveCodec {
    const val FORMAT = "heap-sleuth-archive"
    const val FORMAT_VERSION = 1
    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    fun export(db: Database): String {
        val snapshots = mutableListOf<HeapSnapshot>()
        val meta = mutableListOf<SnapshotMetaRow>()
        for (row in db.listSnapshots()) {
            snapshots += db.loadSnapshot(row.snapshotId)!!
            meta += SnapshotMetaRow(row.snapshotId, row.createdAt, row.inputDigest, row.diagnostics)
        }
        val analyses = db.listAnalyses().map {
            AnalysisRow(it.analysisId, it.snapshotId, it.params, it.inputDigest, it.resultJson, it.judgments, it.staleReason, it.createdAt)
        }
        val events = db.listEvents().map {
            EventRow(it.seq, it.eventId, it.type, it.payloadJson, it.inputDigest, it.paramVersion, it.parents, it.createdAt)
        }
        val decisions = db.listDecisions().map {
            DecisionRow(it.decisionId, it.targetId, it.verdict, it.reviewer, it.rationale, it.eventSeq, it.createdAt)
        }
        val body = Archive(FORMAT, FORMAT_VERSION, java.time.Instant.now().toString(), "",
            snapshots, meta, analyses, events, decisions)
        val checksum = sha256(pretty.encodeToString(Archive.serializer(), body))
        return pretty.encodeToString(Archive.serializer(), body.copy(checksum = checksum))
    }

    /** Import is atomic: any failure rolls back, leaving the local DB untouched (half-write recovery). */
    fun import(db: Database, text: String): ImportResult {
        val archive: Archive = try {
            json.decodeFromString(Archive.serializer(), text)
        } catch (e: Exception) {
            return ImportResult.FormatCorrupt("archive is not valid JSON or wrong shape: ${e.message}")
        }
        if (archive.format != FORMAT)
            return ImportResult.FormatCorrupt("expected format='$FORMAT', got '${archive.format}'")
        if (archive.formatVersion != FORMAT_VERSION)
            return ImportResult.FormatCorrupt("unsupported formatVersion ${archive.formatVersion}")
        val expected = sha256(pretty.encodeToString(Archive.serializer(), archive.copy(checksum = "")))
        if (expected != archive.checksum)
            return ImportResult.FormatCorrupt("checksum mismatch: file truncated or tampered")
        // semantic validation BEFORE writing anything
        val ids = archive.snapshots.map { it.snapshotId }
        if (ids.toSet().size != ids.size)
            return ImportResult.SemanticUnsolvable("duplicate snapshotId in archive: ${ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}")
        for (s in archive.snapshots) {
            val objIds = s.objects.map { it.id }
            if (objIds.toSet().size != objIds.size)
                return ImportResult.SemanticUnsolvable("snapshot ${s.snapshotId} has duplicate object ids")
        }
        val eventSeqs = archive.events.map { it.seq }
        if (eventSeqs.toSet().size != eventSeqs.size)
            return ImportResult.SemanticUnsolvable("duplicate event seq in archive")
        val knownEventIds = archive.events.map { it.eventId }.toSet()
        for (e in archive.events) for (p in e.parents)
            if (p !in knownEventIds)
                return ImportResult.SemanticUnsolvable("event ${e.eventId} references unknown parent $p")

        return try {
            db.tx {
                for (s in archive.snapshots) {
                    val m = archive.snapshotMeta.first { it.snapshotId == s.snapshotId }
                    db.insertSnapshot(s, m.diagnostics, json.encodeToString(HeapSnapshot.serializer(), s), m.inputDigest)
                }
                for (a in archive.analyses) {
                    db.insertAnalysis(Database.AnalysisRow(a.analysisId, a.snapshotId, a.params, a.inputDigest,
                        a.resultJson, a.judgments, a.staleReason, a.createdAt))
                }
                for (e in archive.events) {
                    db.insertEvent(e.type, e.eventId, e.payloadJson, e.inputDigest, e.paramVersion, e.parents,
                        seq = e.seq, createdAt = e.createdAt)
                }
                for (d in archive.decisions) {
                    db.insertDecision(Database.DecisionRow(d.decisionId, d.targetId, d.verdict, d.reviewer, d.rationale, d.eventSeq, d.createdAt))
                }
            }
            ImportResult.Ok(archive.snapshots.size, archive.events.size, archive.decisions.size)
        } catch (e: Exception) {
            ImportResult.SemanticUnsolvable("import rolled back, database unchanged: ${e.message}")
        }
    }
}
