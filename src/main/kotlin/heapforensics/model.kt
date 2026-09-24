package heapforensics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Kinds of references between heap objects. */
enum class EdgeKind { STRONG, WEAK, INTERNAL }

/** Root categories reported by the runtime. */
enum class RootType {
    JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK, SYSTEM_CLASS,
    THREAD, BUSY_MONITOR, FINALIZER, UNFINALIZED, UNKNOWN
}

/** Explicit policy deciding whether weak references keep targets alive. */
enum class WeakPolicy(val id: String) {
    WEAK_KEEP_ALIVE("weak-keep-alive"),
    WEAK_IGNORE("weak-ignore");
    companion object { fun byId(id: String) = entries.first { it.id == id } }
}

/** Severity of a parse/normalization diagnostic. */
enum class Severity { ERROR, WARNING }

/** A diagnostic always carries a position path into the raw document. */
@Serializable
data class Diagnostic(val severity: String, val position: String, val message: String)

// ---------- Raw material layer (immutable, exactly as imported) ----------

@Serializable
data class AllocationSite(val file: String? = null, val line: Int? = null, val method: String? = null)

@Serializable
data class RawClass(
    val classId: String,
    val name: String,
    val superClassId: String? = null,
    val classLoaderId: String? = null,
    val metadata: JsonObject = JsonObject(emptyMap())
)

@Serializable
data class RawObject(
    val objectId: String,
    val address: String,            // raw text, e.g. "0x7f00" or "??"; never an identity
    val classId: String? = null,    // null => incomplete object
    val sizeBytes: String? = null,  // raw text, normalized only in derived layer
    val allocationSite: AllocationSite? = null,
    val incomplete: Boolean = false,
    val note: String? = null
)

@Serializable
data class RawEdge(val from: String, val to: String, val kind: String, val label: String? = null)

@Serializable
data class RawRoot(val objectId: String, val rootType: String)

@Serializable
data class IncompleteChunk(val offset: Long, val length: Long, val reason: String)

@Serializable
data class RawSnapshot(
    val snapshotId: String,
    val parserVersion: String,
    val capturedAtEpochMs: Long,
    val source: String = "unknown",
    val classes: List<RawClass> = emptyList(),
    val objects: List<RawObject> = emptyList(),
    val edges: List<RawEdge> = emptyList(),
    val roots: List<RawRoot> = emptyList(),
    val incompleteChunks: List<IncompleteChunk> = emptyList()
)

// ---------- Derived layer (normalized values; raw stays untouched) ----------

data class DerivedObject(
    val raw: RawObject,
    val sizeBytes: Long?,        // null when unconvertible; raw value stays in place
    val addressValue: Long?,     // parsed hex/dec address, or null
    val diagnostics: List<Diagnostic>
)

data class DerivedSnapshot(
    val raw: RawSnapshot,
    val objects: Map<String, DerivedObject>,
    val edges: List<Pair<RawEdge, EdgeKind?>>,   // null kind => unconvertible, kept raw
    val roots: List<Pair<RawRoot, RootType?>>,
    val diagnostics: List<Diagnostic>
)
