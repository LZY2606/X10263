package heapforensics

import kotlinx.serialization.Serializable

enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
data class Diagnostic(val position: String, val severity: String, val message: String)

@Serializable
data class ClassRec(val classId: String, val name: String)

@Serializable
data class ObjectRec(
    val objectId: String,
    val address: String,          // raw textual address, NOT an identity
    val classId: String,
    val sizeRaw: String,          // raw representation as imported
    val sizeBytes: Long?,         // null when not convertible; diagnostic attached
    val siteId: String? = null,
    val incomplete: Boolean = false,
    val incompleteReason: String? = null,
)

@Serializable
data class EdgeRec(val fromId: String, val toId: String, val kind: EdgeKind, val name: String)

@Serializable
data class RootRec(val objectId: String, val rootType: String)

@Serializable
data class SiteRec(val siteId: String, val className: String, val line: Int)

@Serializable
data class SnapshotData(
    val snapshotId: String,
    val name: String,
    val parserVersion: String,
    val rawText: String,
    val classes: List<ClassRec>,
    val objects: List<ObjectRec>,
    val edges: List<EdgeRec>,
    val roots: List<RootRec>,
    val sites: List<SiteRec>,
    val diagnostics: List<Diagnostic>,
)

@Serializable
enum class WeakPolicy { RETAIN_NONE, RETAIN_ALL }

@Serializable
data class Params(
    val version: Int,
    val weakPolicy: WeakPolicy,
    val budgetNodes: Int = 100_000,
    val budgetPaths: Int = 10_000,
    val note: String = "",
)

/** A root path: ordered object ids from a root to the target. */
@Serializable
data class RootPath(val rootType: String, val objectIds: List<String>) {
    fun render(): String = "[${rootType}] " + objectIds.joinToString(" -> ")
}

@Serializable
data class AnalysisResult(
    val analysisId: String,
    val snapshotId: String,
    val paramsVersion: Int,
    val inputDigest: String,
    val reachable: List<String>,                 // sorted for stable order
    val unreachable: List<String>,
    val idom: Map<String, String>,               // objectId -> immediate dominator ("<root>" for roots)
    val retainedBytes: Map<String, Long>,        // unit: bytes
    val sizeUnknown: List<String>,               // objects whose size stayed unconvertible
    val rootPaths: Map<String, List<RootPath>>,  // ALL equal-length shortest paths kept
    val judgments: List<Judgment>,               // machine-readable rule evidence
    val leakCandidates: List<LeakCandidate>,
)

@Serializable
data class Judgment(val rule: String, val detail: String, val affected: List<String> = emptyList())

@Serializable
data class LeakCandidate(
    val objectId: String,
    val retainedBytes: Long,
    val paths: List<RootPath>,
    val status: String,                          // "candidates-kept" when not uniquely decidable
)

@Serializable
data class ClassGroupDiff(
    val classId: String,
    val nameA: String?,
    val nameB: String?,
    val renamed: Boolean,
    val countA: Int,
    val countB: Int,
    val bytesA: Long,
    val bytesB: Long,
)

/** Three+ distinct outcome kinds: success / resource / format / semantic. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class ResourceExhausted(val resource: String, val limit: Int) : Outcome<Nothing>
    data class CorruptFormat(val diagnostics: List<Diagnostic>) : Outcome<Nothing>
    data class SemanticUnsolvable(val reason: String, val candidates: List<String> = emptyList()) : Outcome<Nothing>
}

@Serializable
data class EventRec(
    val eventId: String,
    val seq: Int,
    val kind: String,
    val payloadJson: String,
    val parentIds: List<String>,
    val analysisId: String? = null,
    val createdAt: String,
)

@Serializable
data class DecisionRec(
    val decisionId: String,
    val eventId: String,
    val objectId: String,
    val verdict: String,       // ACCEPT | REJECT | ADJUST
    val note: String,
)
