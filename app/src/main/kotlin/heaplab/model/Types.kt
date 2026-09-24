package heaplab.model

import kotlinx.serialization.Serializable

/**
 * 不可变材料层：直接来自堆快照文本，逐字段保留原始表示。
 * 规范化（字节、地址数值）只发生在派生层，无法转换的值留在原位置并附诊断。
 */

@Serializable
data class SizeValue(
    val raw: String,            // 原始表示，例如 "1024"、"2.5KiB"
    val bytes: Long?,           // 派生层规范化结果；无法转换时为 null
    val unit: String,           // 解析出的单位，未知为 ""
    val convertible: Boolean,
    val diagnosticId: String? = null
)

@Serializable
data class AddressValue(
    val raw: String,            // 原始表示，例如 "0x7f00abcd"
    val value: Long?,           // 规范化为无符号语义的 long；无法转换为 null
    val base: String,           // 坐标基准："hex16" / "dec" / "unknown"
    val diagnosticId: String? = null
)

@Serializable
data class RawObject(
    val objId: String,          // 原始对象 id（稳定身份的一部分，不做重排）
    val address: AddressValue,
    val classIdRaw: String,     // 类元数据，保留原始引用串
    val size: SizeValue,
    val allocSiteId: String?,
    val complete: Boolean,      // 不完整对象：块被截断
    val startLine: Int,
    val endLine: Int,
    val rawText: String
)

enum class EdgeStrength { STRONG, WEAK, INTERNAL }

@Serializable
data class RawEdge(
    val edgeUid: String,        // 稳定身份：<snapshotId>#e<声明序号>
    val fromId: String,
    val toId: String,
    val strength: EdgeStrength,
    val label: String,
    val labelRaw: String,
    val line: Int,
    val resolved: Boolean,      // 目标能否在对象表中解析
    val diagnosticId: String? = null
)

@Serializable
data class RawRoot(
    val rootUid: String,        // <snapshotId>#r<声明序号>
    val objId: String,
    val category: String,       // root 分类，规范化（见 RootClassifier）
    val categoryRaw: String,
    val label: String,
    val line: Int,
    val resolved: Boolean,
    val diagnosticId: String? = null
)

@Serializable
data class RawClass(
    val classId: String,
    val name: String,
    val nameRaw: String,
    val superClassId: String?,
    val classLoaderId: String?,
    val line: Int
)

@Serializable
data class AllocationSite(
    val siteId: String,
    val classId: String?,
    val locationRaw: String,    // 坐标基准：file:line 文本
    val epochMillisRaw: String?,// 时间基准：原始纪元毫秒串
    val epochMillis: Long?,     // 规范化；不可转换为 null
    val line: Int
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    val kind: String,           // OBJECT / EDGES / ROOTS / SECTION
    val startLine: Int,
    val endLine: Int,
    val reason: String,
    val rawText: String
)

@Serializable
data class ParseDiagnostic(
    val diagnosticId: String,
    val severity: String,       // ERROR / WARN
    val code: String,          // CORRUPT_FORMAT / UNCONVERTIBLE_VALUE / DANGLING_REF / DUPLICATE_ADDRESS ...
    val line: Int,
    val col: Int,
    val endCol: Int,
    val message: String,
    val rawText: String,
    val invalidValue: String? = null
)

@Serializable
data class RawSnapshot(
    val snapshotId: String,     // 快照稳定身份（导入时确定，导出往返保持）
    val sourceName: String,
    val formatVersion: String,  // 快照格式版本
    val parserVersion: String,  // 解析器版本
    val importedAtEpoch: Long,
    val objects: Map<String, RawObject>,
    val classes: Map<String, RawClass>,
    val edges: List<RawEdge>,
    val roots: List<RawRoot>,
    val allocationSites: Map<String, AllocationSite>,
    val incompletes: List<IncompleteBlock>,
    val diagnostics: List<ParseDiagnostic>,
    /** 同一规范化地址出现多个不同原始对象 id：地址不是稳定身份的直接证据。 */
    val duplicateAddresses: Map<String, List<String>>
) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == "ERROR" }
}
