package heaplab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val json = Json { prettyPrint = true; ignoreUnknownKeys = true; explicitNulls = false }

// ---------- Units / quantities ----------

@Serializable
data class Quantity(val value: Long, val unit: String) {
    fun render(): String = "$value $unit"
}

// ---------- Snapshot material (immutable layer) ----------

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
enum class RootType { THREAD, JNI_GLOBAL, JNI_LOCAL, MONITOR, CLASS_LOADER, SYSTEM, OTHER }

@Serializable
data class ClassInfo(val id: String, val name: String)

@Serializable
data class HeapObject(
    val id: String,                 // original object id from the snapshot file
    val address: String,            // raw textual address, preserved verbatim; NOT an identity
    val classId: String,
    val shallowSize: Quantity,
    val allocSite: String? = null,  // allocation site, raw form preserved
    val incomplete: Boolean = false // object came from an incomplete block
)

@Serializable
data class HeapEdge(val from: String, val to: String, val kind: EdgeKind, val name: String? = null)

@Serializable
data class HeapRoot(val objectId: String, val rootType: RootType)

@Serializable
data class HeapSnapshot(
    val snapshotId: String,         // stable snapshot identity
    val parserVersion: String,      // parser version preserved verbatim
    val capturedAt: String,         // time base for the snapshot (ISO-8601)
    val classes: List<ClassInfo> = emptyList(),
    val objects: List<HeapObject> = emptyList(),
    val edges: List<HeapEdge> = emptyList(),
    val roots: List<HeapRoot> = emptyList()
)

// ---------- Diagnostics ----------

@Serializable
enum class Severity { ERROR, WARNING }

@Serializable
data class Diagnostic(val position: String, val severity: Severity, val message: String)

// ---------- Operation results: three distinct failure kinds ----------

@Serializable
enum class FailureKind { BUDGET_EXHAUSTED, CORRUPT_FORMAT, SEMANTIC_UNSOLVABLE }

sealed interface OpResult<out T> {
    data class Ok<T>(val value: T) : OpResult<T>
    data class Failed(val kind: FailureKind, val diagnostics: List<Diagnostic>, val detail: String) : OpResult<Nothing>
}

fun <T> OpResult<T>.orNull(): T? = (this as? OpResult.Ok)?.value

// ---------- Parameters (versioned, mutable layer) ----------

@Serializable
enum class WeakPolicy { WEAK_NOT_ALIVE, WEAK_KEEP_ALIVE }

@Serializable
data class ParamSet(
    val version: Int,
    val weakPolicy: WeakPolicy,
    val maxObjects: Int = 100_000,
    val maxPathsPerObject: Int = 64
)

// ---------- Computation result (derived layer) ----------

@Serializable
data class RootPath(val rootObjectId: String, val rootType: RootType, val hops: List<String>) {
    fun render(): String = hops.joinToString(" -> ")
}

@Serializable
data class ObjectAnalysis(
    val objectId: String,
    val reachable: Boolean,
    val dominatorParent: String? = null,   // null => root of dominator forest or unreachable
    val retainedSize: Quantity? = null,    // only for reachable objects
    val rootPaths: List<RootPath> = emptyList() // ALL shortest equal-length paths, stable order
)

@Serializable
data class ComputationResult(
    val computationId: String,
    val snapshotId: String,
    val paramVersion: Int,
    val weakPolicy: WeakPolicy,
    val inputDigest: String,
    val parentComputationIds: List<String>,
    val analyses: List<ObjectAnalysis>,
    val unreachableCount: Int,
    val cycleDetected: Boolean,
    val duplicateAddresses: Map<String, List<String>>,
    val diagnostics: List<Diagnostic>
)

// ---------- Class group diff between two snapshots ----------

@Serializable
data class ClassGroup(
    val className: String,
    val snapshotId: String,
    val objectCount: Int,
    val totalShallow: Quantity,
    val matchedClassId: String? = null // identity inside its own snapshot
)

@Serializable
data class ClassDiff(
    val leftSnapshot: String,
    val rightSnapshot: String,
    val onlyLeft: List<ClassGroup>,
    val onlyRight: List<ClassGroup>,
    val common: List<Pair<ClassGroup, ClassGroup>>,
    val renameCandidates: List<Pair<String, String>> // (leftName, rightName) suspected renames
)

// ---------- Decisions (mutable, versioned) ----------

@Serializable
enum class DecisionStatus { ACCEPTED, REJECTED, CONFLICT }

@Serializable
data class Decision(
    val decisionId: String,
    val subject: String,          // e.g. "computation:cmp-1" or "leak-candidate:o7"
    val status: DecisionStatus,
    val rationale: String,
    val paramVersion: Int,
    val supersedes: String? = null,
    val createdSeq: Long = 0
)

// ---------- Machine-readable events ----------

@Serializable
data class Event(val seq: Long, val kind: String, val payload: String)
