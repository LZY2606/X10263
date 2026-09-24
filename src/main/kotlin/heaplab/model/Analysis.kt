package heaplab.model

import kotlinx.serialization.Serializable

@Serializable
enum class WeakPolicy { KEEP_ALIVE, NON_KEEP_ALIVE }

@Serializable
data class AnalysisParams(
    val version: Int = 1,
    val weakPolicy: WeakPolicy = WeakPolicy.NON_KEEP_ALIVE,
    val maxRootPaths: Int = 512,
    val leakMinRetainedBytes: Long = 64,
    val leakRootCategories: List<String> = listOf("JNI_GLOBAL", "THREAD_LOCAL", "STATIC_FIELD"),
    val ruleVersion: String = "retention-rules/1",
)

@Serializable
enum class ComputeStatus { OK, BUDGET_EXHAUSTED, SEMANTIC_UNRESOLVABLE }

@Serializable
data class NodeView(
    val objectId: String,
    val classId: String,
    val className: String?,
    val address: String?,
    val shallowBytes: Long?,
    val reachable: Boolean,
    val dominatorParent: String?,
    val retainedBytes: Long?,
    val retainedInvalidReason: String?,
    val allocSiteId: String?,
    val allocLocation: String?,
    val complete: Boolean,
    val missingFields: List<String>,
    val duplicateAddress: Boolean,
    val rootCategories: List<String>,
)

@Serializable
data class RootPath(
    val rootRecordId: String,
    val rootCategory: String,
    val rootObjectId: String,
    val objectIds: List<String>,
    val edgeIds: List<String>,
    val length: Int,
)

@Serializable
data class RootPathSummary(
    val objectId: String,
    val shortestLength: Int?,
    val truncated: Boolean,
    val paths: List<RootPath>,
)

@Serializable
data class LeakCandidate(
    val objectId: String,
    val retainedBytes: Long,
    val rootCategories: List<String>,
    val ruleVersion: String,
)

@Serializable
data class GraphEdgeView(
    val edgeId: String,
    val fromId: String,
    val toId: String,
    val kind: EdgeKind,
    val label: String?,
    val traversed: Boolean,
)

@Serializable
data class AnalysisResult(
    val snapshotIdentity: String,
    val params: AnalysisParams,
    val status: ComputeStatus,
    val diagnostics: List<Diagnostic>,
    val reachableCount: Int,
    val unreachableCount: Int,
    val nodes: List<NodeView>,
    val edges: List<GraphEdgeView>,
    val rootPaths: List<RootPathSummary>,
    val leakCandidates: List<LeakCandidate>,
    val sizeUnit: String = "byte",
    val coordinateBasis: String = "layered-bfs/v1 (column=BFS depth from roots, row=order within layer)",
    val timeBasis: String?,
    val addressBasis: String?,
    val validFromSeq: Long,
    val validUntilSeq: Long? = null,
)

@Serializable
enum class ClassDiffStatus { SAME, RENAMED, ADDED, REMOVED }

@Serializable
data class ClassClusterRow(
    val classId: String,
    val nameA: String?,
    val nameB: String?,
    val countA: Int,
    val countB: Int,
    val shallowTotalA: Long,
    val shallowTotalB: Long,
    val status: ClassDiffStatus,
    val sampleObjectIds: List<String>,
)

@Serializable
data class SnapshotDiff(
    val snapshotA: String,
    val snapshotB: String,
    val clusters: List<ClassClusterRow>,
    val identityBasis: String = "stable object id (addresses are NOT identity)",
    val addressMigrations: Int,
    val diagnostics: List<Diagnostic>,
)
