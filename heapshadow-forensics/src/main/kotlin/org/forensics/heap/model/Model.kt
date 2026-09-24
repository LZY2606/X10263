package org.forensics.heap.model

import kotlinx.serialization.Serializable

/**
 * 堆影取证室领域模型。
 *
 * 分层原则：
 *  - [RawSnapshot] 是不可变材料层：逐行保留原始表示（地址、尺寸、块文本），不做有损规范化。
 *  - analysis 包是派生层：只在计算时规范化（例如把 "0x1A" 与 "26" 统一为字节数），
 *    无法转换的值留在原位置并生成 [Diagnostic]，绝不用空值或默认值伪装成功。
 */

/** 边语义。INTERNAL 是运行时内部边（字段槽/数组槽），默认强可达，除非显式标记为弱策略。 */
@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL, SOFT }

/** root 分类（沿用通用运行时快照的 root 分类法，原样保留字符串并归一为枚举；未知值不丢弃）。 */
@Serializable
enum class RootKind {
    JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK, STICKY_CLASS,
    THREAD_BLOCK, THREAD_OBJECT, MONITOR_USED, INTERNED_STRING, FINALIZING,
    DEBUGGER, REFERENCE_CLEANUP, VM_INTERNAL, UNREACHABLE, UNKNOWN;

    companion object {
        fun parse(raw: String): RootKind =
            entries.firstOrNull { it.name.equals(raw.trim().uppercase().replace('-', '_').replace(' ', '_'), ignoreCase = true) }
                ?: UNKNOWN
    }
}

/** 弱引用保活策略（由审阅者显式选择；策略本身是可版本化参数）。 */
@Serializable
enum class WeakPolicy {
    /** 弱/软边不保活：目标可能不可达，保留大小不计弱引用链。 */
    EXCLUDE_WEAK,
    /** 软引用保活但弱引用不保活。 */
    KEEP_SOFT,
    /** 弱/软边都视为保活（仅用于对照实验）。 */
    INCLUDE_ALL
}

/** 计算结论状态：预算耗尽 / 格式损坏 / 语义无解必须是三种不同结果。 */
@Serializable
enum class ResultStatus {
    /** 计算成功完成（仍可能带诊断）。 */
    OK,
    /** 资源预算（节点/边/时间）耗尽：带已完成部分与预算诊断。 */
    BUDGET_EXHAUSTED,
    /** 语义无解：例如边指向不存在的对象、重复身份冲突等。 */
    SEMANTICALLY_AMBIGUOUS
}

/** 诊断严重级别。 */
@Serializable
enum class Severity { ERROR, WARNING, INFO }

/** 带位置的诊断（行号从 1 开始，col 可选）。 */
@Serializable
data class Diagnostic(
    val code: String,
    val severity: Severity,
    val message: String,
    val line: Int? = null,
    val col: Int? = null,
    val fragment: String? = null,
    val objectId: String? = null
) {
    companion object {
        const val UNPARSEABLE_SIZE = "HSF-101"
        const val UNPARSEABLE_ADDR = "HSF-102"
        const val INCOMPLETE_BLOCK = "HSF-103"
        const val DANGLING_EDGE = "HSF-201"
        const val DUPLICATE_ADDRESS = "HSF-202"
        const val MISSING_CLASS = "HSF-203"
        const val ROOT_UNKNOWN_KIND = "HSF-204"
        const val BUDGET_NODES = "HSF-301"
        const val BUDGET_EDGES = "HSF-302"
        const val AMBIGUOUS_DOMINANCE = "HSF-401"
        const val PARSER_VERSION_MISMATCH = "HSF-501"
    }
}

/** 单位 / 坐标基准。内存尺寸的基准恒为字节；时间为带时区的 ISO-8859 毫秒。 */
@Serializable
data class UnitBasis(
    val sizeUnit: String = "byte",
    val addressBase: String = "hex-runtime-heap",
    val timeBasis: String = "ISO-8601/UTC millis",
    val coordinateSystem: String = "runtime-heap-offset"
)

/** allocation site（分配点）：坐标基准是 class#method:line，行号不可解析时保留原文。 */
@Serializable
data class AllocationSite(
    val id: String,
    val className: String,
    val method: String,
    val lineRaw: String,
    val fileName: String? = null
)

/** 类元数据：rawName 为快照原文；改名检测基于 id 对应而非名字。 */
@Serializable
data class RawClass(
    val classId: String,
    val rawName: String,
    val superclassId: String? = null,
    val classLoaderObjectId: String? = null,
    val instanceSizeRaw: String? = null,
    val parserVersion: String,
    val siteId: String? = null
)

/** 不完整块：原文、起止行与缺失说明全部保留。 */
@Serializable
data class IncompleteBlock(
    val blockId: String,
    val startLine: Int,
    val endLine: Int?,
    val rawText: String,
    val reason: String
)

/** 原始对象记录。addressRaw / shallowSizeRaw 保留快照原文，永不就地覆写。 */
@Serializable
data class RawObject(
    val objectId: String,
    val addressRaw: String,
    val classId: String,
    val shallowSizeRaw: String,
    val siteId: String? = null,
    val parserVersion: String,
    val complete: Boolean = true,
    val sourceLine: Int,
    val rootKind: RootKind? = null,
    val rootRaw: String? = null,
    val unreachable: Boolean = false,
    val diagnosticCodes: List<String> = emptyList()
)

