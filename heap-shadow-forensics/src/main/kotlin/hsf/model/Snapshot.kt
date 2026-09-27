package hsf.model

import kotlinx.serialization.Serializable

/**
 * HSP (Heap Shadow Profile) domain model.
 *
 * Layer 0 - immutable source material. Values are kept in their ORIGINAL
 * representation. Normalisation (parsing addresses/sizes, units) happens only
 * in the derived analysis layer; values that cannot be converted stay in place
 * with a [Diagnostic] instead of being dropped or defaulted.
 */

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
enum class RootKind {
    STICKY_CLASS, JAVA_LOCAL, JNI_GLOBAL, JNI_LOCAL, NATIVE_STACK,
    INTERNED_STRING, FINALIZING, DEBUGGER, REFERENCE_CLEANUP, VM_INTERNAL, UNCLASSIFIED
}

@Serializable
data class ClassMeta(
    val classId: String,
    val name: String,
    val nameRaw: String,
    val superclassId: String? = null,
    val classLoaderObjectId: String? = null,
    /** Stable name across renames, declared by the runtime when available. */
    val renameKey: String? = null,
    val nameHistory: List<String> = emptyList(),
    val fieldsRaw: List<String> = emptyList()
)

/**
 * A heap object. [objectId] is the snapshot's stable identity; [addressRaw] is
 * an address and MUST NOT be treated as a stable identity (objects can be
 * re-created at a reused address across snapshots).
 */
@Serializable
data class HeapObject(
    val objectId: String,
    val addressRaw: String?,
    val classId: String,
    val sizeRaw: String,
    val unit: String = "byte",
    val allocationSiteId: String? = null,
    val complete: Boolean = true,
    val chunkId: String? = null,
    val notes: String? = null
)

@Serializable
data class Edge(
    val edgeId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val kind: EdgeKind,
    val fieldRaw: String? = null,
    val chunkId: String? = null
)

@Serializable
data class RootRecord(
    val objectId: String,
    val kind: RootKind,
    val detailRaw: String? = null,
    val chunkId: String? = null
)

@Serializable
data class AllocationSite(
    val siteId: String,
    val location: String,
    val line: Int? = null,
    val classId: String? = null
)

@Serializable
data class IncompleteChunk(
    val chunkId: String,
    val reason: String,
    val firstLine: Int? = null,
    val lastLine: Int? = null
)

@Serializable
data class HeapSnapshot(
    val formatVersion: String,
    val parserVersion: String,
    val snapshotId: String,
    val capturedAtEpochMillis: Long? = null,
    val timeBasisRaw: String? = null,
    val addressBasis: String = "runtime-virtual-heap",
    val addressUnit: String = "byte",
    val classes: List<ClassMeta> = emptyList(),
    val objects: List<HeapObject> = emptyList(),
    val edges: List<Edge> = emptyList(),
    val roots: List<RootRecord> = emptyList(),
    val allocationSites: List<AllocationSite> = emptyList(),
    val incompleteChunks: List<IncompleteChunk> = emptyList(),
    /** Non-fatal diagnostics for material that was retained but not convertible. */
    val diagnostics: List<Diagnostic> = emptyList()
)
