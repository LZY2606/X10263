package forensics.heapshadow.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 不可变材料层：解析后、未经任何规范化的原始领域对象。
 * 所有 [SourcePos] 都指回输入片段（文件内 JSON 路径 + 序号）。
 */

/** 输入位置：用于带位置诊断，且在重复/不完整材料出现时可回溯。 */
data class SourcePos(
    val file: String,
    /** 输入内路径，如 objects[3].fields.address。 */
    val path: String,
    /** 该材料在其数组中的原始序号（稳定顺序的第一排序键）。 */
    val ordinal: Int,
) {
    fun at(sub: String) = copy(path = "$path.$sub")
    override fun toString(): String = "$file:$path"
}

/**
 * 带单位的量。原始表示原样保留（raw/rawUnit），派生层才尝试规范化。
 * 无法转换时 normalizedBytes 为 null 并在诊断中说明，绝不丢弃原值。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Quantity(
    val raw: String,
    val rawUnit: String?,
    /** 规范化为字节；单位未知/不可换算时为 null（诊断挂在导入结果上）。 */
    val normalizedBytes: Long?,
    /** 单位基准说明，如 "1 KiB = 1024 B（IEC 二进制）"。 */
    val unitBasis: String?,
    val conversionDiagnosticId: String? = null,
)

/** 地址记录：地址不是稳定身份。重复地址保留为独立对象并产生诊断。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AddressRecord(
    val raw: String,
    val normalized: Long?,
    val radix: Int,
    val addressSpace: String,
    val diagnosticId: String? = null,
)

enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class RootKind {
    STICKY_CLASS,
    JAVA_LOCAL,
    NATIVE_STACK,
    GLOBAL,
    JNI_GLOBAL,
    JNI_LOCAL,
    THREAD_OBJECT,
    SYSTEM_CLASS,
    INTERNED_STRING,
    FINALIZING,
    DEBUGGER,
    REFERENCE_CLEANUP,
    VM_INTERNAL,
    UNKNOWN,
}

data class ClassMeta(
    val classId: String,
    val name: String,
    val classLoaderObjectId: String?,
    val superClassId: String?,
    val isArray: Boolean,
    val location: SourcePos,
)

data class AllocationSite(
    val siteId: String,
    val className: String,
    val methodName: String?,
    val fileName: String?,
    val line: Int?,
    val location: SourcePos,
)

/**
 * 堆对象（材料层）。incomplete=true 表示来自不完整块：
 * 字段按原始 JSON 是否出现决定 nullable，而不是用默认值伪装完整。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class HeapObject(
    /** 快照内原始对象 id，逐字保留。 */
    val objectId: String,
    val klassId: String?,
    val address: AddressRecord?,
    val shallowSize: Quantity?,
    val allocationSiteId: String?,
    val ordinal: Int,
    val incomplete: Boolean,
    /** 该对象缺失的字段路径（仅 incomplete 时有意义）。 */
    val missingFields: List<String>,
    /** 该对象所在的不完整块 id（可空）。 */
    val incompleteChunkId: String?,
    val location: SourcePos,
)

data class HeapEdge(
    val edgeId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val kind: EdgeKind,
    val label: String?,
    val ordinal: Int,
    /** 边指向的端点缺失（不完整快照语义问题来源之一）。 */
    val incomplete: Boolean,
    val location: SourcePos,
)

data class RootRecord(
    val rootId: String,
    val objectId: String,
    val kind: RootKind,
    val ordinal: Int,
    val location: SourcePos,
)

/**
 * 不完整块：记录解析器恢复边界。块内对象以 incomplete=true 标记，
 * 绝不静默丢弃或补默认值。
 */
data class IncompleteChunk(
    val chunkId: String,
    val reason: String,
    val rawExcerpt: String,
    val byteOffset: Long?,
    val ordinal: Int,
    val location: SourcePos,
)

/** 坐标/时间基准：所有“时间类”显示值必须挂这个基准。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class TimeBasis(
    val capturedAt: String,
    val capturedAtRaw: String,
    val clockBasis: String,
    val uptimeMs: Long?,
    val uptimeRaw: String?,
)

/** 地址空间基准：地址只在同一 addressSpace 内可比较。 */
data class AddressBasis(
    val pointerSizeBits: Int,
    val addressSpace: String,
)

data class SnapshotMaterial(
    val snapshotId: String,
    val label: String,
    /** 解析器名称与版本：逐字保留，决定规则兼容性。 */
    val parserName: String,
    val parserVersion: String,
    val formatVersion: String,
    val addressBasis: AddressBasis,
    val timeBasis: TimeBasis?,
    val classes: List<ClassMeta>,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val roots: List<RootRecord>,
    val allocationSites: List<AllocationSite>,
    val incompleteChunks: List<IncompleteChunk>,
)
