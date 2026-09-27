package heaplab.analysis

import heaplab.model.*
import kotlinx.serialization.Serializable

@Serializable
data class CoordinateBasis(
    /** 量纲与基准，每个派生值都引用这里。 */
    val sizeUnit: String,
    val addressBase: String,
    val timeBasis: String,
    val snapshotTakenAtRaw: String?,
    val snapshotTakenAtEpochMs: Long?,
    /** 有效区间：本结果对哪份输入摘要、哪版参数、哪些父级依赖有效。 */
    val validForInputSha: String,
    val validForParamsVersion: Int,
    val parentDependencies: List<String>,
    /** 失效原因：结果在何种前提下不再可信（不删除，只标注）。 */
    val invalidationReasons: List<String>
)

@Serializable
data class ObjectView(
    /** 稳定身份：snapshotId#rawObjectId；显示与导出/导入往返都用它。 */
    val nodeKey: String,
    val snapshotId: String,
    val rawObjectId: String,
    val correspondenceKey: String?,
    val addressRaw: String,
    val classId: String,
    val className: String,
    val allocationSiteId: String?,
    val allocationLocationRaw: String?,
    val rootKind: RootKind,
    val shallowSizeRaw: String,
    val shallowSizeBytes: Long?,
    val sizeUnit: String,
    val incomplete: Boolean,
    val incompleteChunkId: String?,
    val reachable: Boolean,
    val rootDistance: Int?,
    val dominatorParentKey: String?,
    val retainedSizeBytes: Long,
    val rootPathCount: Int,
    val diagnostics: List<String>
)

@Serializable
data class LeakCandidate(
    val targetKey: String,
    val targetRawObjectId: String,
    val className: String,
    val retainedSizeBytes: Long,
    val sizeUnit: String,
    val dominatorParentKey: String?,
    val allocationLocationRaw: String?,
    /** 全部等长 root 路径候选；无法唯一决定时不做唯一化。 */
    val rootPaths: List<RootPath>,
    val incomplete: Boolean
)

@Serializable
data class ClassGroup(
    val classId: String,
    val classNameRaw: String,
    val objectCount: Int,
    val shallowSizeBytes: Long,
    val retainedSizeBytes: Long,
    val incompleteObjectCount: Int,
    val sizeUnit: String
)

@Serializable
data class GraphEdgeView(
    val edgeId: String,
    val fromKey: String,
    val toKey: String,
    val kind: EdgeKind,
    val fieldRaw: String?,
    val participatingInReachability: Boolean
)

@Serializable
data class AnalysisResult(
    val resultId: String,
    val snapshotId: String,
    val snapshotLabel: String,
    val paramsVersion: Int,
    val weakPolicy: WeakPolicy,
    val inputSummary: InputSummary,
    val basis: CoordinateBasis,
    val totalObjects: Int,
    val reachableObjects: Int,
    val unreachableObjects: Int,
    val excludedWeakEdgeIds: List<String>,
    val objects: List<ObjectView>,
    val edges: List<GraphEdgeView>,
    val rootPathsByTarget: Map<String, List<RootPath>>,
    val leakCandidates: List<LeakCandidate>,
    val classGroups: List<ClassGroup>,
    val diagnostics: List<Diagnostic>
)

@Serializable
data class InputSummary(
    val snapshotSha256: String,
    val rawSizeBytes: Long,
    val objectCount: Int,
    val edgeCount: Int,
    val classCount: Int,
    val incompleteChunkCount: Int,
    val parserVersion: String,
    val formatDialect: String
)
