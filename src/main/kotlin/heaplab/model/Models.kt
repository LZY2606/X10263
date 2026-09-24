package heaplab.model

import kotlinx.serialization.Serializable

const val VIRTUAL_ROOT: String = "@@ROOT@@"

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
enum class Severity { INFO, WARN, ERROR }

@Serializable
data class Diagnostic(
    val code: String,
    val severity: Severity,
    val line: Int? = null,
    val column: Int? = null,
    val message: String,
    val refId: String? = null,
)

@Serializable
data class IncompleteBlock(
    val startLine: Int,
    val endLine: Int?,
    val reason: String?,
    val rawText: String,
)

@Serializable
data class ClassMeta(
    val id: String,
    val name: String,
    val superClassId: String?,
    val sourceLine: Int,
    val rawLine: String,
)

@Serializable
data class HeapObject(
    val id: String,
    val address: String?,
    val classId: String,
    val shallowBytes: Long?,
    val allocSiteId: String?,
    val complete: Boolean,
    val missingFields: List<String>,
    val sourceLine: Int,
    val rawLine: String,
)

@Serializable
data class HeapEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val kind: EdgeKind,
    val label: String?,
    val sourceLine: Int,
    val rawLine: String,
)

@Serializable
data class RootRecord(
    val id: String,
    val objectId: String,
    val category: String,
    val sourceLine: Int,
    val rawLine: String,
)

@Serializable
data class AllocSite(
    val id: String,
    val location: String,
    val sourceLine: Int,
    val rawLine: String,
)

@Serializable
enum class ParseStatus { OK, FORMAT_CORRUPT }

@Serializable
data class Snapshot(
    val identity: String,
    val parserVersion: String,
    val capturedAt: String?,
    val timeBasis: String?,
    val addressBasis: String?,
    val objects: List<HeapObject>,
    val classes: List<ClassMeta>,
    val edges: List<HeapEdge>,
    val roots: List<RootRecord>,
    val sites: List<AllocSite>,
    val incompleteBlocks: List<IncompleteBlock>,
    val diagnostics: List<Diagnostic>,
    val status: ParseStatus,
    val rawText: String,
)
