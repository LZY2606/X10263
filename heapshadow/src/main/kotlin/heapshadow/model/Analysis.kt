package heapshadow.model

import kotlinx.serialization.Serializable

/** 稳定身份的支配/可达分析节点结果。 */
@Serializable
data class NodeResult(
    val objectId: String,
    val address: String? = null,
    val classId: String,
    val className: String,
    val reachable: Boolean,
    val dominatorParentId: String?,
    /** 支配子树浅大小之和；含无法求和项时 retainedUncertain=true。 */
    val retainedSize: Long?,
    val retainedSizeUnit: String = "bytes",
    val retainedUncertain: Boolean = false,
    val shallowSize: Long?,
    val shallowRaw: String,
    val rootKind: RootKind? = null,
    val allocationSiteId: String? = null,
    val incomingStrong: Int = 0,
    val incomingWeak: Int = 0,
    val incomingInternal: Int = 0
)

@Serializable
data class RootPathCandidate(
    val objectId: String,
    /** 从 root 到该对象的一条最短路径；元素为 objectId 序列。 */
    val pathObjectIds: List<String>,
    val pathEdgeKinds: List<EdgeKind>,
    val length: Int,
    /** 多个等长 root 路径全部保留；ties 是其余等长候选。 */
    val tieWith: List<List<String>> = emptyList(),
    val ambiguous: Boolean = false,
    val rootKind: RootKind
)

@Serializable
data class LeakCandidate(
    val objectId: String,
    val className: String,
    val retainedSize: Long?,
    val reasonCode: String,
    val reason: String,
    val dominatorParentId: String?,
    val rootDistance: Int,
    val rootPathIds: List<String>
)

/**
 * 计算结果记录输入摘要、参数版本与父级依赖。永不覆盖，历史事件重放时直接读回。
 */
@Serializable
data class AnalysisResult(
    val analysisId: String,
    val snapshotId: String,
    val policyVersion: String,
    val policy: WeakPolicy,
    val createdAtEventId: String,
    val inputSummary: InputSummary,
    val parentDependencies: List<Dependency>,
    val nodes: Map<String, NodeResult>,
    val rootPaths: Map<String, RootPathCandidate>,
    val leakCandidates: List<LeakCandidate>,
    val diagnostics: List<Diagnostic>,
    /** 有效区间 [validFromEventId, validUntilEventId)；until 为 null 表示当前有效。 */
    val validFromEventId: String,
    val validUntilEventId: String? = null,
    val invalidReason: String? = null
)

@Serializable
data class InputSummary(
    val snapshotId: String,
    val parserVersion: String,
    val objectCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
    val classCount: Int,
    val incompleteBlockCount: Int,
    val rawPayloadSha256: String,
    val rawByteLength: Long
)

@Serializable
data class Dependency(val kind: String, val id: String, val version: String? = null)
