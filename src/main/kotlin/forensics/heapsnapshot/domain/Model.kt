package forensics.heapsnapshot.domain

import kotlinx.serialization.Serializable

/**
 * 不可变材料层：所有记录都保留导入时的原始表示（rawLine / raw 字段），
 * 规范化只发生在派生层。地址不是身份：identity 为快照内对象 id。
 */

@Serializable
data class Diagnostic(
    val code: String,
    val level: Level,
    val line: Int,
    val column: Int = 0,
    val location: String,
    val message: String,
    val rawLine: String? = null,
) {
    enum class Level { WARNING, ERROR }
}

@Serializable
data class RawQuantity(
    val raw: String,
    val unit: String? = null,
    val valueBytes: Long? = null,
    val convertible: Boolean,
    val diagnosticCode: String? = null,
)

@Serializable
data class SnapshotMeta(
    val identity: String,
    val displayName: String,
    val parserVersion: String,
    val takenAt: String,
    val baseUnit: String = "bytes",
    val rawSha256: String = "",
)

@Serializable
data class ClassRecord(
    val id: String,
    val key: String,
    val name: String,
    val allocationSiteId: String?,
    val line: Int,
    val rawLine: String,
)

@Serializable
data class AllocationSiteRecord(
    val id: String,
    val type: String,
    val location: String,
    val line: Int,
    val rawLine: String,
)

@Serializable
data class ObjectRecord(
    val id: String,
    val classId: String,
    val addressRaw: String,
    val address: Long?,
    val size: RawQuantity,
    val complete: Boolean,
    val allocationSiteId: String?,
    val fieldsRaw: String?,
    val line: Int,
    val rawLine: String,
)

@Serializable
data class EdgeRecord(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val field: String?,
    val line: Int,
    val rawLine: String,
)

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
data class RootRecord(
    val ref: String,
    val kind: String,
    val label: String?,
    val line: Int,
    val rawLine: String,
)

@Serializable
data class IncompleteMark(
    val block: String,
    val kind: String,
    val detail: String,
    val line: Int,
    val rawLine: String,
)

/** 跨快照身份对应（类改名 / 对象同一）。 */
@Serializable
data class CorrespondenceRecord(
    val kind: String,
    val fromKey: String,
    val toKey: String,
    val note: String?,
    val line: Int,
    val rawLine: String,
)

@Serializable
data class ParsedSnapshot(
    val meta: SnapshotMeta,
    val classes: List<ClassRecord>,
    val allocationSites: List<AllocationSiteRecord>,
    val objects: List<ObjectRecord>,
    val edges: List<EdgeRecord>,
    val roots: List<RootRecord>,
    val incompleteMarks: List<IncompleteMark>,
    val correspondences: List<CorrespondenceRecord>,
    val diagnostics: List<Diagnostic>,
    val rawLines: List<String>,
)
