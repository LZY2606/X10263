package heapforensics

import kotlinx.serialization.Serializable

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
data class FieldMeta(
    val name: String,
    val offsetBytes: Long,
    val kind: EdgeKind? = null // null = primitive field
)

@Serializable
data class ClassMeta(
    val classId: String,
    val name: String,
    val superClassId: String? = null,
    val instanceSizeBytes: Long,
    val fields: List<FieldMeta> = emptyList()
)

@Serializable
data class HeapObject(
    val objectId: String,
    val address: String, // raw hex text exactly as imported; NOT a stable identity
    val classId: String,
    val sizeBytes: Long,
    val allocationSite: String? = null,
    val incomplete: Boolean = false
)

@Serializable
data class HeapEdge(
    val fromId: String,
    val toId: String,
    val kind: EdgeKind,
    val label: String? = null
)

@Serializable
data class RootRef(val objectId: String, val rootType: String)

@Serializable
data class IncompleteChunk(
    val chunkId: String,
    val offsetHex: String,
    val lengthBytes: Long,
    val reason: String
)

@Serializable
data class HeapSnapshot(
    val snapshotId: String,
    val parserVersion: String,
    val capturedAt: String, // ISO-8601, time basis for the snapshot
    val classes: List<ClassMeta>,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val roots: List<RootRef>,
    val incompleteChunks: List<IncompleteChunk> = emptyList()
)

@Serializable
data class Diagnostic(val position: String, val code: String, val message: String)

@Serializable
data class AnalysisParams(
    val version: Int,
    val weakKeepAlive: Boolean,
    val maxPathCandidates: Int = 8,
    val nodeBudget: Int = 100_000
)

@Serializable
data class DomainEvent(
    val eventId: String,
    val seq: Long,
    val type: String,
    val snapshotId: String? = null,
    val paramsVersion: Int? = null,
    val summary: String,
    val affected: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val createdAt: String
)

@Serializable
data class AnalysisRun(
    val runId: String,
    val snapshotId: String,
    val paramsVersion: Int,
    val inputDigest: String,
    val parentEventSeq: Long,
    val status: String,
    val createdAt: String,
    val supersededBy: String? = null,
    val invalidationReason: String? = null
)

@Serializable
data class ClassGroupDiff(
    val classId: String,
    val nameA: String?,
    val nameB: String?,
    val countA: Long,
    val countB: Long,
    val bytesA: Long,
    val bytesB: Long,
    val renamed: Boolean,
    val added: Boolean,
    val removed: Boolean
)
