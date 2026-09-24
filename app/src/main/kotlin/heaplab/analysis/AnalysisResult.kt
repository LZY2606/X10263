package heaplab.analysis

import heaplab.model.EdgeStrength
import kotlinx.serialization.Serializable

/** 资源预算耗尽 / 格式损坏 / 语义无解 —— 三种不同结果，绝不混用。 */
@Serializable
sealed class AnalysisOutcome {
    @Serializable
    data class Ok(val result: AnalysisResult) : AnalysisOutcome()

    @Serializable
    data class FormatCorrupt(val reason: String, val diagnostics: List<Located>) : AnalysisOutcome()

    @Serializable
    data class SemanticUnresolved(val reason: String, val diagnostics: List<Located>) : AnalysisOutcome()

    @Serializable
    data class BudgetExhausted(val reason: String, val diagnostics: List<Located>) : AnalysisOutcome()
}

@Serializable
data class Located(val line: Int, val col: Int, val code: String, val message: String, val raw: String)

@Serializable
data class GraphEdge(
    val edgeUid: String,
    val fromId: String,
    val toId: String,
    val strength: EdgeStrength,
    val label: String,
    /** 该边在当前策略下是否参与可达性。 */
    val reach: Boolean,
    /** 该边在当前策略下是否参与支配关系。 */
    val dominates: Boolean
)

@Serializable
data class RootPath(
    val rootObjId: String,
    val rootCategory: String,
    val objectIds: List<String>,
    val edgeUids: List<String>,
    val length: Int
)

@Serializable
data class ObjectResult(
    val objId: String,
    val reachable: Boolean,
    val reachableViaWeakOnly: Boolean,
    val dominatorId: String?,           // 虚拟根用 ROOT
    val dominatorIsVirtualRoot: Boolean,
    val shallowBytes: Long?,            // 无法规范化时为 null，绝不填 0 伪装
    val shallowObjectCount: Int,
    val retainedBytes: Long?,
    val retainedObjectCount: Int,
    val retainedIds: List<String>,
    val rootPaths: List<RootPath>,      // 全部等长最短 root 路径（可多于一条）
    val ambiguity: Boolean              // 存在多个等长 root 路径或支配归因不唯一
)

@Serializable
data class LeakCandidate(
    val objId: String,
    val retainedBytes: Long?,
    val retainedObjectCount: Int,
    val reason: String,
    val allocationSite: String?,
    val siteLocation: String?,
    val siteEpochRaw: String?,
    val rootDistance: Int
)

@Serializable
data class AnalysisResult(
    val runId: String,
    val digest: InputDigest,
    val parameters: Parameters,
    val parentRunId: String?,
    val nodeIds: List<String>,
    val edges: List<GraphEdge>,
    val virtualRootId: String,
    val objects: Map<String, ObjectResult>,
    val dominatorOrder: List<String>,
    val leakCandidates: List<LeakCandidate>,
    val unreachableIds: List<String>,
    val weakOnlyReachableIds: List<String>,
    val notes: List<String>
)
