package lab.heap.domain

import kotlinx.serialization.Serializable

/** 边的引用语义：强引用 / 弱引用 / 内部引用（字段内嵌，不构成独立保活）。 */
@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

/** Root 分类（保留运行时原始 root 类型名）。 */
@Serializable
enum class RootKind {
    JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK, STICKY_CLASS,
    THREAD_OBJ, MONITOR_USED, INTERNED_STRING, FINALIZING, UNKNOWN
}

/**
 * 原始对象记录：导入层逐字段保留快照原文。
 * [sizeBytesRaw] 为原始表示（可能带单位 / 无法解析），派生层才规范化。
 */
@Serializable
data class RawObject(
    val ordinal: Int,
    val objectId: String,
    val address: String,
    val className: String,
    val classObjectId: String?,
    val sizeBytesRaw: String,
    val sizeBytes: Long?,
    val allocationSite: String?,
    val complete: Boolean,
    val incompleteReason: String?
)

/** 原始边：from/to 均为原始对象 id；弱/内部边由 kind 携带。 */
@Serializable
data class RawEdge(
    val ordinal: Int,
    val edgeId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val label: String,
    val kind: EdgeKind
)

/** Root 记录：对象 + root 分类 + 原始类型名 + 线程/位置坐标（若有）。 */
@Serializable
data class RawRoot(
    val ordinal: Int,
    val objectId: String,
    val kind: RootKind,
    val rawType: String,
    val location: String?
)

/** 不完整块：保留偏移、长度、字节前缀与原因，不静默丢弃。 */
@Serializable
data class IncompleteBlock(
    val offset: Long,
    val length: Long?,
    val hexPrefix: String,
    val reason: String
)

/** Allocation site：id、坐标（文件:行/帧索引）、原始文本与时间基准。 */
@Serializable
data class AllocationSite(
    val siteId: String,
    val coordinate: String,
    val rawText: String,
    val timeBase: String
)

/** 解析器与快照身份元数据。 */
@Serializable
data class ParserInfo(
    val parserVersion: String,
    val formatVersion: String,
    val dumpTimeBase: String,
    val dumpTime: String,
    val addressUnit: String,
    val sizeUnit: String,
    val snapshotIdentity: String,
    val origin: String
)

/** 带位置的诊断（行从 1 起，列从 1 起；块用偏移）。 */
@Serializable
data class Diagnostic(
    val line: Int?,
    val column: Int?,
    val offset: Long?,
    val code: String,
    val message: String,
    val rawFragment: String,
    val fatal: Boolean
)

/**
 * 一次导入的不可变材料层。任何派生计算只能引用它，不得改写。
 * [status] 区分三种失败：OK / CORRUPT（格式损坏）/ SEMANTIC_UNRESOLVABLE（语义无解）。
 */
@Serializable
data class SnapshotMaterial(
    val snapshotId: String,
    val parser: ParserInfo,
    val objects: List<RawObject>,
    val edges: List<RawEdge>,
    val roots: List<RawRoot>,
    val allocationSites: List<AllocationSite>,
    val incompleteBlocks: List<IncompleteBlock>,
    val diagnostics: List<Diagnostic>,
    val rawLines: List<String>,
    val status: ImportStatus
)

@Serializable
enum class ImportStatus { OK, CORRUPT, SEMANTIC_UNRESOLVABLE }

/** 弱引用保活策略：显式参数，不允许隐式默认“弱引用保活”。 */
@Serializable
enum class WeakPolicy { KEEP_ALIVE, DROP_FOR_REACHABILITY }

/** 支配计算参数（带版本）。 */
@Serializable
data class AnalysisParams(
    val version: Int,
    val weakPolicy: WeakPolicy,
    val rootKinds: List<RootKind>,
    val budgetNodes: Int,
    val budgetEdges: Int
) {
    companion object {
        val INITIAL = AnalysisParams(
            version = 1,
            weakPolicy = WeakPolicy.DROP_FOR_REACHABILITY,
            rootKinds = listOf(
                RootKind.JNI_GLOBAL, RootKind.JNI_LOCAL, RootKind.JAVA_FRAME,
                RootKind.NATIVE_STACK, RootKind.STICKY_CLASS, RootKind.THREAD_OBJ,
                RootKind.MONITOR_USED, RootKind.INTERNED_STRING, RootKind.FINALIZING
            ),
            budgetNodes = 200_000,
            budgetEdges = 800_000
        )
    }
}

