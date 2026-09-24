package heapsnapshot.domain

import kotlinx.serialization.Serializable

/** 派生节点：支配父级、保留大小、root 路径都挂在稳定身份上。 */
@Serializable
data class DerivedNode(
    val ref: SnapshotRef,
    val objectId: String,
    val idomParent: SnapshotRef?,
    val retainedBytes: Double,
    val retainedIncomplete: Boolean,
    val selfIncomplete: Boolean,
    val rootDistance: Int,
    val rootPaths: List<List<String>>,
    val leakCandidate: Boolean
)

@Serializable
data class AnalysisResult(
    val resultId: String,
    val snapshotId: String,
    val policyVersion: String,
    val inputSummary: InputSummary,
    val parentDependencyIds: List<String>,
    val createdAtEventId: String,
    val reachableObjectIds: List<String>,
    val unreachableObjectIds: List<String>,
    val nodes: List<DerivedNode>,
    val leakCandidates: List<String>,
    val diagnostics: List<ParseDiagnostic>,
    val validFromEventId: String,
    val validUntilEventId: String? = null,
    val invalidationReason: String? = null,
    val basis: ResultBasis
)

@Serializable
data class InputSummary(
    val objectCount: Int,
    val edgeCount: Int,
    val rootCount: Int,
    val classCount: Int,
    val materialHash: String
)

/** 单位、坐标、时间基准随结果一起暴露。 */
@Serializable
data class ResultBasis(
    val sizeUnit: String,
    val coordinateBasis: String,
    val coordinateUnit: String,
    val timeBasis: String,
    val takenAtEpochMs: Long?,
    val weakKeepsAlive: Boolean,
    val internalKeepsAlive: Boolean
)