/** 原始边。from/to 均为原始对象 id。 */
@Serializable
data class RawEdge(
    val edgeId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val kind: EdgeKind,
    val label: String? = null,
    val sourceLine: Int,
    val parserVersion: String,
    /** INTERNAL 边的槽位坐标（数组下标或字段偏移），原文保留。 */
    val slotRaw: String? = null
)

/** 不可变材料层：一次快照导入的全部原始内容与诊断。 */
@Serializable
data class RawSnapshot(
    val snapshotId: String,
    val identityKey: String,
    val displayName: String,
    val parserVersion: String,
    val capturedAtRaw: String,
    val units: UnitBasis,
    val classes: List<RawClass>,
    val objects: List<RawObject>,
    val edges: List<RawEdge>,
    val sites: List<AllocationSite>,
    val roots: List<RawRoot>,
    val incompleteBlocks: List<IncompleteBlock>,
    val diagnostics: List<Diagnostic>,
    /** 原文按行保留，供“输入片段”回显与取证。 */
    val sourceLines: List<String>,
    val checksumSha256: String
) : java.io.Serializable

/** root 记录：一个对象可有多个 root 分类（多 root 情形）。 */
@Serializable
data class RawRoot(
    val objectId: String,
    val kind: RootKind,
    val kindRaw: String,
    val sourceLine: Int
)

/** 规范化后的尺寸（字节）。无法转换时 normalizedBytes 为 null，raw 保留原文并附诊断。 */
@Serializable
data class SizedValue(val raw: String, val normalizedBytes: Long?, val diagnosticCode: String?)

/** 规范化后的地址。地址不作为稳定身份，只用于展示与重复地址诊断。 */
@Serializable
data class AddrValue(val raw: String, val normalized: Long?, val diagnosticCode: String?)

/** 计算参数（显式、可版本化）。 */
@Serializable
data class AnalysisParams(
    val weakPolicy: WeakPolicy = WeakPolicy.EXCLUDE_WEAK,
    val maxNodes: Int = 200_000,
    val maxEdges: Int = 1_000_000,
    val budgetMillis: Long = 10_000L,
    /** 泄漏候选阈值（保留字节）；不修改原始材料。 */
    val leakThresholdBytes: Long = 64L,
    val paramsVersion: String = "params-v1"
)

/** 单个对象的分析结论。 */
@Serializable
data class ObjectResult(
    val objectId: String,
    val reachable: Boolean,
    val shallowBytes: Long?,
    val retainedBytes: Long?,
    val dominatorId: String?,
    /** root 路径：每条为对象 id 序列（root..this）。多个等长路径全部保留。 */
    val rootPaths: List<List<String>>,
    val isLeakCandidate: Boolean,
    val diagnosticCodes: List<String> = emptyList()
)

/** 边在某次分析视图下是否保活（弱策略的显式结果）。 */
@Serializable
data class EdgeView(
    val edgeId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val kind: EdgeKind,
    val keepsAlive: Boolean,
    val label: String? = null
)

/** 类群统计。 */
@Serializable
data class ClassGroup(
    val classId: String,
    val className: String,
    val instanceCount: Int,
    val shallowBytes: Long,
    val retainedBytes: Long,
    val leakInstanceCount: Int
)

/** 两个快照间的类群差异（基于身份对应，不依赖地址；改名显式标注）。 */
@Serializable
data class ClassGroupDelta(
    val classId: String,
    val nameBefore: String,
    val nameAfter: String,
    val renamed: Boolean,
    val countBefore: Int,
    val countAfter: Int,
    val shallowBefore: Long,
    val shallowAfter: Long,
    val retainedBefore: Long,
    val retainedAfter: Long,
    val onlyIn: String?
)

/** 一次完整分析（派生层，父级依赖不可变）。 */
@Serializable
data class AnalysisResult(
    val analysisId: String,
    val snapshotId: String,
    val inputChecksum: String,
    val params: AnalysisParams,
    val status: ResultStatus,
    val objects: List<ObjectResult>,
    val edges: List<EdgeView>,
    val classGroups: List<ClassGroup>,
    val diagnostics: List<Diagnostic>,
    val reachableCount: Int,
    val totalShallowBytes: Long,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    /** 有效区间：参数版本 + 输入 checksum 决定；失效原因非空时结论不可用。 */
    val invalidReason: String? = null,
    val parentDependency: DependencyRef
)

@Serializable
data class DependencyRef(
    val rawSnapshotId: String,
    val rawChecksum: String,
    val parserVersion: String,
    val paramsVersion: String
)

/** 事件（机器可读取的判断依据；事件只追加、不改写）。 */
@Serializable
data class DecisionEvent(
    val eventId: String,
    val branchId: String,
    val parentEventId: String?,
    val sequence: Long,
    val atEpochMillis: Long,
    val type: String, // IMPORT | ANALYZE | PARAM_REVISE | ACCEPT | REJECT | ADJUST | SOURCE_PATCH | EXPORT | REIMPORT
    val actor: String,
    val summary: String,
    val payloadJson: String,
    /** 该事件时刻可见结论所依赖的分析 id（用于时间旅行恢复）。 */
    val visibleAnalysisId: String?,
    val dependencyRef: DependencyRef?
)
