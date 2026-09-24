package heapfx.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Edge kinds preserved from the raw snapshot. */
enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class RootType { JNI_GLOBAL, JNI_LOCAL, THREAD, CLASS_LOADER, SYSTEM, MONITOR, UNKNOWN }

/** Computation outcomes. The three failure modes are deliberately distinct. */
enum class ComputeStatus { OK, BUDGET_EXHAUSTED, FORMAT_CORRUPT, SEMANTIC_UNSOLVABLE }

enum class DecisionVerdict { ACCEPT, REJECT, ADJUST }

enum class Severity { INFO, WARNING, ERROR }

/** A diagnostic always carries a position into the raw input. */
@Serializable
data class Diagnostic(
    val severity: String,
    val position: String,
    val message: String,
)

/** Raw import payload. Raw representations (ids, addresses, sizes with units) are kept verbatim. */
@Serializable
data class SnapshotPayload(
    val snapshotId: String,
    val name: String,
    val parserVersion: String,
    val capturedAt: String,
    val classes: List<ClassPayload> = emptyList(),
    val objects: List<ObjectPayload> = emptyList(),
    val edges: List<EdgePayload> = emptyList(),
    val roots: List<RootPayload> = emptyList(),
    val incompleteBlocks: List<IncompleteBlockPayload> = emptyList(),
)

@Serializable
data class ClassPayload(
    val classId: String,
    val name: String,
    val superName: String? = null,
    val classLoader: String? = null,
)

@Serializable
data class ObjectPayload(
    val id: String,
    val address: String,
    val classId: String,
    /** Raw size with unit, e.g. "64 B", "1.5 KiB". Normalization happens only in the derived layer. */
    val size: String,
    val allocSite: String? = null,
    val incomplete: Boolean = false,
)

@Serializable
data class EdgePayload(
    val from: String,
    val to: String,
    val kind: String,
    val name: String,
)

@Serializable
data class RootPayload(
    val `object`: String,
    val type: String,
)

@Serializable
data class IncompleteBlockPayload(
    val blockId: String,
    val reason: String,
    val affectedObjects: List<String> = emptyList(),
)

/** Versioned analysis policy. Weak references keep targets alive only when explicitly listed. */
@Serializable
data class Policy(
    val policyVersion: String,
    val weakKeepAlive: List<String> = emptyList(),
    val treatInternalAsStrong: Boolean = true,
    val leakThresholdBytes: Long = 1024,
    val maxNodes: Int = 100_000,
    val maxSteps: Int = 1_000_000,
    val maxRootPathsPerObject: Int = 64,
)

@Serializable
data class RootPath(
    val objectKey: String,
    val pathIndex: Int,
    /** Object keys from root to the object, inclusive. */
    val nodes: List<String>,
    val rootType: String,
)

@Serializable
data class DerivedObject(
    val objectKey: String,
    val reachable: Boolean,
    val sizeBytes: Long?,
    val retainedBytes: Long,
    val dominatorParent: String?,
    val depth: Int,
)

@Serializable
data class ComputeResult(
    val computeId: String,
    val snapshotId: String,
    val policyVersion: String,
    val inputDigest: String,
    val status: ComputeStatus,
    val objects: List<DerivedObject> = emptyList(),
    val rootPaths: List<RootPath> = emptyList(),
    val leakCandidates: List<String> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
)

/** Class identity across snapshots is the classId, never the name or an address. */
@Serializable
data class ClassGroupDiff(
    val classId: String,
    val nameInA: String?,
    val nameInB: String?,
    val countA: Int,
    val countB: Int,
    val bytesA: Long,
    val bytesB: Long,
    val relation: String, // MATCHED | RENAMED | ONLY_IN_A | ONLY_IN_B
)

@Serializable
data class FxEvent(
    val eventId: String,
    val seq: Long,
    val ts: String,
    val kind: String,
    val payload: JsonObject,
    /** Dependency versions + visible conclusions frozen at event time. */
    val view: JsonObject,
)