/** 计算结果三态：成功 / 资源预算耗尽 / 语义无解（格式损坏在导入层）。 */
@Serializable
enum class ComputeStatus { COMPUTED, BUDGET_EXHAUSTED, SEMANTIC_UNRESOLVABLE }

/** 节点保留视图：稳定身份 = snapshotId + '#' + objectId。 */
@Serializable
data class NodeRetention(
    val stableId: String,
    val objectId: String,
    val address: String,
    val className: String,
    val shallowBytes: Long,
    val retainedBytes: Long,
    val sizeUnit: String,
    val dominatorStableId: String?,
    val reachable: Boolean,
    val root: Boolean,
    val complete: Boolean,
    val sizeKnown: Boolean,
    val addressDuplicate: Boolean
)

/** 一条 root 路径：多个等长路径全部保留（candidateSetId 标识候选集）。 */
@Serializable
data class RootPath(
    val candidateSetId: String,
    val pathOrdinal: Int,
    val rootObjectId: String,
    val rootKind: RootKind,
    val nodeStableIds: List<String>,
    val edgeLabels: List<String>,
    val lengthEdges: Int,
    val unique: Boolean
)

/** 泄漏路径候选（结构化状态，非日志）。 */
@Serializable
data class LeakCandidate(
    val stableId: String,
    val targetStableId: String,
    val pathCandidateSetId: String,
    val equalLengthPathCount: Int,
    val retainedBytes: Long,
    val reasonCode: String,
    val ambiguity: String?
)

/** 支配树边（父→支配子）。 */
@Serializable
data class DomEdge(val parentStableId: String, val childStableId: String)

/**
 * 派生结果：记录输入摘要、参数版本与父级依赖。
 * validFromEventSeq..validUntilEventSeq 给出有效区间；失效时填 invalidReason。
 */
@Serializable
data class AnalysisResult(
    val resultId: String,
    val snapshotId: String,
    val materialDigest: String,
    val paramsVersion: Int,
    val parentEventSeq: Long,
    val computedAtEventSeq: Long,
    val status: ComputeStatus,
    val statusDetail: String?,
    val nodes: List<NodeRetention>,
    val domEdges: List<DomEdge>,
    val rootPaths: List<RootPath>,
    val leakCandidates: List<LeakCandidate>,
    val reachableCount: Int,
    val totalCount: Int,
    val validFromEventSeq: Long,
    val validUntilEventSeq: Long?,
    val invalidReason: String?
)

/** 两个快照的类群差异（稳定身份对应 + 改名追踪）。 */
@Serializable
data class ClassGroupRow(
    val classKey: String,
    val classNameV1: String?,
    val classNameV2: String?,
    val renamedFrom: String?,
    val renamedTo: String?,
    val instanceCountV1: Int,
    val instanceCountV2: Int,
    val shallowBytesV1: Long,
    val shallowBytesV2: Long,
    val retainedBytesV1: Long,
    val retainedBytesV2: Long,
    val sizeUnit: String,
    val correspondence: String,
    val stableIdsV1: List<String>,
    val stableIdsV2: List<String>
)

@Serializable
data class ClassGroupDiff(
    val resultId: String,
    val snapshotIdV1: String,
    val snapshotIdV2: String,
    val paramsVersion: Int,
    val rows: List<ClassGroupRow>,
    val validFromEventSeq: Long,
    val validUntilEventSeq: Long?,
    val invalidReason: String?
)

/** 对象图/支配树 SVG 的坐标基准（单位随结果暴露）。 */
@Serializable
data class GraphView(
    val title: String,
    val svg: String,
    val coordinateSystem: String,
    val unit: String,
    val timeBase: String,
    val nodePositions: List<NodePosition>
)

@Serializable
data class NodePosition(val stableId: String, val x: Int, val y: Int)
