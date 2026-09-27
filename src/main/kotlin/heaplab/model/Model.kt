package heaplab.model

import kotlinx.serialization.Serializable

/**
 * 不可变材料层模型：对应解析器从堆快照中读出的原始表示。
 * 所有字段保留原始字符串/数值；派生层才做规范化。
 */

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL, SOFT }

@Serializable
enum class RootKind {
    JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK, STICKY_CLASS,
    THREAD_BLOCK, MONITOR_USED, INTERNED_STRING, FINALIZING, UNKNOWN, NON_ROOT
}

/** 资源预算耗尽：与格式损坏、语义无解互斥的三种分析结果之一。 */
class BudgetExhaustedException(
    val budgetName: String,
    val used: Long,
    val limit: Long
) : RuntimeException("资源预算耗尽: $budgetName used=$used limit=$limit")

/** 格式损坏：输入违反文件格式/语法约束，带位置。 */
class CorruptFormatException(
    message: String,
    val line: Int? = null,
    val column: Int? = null,
    val chunkId: String? = null
) : RuntimeException(message)

/** 语义无解：语法合法但存在多个互斥解释或无法决定，带位置/候选。 */
class SemanticallyAmbiguousException(
    message: String,
    val candidates: List<String> = emptyList(),
    val objectIds: List<String> = emptyList()
) : RuntimeException(message)

@Serializable
data class Diagnostic(
    val code: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
    val chunkId: String? = null,
    val objectId: String? = null,
    val severity: String = "ERROR"
)

/** 快照中不完整块的原文记录，永远保留在材料层。 */
@Serializable
data class IncompleteChunk(
    val chunkId: String,
    val rawText: String,
    val startLine: Int,
    val endLine: Int,
    val reason: String
)

/** 解析器版本等溯源坐标。 */
@Serializable
data class ParserInfo(
    val parserVersion: String,
    val formatDialect: String,
    val exportedAt: String? = null
)

/** 类元数据：classObjectId 指向描述类自身的对象（若存在）。 */
@Serializable
data class ClassMeta(
    val classId: String,
    val nameRaw: String,
    val classObjectId: String? = null,
    val superClassId: String? = null,
    val classLoaderObjectId: String? = null
)

/** Allocation site：分配点坐标（文件/类/方法/行），保留原始表示。 */
@Serializable
data class AllocationSite(
    val siteId: String,
    val locationRaw: String,
    val classNameRaw: String? = null,
    val methodRaw: String? = null,
    val line: Int? = null
)

/**
 * 原始对象。rawObjectId 为解析器给出的对象 id（字符串保留原始表示），
 * addressRaw/address 保留原始地址；address 绝不当作稳定身份。
 * incomplete=true 表示该对象来自不完整块，sizeRaw 可能无法转换。
 */
@Serializable
data class HeapObject(
    val rawObjectId: String,
    val addressRaw: String,
    val address: Long?,
    val classId: String,
    val sizeRaw: String,
    val sizeBytes: Long?,
    val allocationSiteId: String? = null,
    val rootKind: RootKind = RootKind.NON_ROOT,
    /** 快照身份对应键：跨快照/类改名后仍指向同一逻辑实体的稳定坐标。 */
    val correspondenceKey: String? = null,
    val incomplete: Boolean = false,
    val incompleteChunkId: String? = null
)

/** 强/弱/内部边。fromObjectId -> toObjectId，fieldRaw 保留原始字段名。 */
@Serializable
data class HeapEdge(
    val edgeId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val kind: EdgeKind,
    val fieldRaw: String? = null,
    val index: Int? = null,
    val incomplete: Boolean = false
)

/** 不可变快照材料。snapshotId 是导入时赋予的稳定身份。 */
@Serializable
data class Snapshot(
    val snapshotId: String,
    val label: String,
    val coordinate: SnapshotCoordinate,
    val parser: ParserInfo,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val classes: List<ClassMeta>,
    val sites: List<AllocationSite>,
    val incompleteChunks: List<IncompleteChunk>,
    val diagnostics: List<Diagnostic>,
    val rawBytesSha256: String,
    val rawSizeBytes: Long
) {
    val objectById: Map<String, HeapObject> by lazy { objects.associateBy { it.rawObjectId } }
    val classById: Map<String, ClassMeta> by lazy { classes.associateBy { it.classId } }
    val siteById: Map<String, AllocationSite> by lazy { sites.associateBy { it.siteId } }

    fun outgoing(id: String): List<HeapEdge> = edges.filter { it.fromObjectId == id }
    fun incoming(id: String): List<HeapEdge> = edges.filter { it.toObjectId == id }
}

/** 坐标/时间/单位基准：每个派生量都必须引用这里的基准。 */
@Serializable
data class SnapshotCoordinate(
    val takenAtRaw: String?,
    val takenAtEpochMs: Long?,
    val sizeUnit: String = "BYTE",
    val addressBase: String = "RAW_HEAP_ADDRESS",
    val timeBasis: String = "EPOCH_MS"
)

/** 弱引用保活策略：显式决定弱/软边是否让目标保持可达。 */
@Serializable
enum class WeakPolicy { KEEP_ALIVE, DO_NOT_KEEP_ALIVE }

/** 派生计算参数（带版本）。 */
@Serializable
data class AnalysisParams(
    val version: Int,
    val weakPolicy: WeakPolicy,
    val retainedSizeUnit: String = "BYTE",
    val maxPathCandidates: Int = 200,
    val nodeBudget: Long = 200_000L,
    val edgeBudget: Long = 1_000_000L
) {
    companion object {
        val INITIAL = AnalysisParams(
            version = 1,
            weakPolicy = WeakPolicy.DO_NOT_KEEP_ALIVE
        )
    }
}
