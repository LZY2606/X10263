package hf

/** 类元数据。classId 是跨快照的稳定身份；name 可能在快照间改名。 */
data class ClassMeta(
    val classId: String,
    val name: String,
    val instanceSize: Long, // 单位：字节
    val superClassId: String? = null,
)

/** allocation site：签名含源码位置。 */
data class AllocSite(val siteId: String, val signature: String)

enum class EdgeKind { STRONG, WEAK, INTERNAL }

data class HeapEdge(val src: Long, val dst: Long, val kind: EdgeKind)

/**
 * 堆对象。objectId 是快照内稳定身份；address 只是当时的地址，
 * 允许重复（地址不当稳定身份），不得用作身份。
 */
data class HeapObject(
    val objectId: Long,
    val address: Long, // 原始地址值；展示用 0x 十六进制
    val classId: String,
    val size: Long, // 单位：字节
    val allocSiteId: String? = null,
    val incomplete: Boolean = false,
)

data class Root(val rootId: String, val kind: String, val objectId: Long)

/** 不完整块：原始字节区间保留，附原因。 */
data class IncompleteBlock(
    val blockId: String,
    val offset: Long, // 字节偏移
    val length: Long, // 字节长度
    val reason: String,
)

/** 不可变快照材料。原始文本 rawText 一并保留，派生层才做规范化。 */
data class Snapshot(
    val snapshotId: String,
    val name: String,
    val parserVersion: String,
    val capturedAt: Long, // epoch millis, UTC
    val rawText: String,
    val classes: List<ClassMeta>,
    val allocSites: List<AllocSite>,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val roots: List<Root>,
    val incompleteBlocks: List<IncompleteBlock>,
) {
    fun objectById(): Map<Long, HeapObject> = objects.associateBy { it.objectId }
    fun classById(): Map<String, ClassMeta> = classes.associateBy { it.classId }
}

enum class WeakPolicy { IGNORE, KEEP }

/**
 * 分析参数，显式版本化。weakPolicy 必须显式给出：
 * 弱引用是否保活由显式策略决定，不存在隐式默认。
 */
data class AnalysisParams(
    val version: Int,
    val weakPolicy: WeakPolicy,
    val maxNodes: Long = 100_000,
    val maxPathsPerObject: Int = 8,
    val leakThresholdBytes: Long = 64,
) {
    fun describe(): String =
        "params v$version(weak=$weakPolicy, maxNodes=$maxNodes, maxPaths=$maxPathsPerObject, leakThreshold=${leakThresholdBytes}B)"
}

data class LeakCandidate(
    val objectId: Long,
    val retainedBytes: Long,
    val dominatorChain: List<Long>, // 从虚拟 root 到该对象的支配链
    val rootPaths: List<List<Long>>,
)

/**
 * 一次计算的完整结论。记录输入摘要、参数版本、有效区间与失效原因。
 * 支配树只在可达图上构建；多个等长 root 路径全部保留（超出预算时截断并标记）。
 */
data class AnalysisResult(
    val snapshotId: String,
    val paramVersion: Int,
    val inputDigest: String,
    val reachable: Set<Long>,
    val idom: Map<Long, Long>, // objectId -> 支配父级 objectId；VIRTUAL_ROOT 为根
    val retained: Map<Long, Long>, // 字节
    val rootPaths: Map<Long, List<List<Long>>>,
    val truncatedPaths: Set<Long>,
    val leakCandidates: List<LeakCandidate>,
    val excludedIncomplete: Set<Long>,
    val validFromSeq: Long = 0,
    val validToSeq: Long? = null,
    val invalidationReason: String? = null,
) {
    companion object { const val VIRTUAL_ROOT: Long = -1L }
}
