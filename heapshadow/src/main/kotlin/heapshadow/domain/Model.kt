package heapshadow.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Immutable domain material imported from a heap snapshot.
 *
 * Layering rule (see case contract):
 *  - Layer 0 (raw): the exact imported bytes and per-record raw representations are never overwritten.
 *  - Layer 1 (normalized): typed views derived from layer 0. Values that cannot be converted stay at
 *    their original location with a [Diagnostic] instead of being silently replaced by null/default.
 */

@Serializable
data class UnitSpec(val code: String, val symbol: String, val kind: String) {
    companion object {
        val BYTES = UnitSpec("byte", "B", "size")
        val MILLIS = UnitSpec("iso8601-ms", "UTC", "time")
        val OBJECT_ID = UnitSpec("snapshot-local-id", "id", "identity")
        val ADDRESS = UnitSpec("native-hex-address", "0x", "address-not-identity")
        val COUNT = UnitSpec("count", "n", "cardinality")
    }
}

/** Coordinate/time basis carried alongside every derived quantity. */
@Serializable
data class Basis(
    val origin: String,
    val timeBase: String,
    val addressSpace: String,
    val parser: ParserRef,
    val snapshotId: String
)

@Serializable
data class ParserRef(val name: String, val version: String, val formatDialect: String)

@Serializable
data class AllocationSite(
    val siteId: String,
    val location: String,
    val line: Int?,
    val loader: String?,
    val raw: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ClassMeta(
    val classId: String,
    val name: String,
    val superClassId: String?,
    val classLoaderId: String?,
    val fields: List<ClassField> = emptyList(),
    val raw: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class ClassField(val name: String, val typeName: String)

enum class EdgeKind { STRONG, WEAK, INTERNAL, UNKNOWN }

@Serializable
data class Edge(
    val edgeId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val kind: EdgeKind,
    val fieldName: String?,
    /** Raw edge classification token exactly as imported (policy decisions happen elsewhere). */
    val rawKindToken: String,
    val raw: Map<String, JsonElement> = emptyMap()
)

enum class RootCategory {
    JNI_GLOBAL, JNI_LOCAL, STATIC_FIELD, NATIVE_STACK, THREAD_OBJECT,
    SYSTEM_CLASS, MONITOR_USED, INTERNED_STRING, UNCLASSIFIED
}

@Serializable
data class RootLabel(
    val rootId: String,
    val objectId: String,
    val category: RootCategory,
    val detail: String?,
    val rawCategoryToken: String,
    val raw: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    val offsetBytes: Long?,
    val byteLength: Long?,
    val reason: String,
    val affectedObjectIds: List<String> = emptyList(),
    val raw: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class HeapObject(
    val objectId: String,
    /** Imported address string, preserved verbatim. Address is never used as stable identity. */
    val address: String,
    val classId: String,
    /** Raw shallow-size token; normalization to bytes is performed on the derived layer. */
    val shallowSizeRaw: String?,
    val shallowSizeBytes: Long?,
    val sizeUnit: UnitSpec,
    val allocationSiteId: String?,
    val complete: Boolean,
    val incompletenessReason: String?,
    /** Cross-snapshot correspondence key declared by the runtime (not derived from address). */
    val correspondenceKey: String?,
    val raw: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class HeapSnapshot(
    val snapshotId: String,
    val snapshotIdentity: String,
    val capturedAt: String,
    val timeBase: String,
    val addressSpace: String,
    val parser: ParserRef,
    val origin: String,
    val objects: List<HeapObject>,
    val classes: List<ClassMeta>,
    val edges: List<Edge>,
    val roots: List<RootLabel>,
    val allocationSites: List<AllocationSite>,
    val incompleteBlocks: List<IncompleteBlock>,
    val diagnostics: List<Diagnostic> = emptyList(),
    /** Exact imported bytes (layer 0) - retained for forensic replay. */
    val rawSourceBytes: String = ""
) {
    fun basis(): Basis = Basis(origin, timeBase, addressSpace, parser, snapshotId)
    fun objectById(id: String): HeapObject? = objects.firstOrNull { it.objectId == id }
    fun classById(id: String): ClassMeta? = classes.firstOrNull { it.classId == id }
}

@Serializable
data class Diagnostic(
    val code: String,
    val severity: String,
    val message: String,
    /** 1-based line / 0-based offset into the imported document when known. */
    val line: Int? = null,
    val offset: Long? = null,
    val jsonPointer: String? = null,
    val subjectId: String? = null,
    val rawToken: String? = null
)

/**
 * Three mandatory distinct failure outcomes plus success.
 * OK is only used when the computation completed with full semantics.
 */
enum class ComputeOutcome { OK, INCOMPLETE_INPUT, BUDGET_EXHAUSTED, FORMAT_CORRUPT, SEMANTIC_AMBIGUOUS }
