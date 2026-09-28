package forensics.heapsnapshot.engine

import forensics.heapsnapshot.domain.Diagnostic
import forensics.heapsnapshot.domain.EdgeKind
import forensics.heapsnapshot.domain.ParsedSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class InputDigest(
    val snapshotIdentity: String,
    val parserVersion: String,
    val rawLineCount: Int,
    val rawSha256: String,
    val objectCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
    val incompleteMarkCount: Int,
    val diagnosticFingerprint: String,
)

@Serializable
data class NodeView(
    val id: String,
    val classId: String,
    val className: String,
    val addressRaw: String,
    val x: Int,
    val y: Int,
    val complete: Boolean,
    val reachable: Boolean,
    val selfSizeBytes: Long?,
    val selfSizeRaw: String,
    val retainedSizeBytes: Long?,
    val retainedStatus: String,
    val dominator: String?,
    val rootPathCount: Int,
    val allocationSiteId: String?,
)

@Serializable
data class EdgeView(
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val activeForReachability: Boolean,
    val treeEdge: Boolean,
    val field: String?,
)

@Serializable
data class RootPath(
    val rootRef: String,
    val rootKind: String,
    val hops: Int,
    val objectIds: List<String>,
    val edgeKinds: List<EdgeKind>,
)

@Serializable
data class LeakCandidate(
    val id: String,
    val objectId: String,
    val reasonCodes: List<String>,
    val retainedSizeBytes: Long?,
    val dominator: String?,
    val shortestPathLength: Int,
    val rootPathCount: Int,
)

@Serializable
data class AnalysisResult(
    val snapshotIdentity: String,
    val paramsVersion: Int,
    val parentDependencyIds: List<String>,
    val inputDigest: InputDigest,
    val reachableCount: Int,
    val nodes: List<NodeView>,
    val edges: List<EdgeView>,
    val rootPaths: Map<String, List<RootPath>>,
    val leakCandidates: List<LeakCandidate>,
    val notes: List<String>,
)

@Serializable
data class ClassGroup(
    val classId: String,
    val classKey: String,
    val className: String,
    val objectIds: List<String>,
    val retainedBytes: Long?,
    val retainedStatus: String,
    val selfBytes: Long?,
)

@Serializable
data class ClassGroupDelta(
    val classKey: String,
    val nameBefore: String?,
    val nameAfter: String?,
    val countBefore: Int,
    val countAfter: Int,
    val retainedBefore: Long?,
    val retainedAfter: Long?,
    val verdict: String,
    val identityBasis: String,
)

@Serializable
data class ClassGroupDiff(
    val snapshotBefore: String,
    val snapshotAfter: String,
    val paramsVersion: Int,
    val deltas: List<ClassGroupDelta>,
    val unmatchedCorrespondences: List<Diagnostic>,
)
