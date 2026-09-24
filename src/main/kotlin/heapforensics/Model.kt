package heapforensics

import kotlinx.serialization.Serializable

/** Edge kinds in the heap graph. Weak edges only keep objects alive when the
 *  explicit [WeakPolicy] says so; internal edges are JVM-internal strong refs. */
enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class RootKind { JNI_GLOBAL, JNI_LOCAL, THREAD_FRAME, SYSTEM_CLASS, MONITOR, NATIVE_STACK, OTHER }

/** Explicit policy deciding whether weak references keep their targets alive.
 *  This is a versioned analysis parameter, never a hidden default. */
enum class WeakPolicy { KEEP_ALIVE, COLLECT }

/** The three distinct failure outcomes. Never collapsed into one another and
 *  never masked by null/default values. */
enum class FailureKind { BUDGET_EXHAUSTED, FORMAT_CORRUPT, SEMANTIC_UNSOLVABLE }

@Serializable
data class Diagnostic(val path: String, val message: String, val severity: String = "ERROR")

@Serializable
data class RawValue(
    val raw: String,                 // original representation, preserved verbatim
    val unit: String? = null,        // e.g. "bytes", "hex-address", "epoch-ms"
    val normalized: Double? = null,  // derived-layer normalization; null when unconvertible
    val diagnostic: String? = null,  // why it could not be converted, stays in place
)

@Serializable
data class GClass(
    val classId: String,             // stable identity across snapshots (rename-safe)
    val name: String,                // display name of this snapshot's version
    val superClassId: String? = null,
    val classLoaderId: String? = null,
)

@Serializable
data class GObject(
    val objectId: String,            // original object id from the snapshot file = stable identity
    val address: RawValue,           // address is evidence, NEVER identity
    val classId: String,
    val shallowSize: RawValue,       // carries unit (bytes)
    val allocationSite: String? = null,  // "Class.method(File.java:42)"
    val incomplete: Boolean = false, // object record truncated in source
)

@Serializable
data class GEdge(
    val fromId: String,
    val toId: String,
    val kind: EdgeKind,
    val label: String = "",          // field name / array index
)

@Serializable
data class GRoot(
    val objectId: String,
    val kind: RootKind,
    val description: String = "",
)

@Serializable
data class IncompleteBlock(
    val offset: Long,                // byte offset in the source file (coordinate basis)
    val length: Long,
    val reason: String,
)

@Serializable
data class ParserVersion(val name: String, val version: String)

@Serializable
data class HeapSnapshot(
    val snapshotId: String,
    val capturedAt: RawValue,        // epoch-ms with time basis
    val parser: ParserVersion,
    val classes: List<GClass>,
    val objects: List<GObject>,
    val edges: List<GEdge>,
    val roots: List<GRoot>,
    val incompleteBlocks: List<IncompleteBlock>,
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** A versioned parameter set for derived computations. */
@Serializable
data class ParamSet(
    val version: Int,
    val weakPolicy: WeakPolicy,
    val pathBudget: Int = 10_000,    // max equal-length paths enumerated
    val nodeBudget: Int = 1_000_000,
)

sealed class AnalysisOutcome<out T> {
    data class Ok<T>(val value: T) : AnalysisOutcome<T>()
    data class Failed(val kind: FailureKind, val diagnostics: List<Diagnostic>) : AnalysisOutcome<Nothing>()
}

@Serializable
data class RootPath(val rootDescription: String, val rootKind: RootKind, val hops: List<String>)

@Serializable
data class ObjectAnalysis(
    val objectId: String,
    val reachable: Boolean,
    val dominatorParent: String? = null,   // null for roots / unreachable
    val retainedBytes: Long = 0,
    val rootPaths: List<RootPath> = emptyList(),  // ALL shortest equal-length paths
)

@Serializable
data class ClassGroup(
    val classId: String,
    val name: String,
    val instanceCount: Int,
    val shallowBytes: Long,
    val retainedBytes: Long,
)

@Serializable
data class ClassGroupDiff(
    val classId: String,
    val nameA: String?, val nameB: String?,     // both kept: rename shows up here
    val renamed: Boolean,
    val countA: Int, val countB: Int,
    val retainedA: Long, val retainedB: Long,
)

@Serializable
data class AnalysisResult(
    val snapshotId: String,
    val paramVersion: Int,
    val weakPolicy: WeakPolicy,
    val inputDigest: String,          // sha-256 of canonical immutable input
    val parentEventIds: List<Long>,   // provenance: events this result depends on
    val objects: List<ObjectAnalysis>,
    val classGroups: List<ClassGroup>,
    val diagnostics: List<Diagnostic>,
)
