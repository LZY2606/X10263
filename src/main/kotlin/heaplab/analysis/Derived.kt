package heaplab.analysis

import heaplab.domain.EdgeKind
import heaplab.domain.RootKind
import kotlinx.serialization.Serializable

/** 派生层节点：稳定身份 = 快照范围 id（urn），与地址解耦。 */
@Serializable
data class GraphNode(
    val urn: String,                 // urn:snapshotId/objectId，稳定身份
    val snapshotId: String,
    val objectId: String,
    val address: String,            // 仅展示，不作身份
    val classId: String,
    val className: String,
    val shallowBytes: Long?,        // 规范化后；null = 无法转换（原值保留在原始层）
    val shallowRaw: String,
    val sizeUnit: String,
    val incomplete: Boolean,
    val isRoot: Boolean,
    val rootKinds: List<RootKind>,
    val reachable: Boolean,         // 按策略可达
    val weakReachableOnly: Boolean, // 仅经弱引用可达（KEEP_ALIVE 下存活）
    val retainedBytes: Long?,
    val retainedRange: SizeRange?,
    val invalidReason: String?,
    val dominatorUrn: String?,      // 支配父级；虚拟根用 urn:virtual-root
    val rootPaths: List<RootPath>,  // 全部等长最短 root 路径
    val svg: NodeSvgMeta
)

@Serializable
data class SizeRange(val lowInclusive: Long, val highInclusive: Long, val unit: String = "B")

@Serializable
data class RootPath(
    val nodes: List<String>,        // urn 序列，从虚拟根到该节点
    val edges: List<String>,        // 稳定 edge id
    val length: Int,
    val containsWeak: Boolean,
    val weakEdgeKinds: List<EdgeKind>
)

@Serializable
data class GraphEdge(
    val urn: String,                // urn:snapshotId/edgeId
    val snapshotId: String,
    val edgeId: String,
    val fromUrn: String,
    val toUrn: String,
    val kind: EdgeKind,
    val field: String?,
    val retainedForReachability: Boolean, // 是否进入本次可达图
    val svg: EdgeSvgMeta
)

@Serializable
data class NodeSvgMeta(val x: Int, val y: Int, val layer: Int)
@Serializable
data class EdgeSvgMeta(val waypoint: String? = null)

@Serializable
data class AnalysisResult(
    val resultId: String,
    val snapshotId: String,
    val paramsVersion: Int,
    val inputDigest: String,        // 输入摘要
    val parentResultId: String?,    // 父级依赖
    val virtualRootUrn: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val unitBasis: String,
    val timeBasis: String,
    val validInterval: String?,
    val invalidReasons: Map<String, String>,
    val leakCandidates: List<LeakCandidate>,
    val diagnostics: List<heaplab.domain.Diagnostic>
)

@Serializable
data class LeakCandidate(
    val nodeUrn: String,
    val className: String,
    val retainedBytes: Long?,
    val retainedRange: SizeRange?,
    val shortestRootPathCount: Int,
    val ambiguous: Boolean,
    val rationale: String
)

/** 两个快照的类群差异（身份对应后比较）。 */
@Serializable
data class ClassGroupDelta(
    val classGroup: String,         // 规范化类群名
    val aliases: List<String>,
    val beforeCount: Int,
    val afterCount: Int,
    val beforeShallowBytes: Long?,
    val afterShallowBytes: Long?,
    val beforeRetainedBytes: Long?,
    val afterRetainedBytes: Long?,
    val beforeIdentity: String,     // urn
    val afterIdentity: String?,     // 改名/对应后的 urn；null=无法对应
    val diffBytes: Long?,
    val note: String?
)

@Serializable
data class DiffResult(
    val diffId: String,
    val beforeSnapshotId: String,
    val afterSnapshotId: String,
    val beforeParamsVersion: Int,
    val afterParamsVersion: Int,
    val inputDigest: String,
    val groups: List<ClassGroupDelta>,
    val identityMap: Map<String, String>, // beforeUrn -> afterUrn
    val unmatchedBefore: List<String>,
    val unmatchedAfter: List<String>
)
