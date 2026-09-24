package forensics.model

import kotlinx.serialization.Serializable

/**
 * A quantity as it appeared in the snapshot: the raw token is never discarded.
 * The derived layer may normalize it; an unconvertible token stays in place with
 * [ParseDiagnostic] instead of being replaced by zero or a default.
 */
@Serializable
data class RawQuantity(val raw: String, val unit: String, val normalizedBytes: Long? = null)

@Serializable
data class Position(val line: Int, val block: String? = null)

@Serializable
data class ParseDiagnostic(
    val code: String,
    val message: String,
    val position: Position,
    val rawToken: String? = null,
)

@Serializable
data class HeapClass(
    val stableId: String,
    val name: String,
    val classLoaderId: String?,
    val meta: Map<String, String>,
    val renamedFrom: String? = null,
)

@Serializable
data class HeapObject(
    val stableId: String,
    val objectId: String,
    val address: String?,
    val classId: String,
    val shallowSize: RawQuantity?,
    val allocSiteId: String?,
    val line: Int,
    val rawLine: String,
)

@Serializable
enum class EdgeStrength { STRONG, WEAK, INTERNAL }

@Serializable
data class HeapEdge(
    val stableId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val strength: EdgeStrength,
    val kind: String,
    val field: String?,
    val line: Int,
    val rawLine: String,
)

@Serializable
data class HeapRoot(
    val stableId: String,
    val objectId: String,
    val rootType: String,
    val category: String,
    val line: Int,
    val rawLine: String,
)

@Serializable
data class AllocationSite(
    val stableId: String,
    val siteId: String,
    val className: String,
    val location: String,
    val coordinateBase: String?,
    val line: Int,
    val rawLine: String,
)

@Serializable
data class IncompleteBlock(
    val blockName: String,
    val startLine: Int,
    val endLine: Int?,
    val reason: String,
    val rawText: String,
)

@Serializable
data class SnapshotHeader(
    val snapshotId: String,
    val parserVersion: String,
    val formatVersion: String,
    val capturedAt: String?,
    val timeBase: String?,
    val addressBase: String?,
    val unitBase: String,
)

@Serializable
data class HeapSnapshot(
    val header: SnapshotHeader,
    val classes: List<HeapClass>,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val roots: List<HeapRoot>,
    val allocSites: List<AllocationSite>,
    val incompleteBlocks: List<IncompleteBlock>,
    val diagnostics: List<ParseDiagnostic>,
    val sourceBytes: Int,
    val sourceLines: Int,
) {
    fun objectByObjectId(id: String): HeapObject? = objects.firstOrNull { it.objectId == id }
    fun classOf(o: HeapObject): HeapClass? = classes.firstOrNull { it.stableId == o.classId }
}
