package heapsleuth.model

import kotlinx.serialization.Serializable

/** Edge kinds preserved verbatim from the source snapshot. */
enum class EdgeKind { STRONG, WEAK, INTERNAL }

/** Root classifications preserved verbatim from the source snapshot. */
enum class RootType { JNI_GLOBAL, JNI_LOCAL, THREAD_STACK, THREAD_OBJECT, SYSTEM_CLASS, NATIVE_STACK, BUSY_MONITOR, OTHER }

@Serializable
data class ClassMeta(
    val id: String,               // stable class id inside its snapshot
    val classKey: String,         // cross-snapshot identity key (survives renames)
    val name: String,
    val superName: String? = null,
    val instanceSizeBytes: Long? = null,   // null when raw value could not be converted
    val instanceSizeRaw: String? = null    // original representation, always preserved
)

@Serializable
data class HeapObject(
    val id: String,               // ORIGINAL object id from the snapshot = stable identity
    val address: String,          // raw address, NOT an identity (duplicates allowed)
    val classId: String?,
    val sizeBytes: Long?,         // null when raw value could not be converted
    val sizeRaw: String,          // original representation, always preserved
    val allocationSite: String? = null,
    val incomplete: Boolean = false
)

@Serializable
data class HeapEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val name: String? = null
)

@Serializable
data class HeapRoot(
    val objectId: String,
    val rootType: RootType
)

@Serializable
data class IncompleteBlock(
    val offset: String,           // raw offset representation, preserved
    val lengthBytes: Long?,
    val lengthRaw: String,
    val reason: String
)

@Serializable
data class HeapSnapshot(
    val snapshotId: String,
    val parserVersion: String,
    val classes: List<ClassMeta>,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val roots: List<HeapRoot>,
    val incompleteBlocks: List<IncompleteBlock>
)

/** A diagnostic always carries a position (JSON path) and is never silently dropped. */
@Serializable
data class Diagnostic(
    val path: String,
    val code: String,
    val message: String
)

/** Versioned analysis parameters. weakKeepAlive is an EXPLICIT policy decision. */
@Serializable
data class AnalysisParams(
    val paramVersion: String,
    val weakKeepAlive: Boolean = false,
    val internalEdgesStrong: Boolean = true,
    val maxRootPathsPerObject: Int = 16,
    val maxObjects: Int = 100_000,
    val maxEdges: Int = 500_000
)

/** Machine-readable judgment codes emitted as events. */
enum class JudgmentCode {
    DOMINATOR_REACHABLE_ONLY,     // dominator tree built on reachable graph only
    WEAK_POLICY_EXPLICIT,         // weak-ref liveness decided by explicit policy
    EQUAL_LENGTH_PATHS_KEPT,      // multiple equal-length root paths preserved
    ADDRESS_NOT_IDENTITY,         // address is not used as stable identity
    CYCLE_HANDLED,                // cyclic graph traversed without divergence
    DUPLICATE_ADDRESS,            // duplicate addresses kept as distinct identities
    INCOMPLETE_OBJECT_KEPT,       // incomplete object preserved with diagnostics
    CLASS_RENAME_MATCHED,         // class rename matched via classKey across snapshots
    UNCONVERTIBLE_VALUE_KEPT,     // unconvertible value left in place with diagnostic
    DECISION_CONFLICT             // conflicting human decisions both retained
}
