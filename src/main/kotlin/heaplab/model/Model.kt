package heaplab.model

import kotlinx.serialization.Serializable

const val PARSER_NAME = "heaplab-hsnap-parser"
const val PARSER_VERSION = "1.4.0"

/** Failure taxonomies. The three outcomes must never collapse into one. */
enum class DiagnosticKind { FORMAT_CORRUPT, BUDGET_EXHAUSTED, SEMANTIC_UNSOLVABLE, WARNING }

@Serializable
data class Diagnostic(
    val kind: DiagnosticKind,
    val code: String,
    val message: String,
    /** 1-based line, 1-based column, 0-based byte offset into the original source. */
    val line: Int,
    val column: Int,
    val offset: Long,
    val blockId: String? = null,
    val fieldKey: String? = null,
    val rawValue: String? = null,
)

@Serializable
data class Field(val key: String, val rawValue: String)

/** Immutable raw-layer entity. Nothing here is normalized; addresses/units stay textual. */
@Serializable
data class RawObject(
    val objectId: String,
    val address: String,
    val classId: String,
    val sizeRaw: String,
    val unitRaw: String,
    val siteId: String? = null,
    val fields: List<Field> = emptyList(),
    val complete: Boolean = true,
    val diagnostics: List<Diagnostic> = emptyList(),
)

@Serializable
data class RawClass(
    val classId: String,
    val name: String,
    val superClassId: String? = null,
    val classLoaderId: String? = null,
    val nameAtCapture: String = name,
)

@Serializable
data class RawEdge(
    val edgeId: String,
    val kind: String, // STRONG | WEAK | INTERNAL
    val fromObjectId: String,
    val toObjectId: String,
    val label: String? = null,
)

@Serializable
data class RawRoot(
    val rootId: String,
    val category: String, // STICKY | JNI_LOCAL | JNI_GLOBAL | SYSTEM_CLASS | THREAD | MONITOR | UNKNOWN
    val objectId: String,
)

@Serializable
data class RawAllocationSite(
    val siteId: String,
    val location: String,
    val line: Int? = null,
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    val kind: String,
    val startedLine: Int,
    val endedLine: Int?,
    val rawText: String,
    val diagnostic: Diagnostic,
)

/**
 * The raw layer. Every original id/address/class/edge/root/block and the parser
 * identity is preserved verbatim. `sourceSha256` anchors provenance.
 */
@Serializable
data class ParsedSnapshot(
    val snapshotId: String,
    val capturedAt: String,
    val formatVersion: String,
    val parserName: String,
    val parserVersion: String,
    val sourceSha256: String,
    val sourceByteCount: Long,
    val objects: List<RawObject>,
    val classes: List<RawClass>,
    val edges: List<RawEdge>,
    val roots: List<RawRoot>,
    val sites: List<RawAllocationSite>,
    val incomplete: List<IncompleteBlock>,
    val diagnostics: List<Diagnostic>,
    val accepted: Boolean,
)

/** One of four distinct outcomes (success plus the three required failures). */
sealed class ParseOutcome {
    data class Success(val snapshot: ParsedSnapshot) : ParseOutcome()
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : ParseOutcome()
    data class BudgetExhausted(
        val diagnostics: List<Diagnostic>,
        val consumedBytes: Long,
        val budgetBytes: Long,
    ) : ParseOutcome()
    data class SemanticUnsolvable(val diagnostics: List<Diagnostic>) : ParseOutcome()
}
