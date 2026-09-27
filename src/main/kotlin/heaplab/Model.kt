package heaplab

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Edge kinds preserved verbatim from the source snapshot. */
enum class EdgeKind { STRONG, WEAK, INTERNAL }

/** Explicit policy deciding whether weak references keep objects alive. */
enum class WeakPolicy { WEAK_KEEP_ALIVE, WEAK_IGNORE }

/** Normalized root classification; the raw source token is always kept alongside. */
enum class RootClass { THREAD, JNI_GLOBAL, CLASS_LOADER, STACK_LOCAL, MONITOR, OTHER }

@Serializable
data class ClassInfo(
    val snapshotId: String,
    val classId: String,          // stable class identity inside a snapshot lineage
    val name: String,             // may be renamed across snapshots; identity stays classId
    val superName: String? = null,
)

@Serializable
data class HeapObject(
    val snapshotId: String,
    val objectId: String,         // original object id, preserved verbatim
    val address: String,          // raw address as text; NOT a stable identity
    val classId: String?,         // null => incomplete object, kept in place with diagnostic
    val sizeBytes: Long?,         // null => incomplete; never defaulted
    val allocSite: String? = null // allocation site as reported by the parser
)

@Serializable
data class HeapEdge(
    val snapshotId: String,
    val edgeId: String,
    val fromId: String,
    val toId: String,
    val kind: EdgeKind,
    val label: String = ""
)

@Serializable
data class HeapRoot(
    val snapshotId: String,
    val objectId: String,
    val rootTypeRaw: String,      // raw classification token from the snapshot
    val rootClass: RootClass
)

@Serializable
data class IncompleteBlock(
    val snapshotId: String,
    val blockId: String,
    val position: String,         // where in the source the block broke
    val raw: String,              // original representation, preserved
    val reason: String
)

@Serializable
data class Snapshot(
    val id: String,
    val parserVersion: String,    // parser version is part of the preserved baseline
    val source: String,
    val importedAtEpochMs: Long
)

@Serializable
data class Diagnostic(
    val code: String,
    val severity: String,         // INFO | WARN | ERROR
    val position: String,         // e.g. "object:o7", "edge:e3", "block:b1", "line:42"
    val message: String
)

/** Analysis parameters; every change bumps paramsVersion so events pin their rule version. */
@Serializable
data class AnalysisParams(
    val paramsVersion: String,
    val weakPolicy: WeakPolicy,
    val maxObjects: Int = 100_000,
    val maxEdges: Int = 500_000,
    val maxPathCandidates: Int = 64,
    val maxDomIterations: Int = 10_000
)

/** A machine-readable judgment event. Stored, replayable, never overwritten. */
@Serializable
data class LabEvent(
    val eventId: String,
    val seq: Long,
    val kind: String,             // IMPORT | ANALYSIS | DECISION_ACCEPT | DECISION_REJECT | PARAM_ADJUST | EXPORT | IMPORT_RESTORE | RECOVERY
    val snapshotIds: List<String>,
    val paramsVersion: String?,
    val inputDigest: String,
    val parentIds: List<String>,
    val status: String,           // OK | BUDGET_EXHAUSTED | FORMAT_CORRUPT | SEMANTIC_UNSOLVABLE | SUPERSEDED | CONFLICT
    val payloadJson: String,
    val createdAtEpochMs: Long
)

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
