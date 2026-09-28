package forensics.graph

import forensics.model.Diagnostic

/** 显式策略参数（参数版本由账本管理，引擎只消费某一确定版本）。 */
data class AnalysisParams(
    val paramVersion: Int,
    val weakKeepsAlive: Boolean = false,
    val internalKeepsAlive: Boolean = true,
    val leakRetainedMinBytes: Long = 1_024L,
    val maxRootPathsPerObject: Int = 128,
    val classCorrespondence: List<ClassCorrespondence> = emptyList(),
)

/** 跨快照类改名/身份对应：canonicalId 是稳定身份，members 按 snapshotId -> classId。 */
data class ClassCorrespondence(val canonicalId: String, val members: Map<String, String>)

const val SUPER_ROOT = "SUPER_ROOT"

data class Validity(
    val valid: Boolean,
    val invalidPositions: List<String> = emptyList(),
    val invalidReason: String? = null,
)

/** 图中节点。稳定身份是 nodeId=<snapshotId>:<objectId>，地址仅作显示。 */
data class NodeView(
    val nodeId: String,
    val snapshotId: String,
    val objectId: String,
    val addressDisplay: String?,
    val className: String?,
    val classId: String?,
    val shallowBytes: Long?,
    val shallowRaw: String?,
    val unit: String?,
    val sizeBasis: String?,
    val reachable: Boolean,
    val isRoot: Boolean,
    val incomplete: Boolean,
)

data class EdgeView(
    val edgeId: String,
    val fromNode: String,
    val toNode: String,
    val kind: String,
    val keepsAlive: Boolean,
    val field: String?,
)

/** 一条 root 路径；多个等长/无法唯一决定时保留全部合法候选。 */
data class RootPath(
    val pathId: String,
    val nodeIds: List<String>,
    val edgeIds: List<String>,
    val rootKind: String,
    val lengthEdges: Int,
    val truncated: Boolean = false,
)

data class LeakCandidate(
    val rank: Int,
    val nodeId: String,
    val retainedBytes: Long,
    val dominatorNode: String?,
    val shortestRootPathLength: Int?,
    val reason: String,
)

data class AnalysisResult(
    val analysisId: String,
    val caseId: String,
    val snapshotId: String,
    val paramVersion: Int,
    val inputSummary: InputSummary,
    val parentDependencies: List<String>,
    val nodes: List<NodeView>,
    val edges: List<EdgeView>,
    val dominator: Map<String, String?>,
    val retainedBytes: Map<String, Long>,
    val rootPaths: Map<String, List<RootPath>>,
    val rootPathTruncated: Boolean,
    val leakCandidates: List<LeakCandidate>,
    val validity: Validity,
    val diagnostics: List<Diagnostic>,
)

data class InputSummary(
    val snapshotId: String,
    val objectCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
    val incompleteChunkCount: Int,
    val parserVersion: String,
    val materialSha: String,
)

/** 类群（跨快照稳定身份 canonicalId）。 */
data class ClassGroupView(
    val canonicalId: String,
    val className: String,
    val renamed: Boolean,
    val instanceCount: Long,
    val shallowBytes: Long,
    val retainedBytes: Long,
)

data class ClassGroupDelta(
    val canonicalId: String,
    val className: String,
    val renamed: Boolean,
    val instanceCountDelta: Long?,
    val retainedBytesDelta: Long?,
    val onlyInSnapshot: String?,
)

data class SnapshotDiff(
    val diffId: String,
    val caseId: String,
    val baseSnapshotId: String,
    val targetSnapshotId: String,
    val paramVersion: Int,
    val parentDependencies: List<String>,
    val groupsBase: List<ClassGroupView>,
    val groupsTarget: List<ClassGroupView>,
    val deltas: List<ClassGroupDelta>,
)
