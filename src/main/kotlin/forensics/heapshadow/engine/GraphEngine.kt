package forensics.heapshadow.engine

import forensics.heapshadow.model.*

/** 引擎输出的稳定身份：全部基于原始材料 id，不基于地址或下标重编号。 */

data class NodeView(
    val objectId: String,
    val klassId: String?,
    val className: String?,
    val shallowBytes: Long?,
    val shallowRaw: String?,
    val reachable: Boolean,
    val reachableVia: List<String>,
    val ordinal: Int,
    val incomplete: Boolean,
    val addressRaw: String?,
)

data class EdgeView(
    val edgeId: String,
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val keepsAliveUnderPolicy: Boolean,
    val label: String?,
    val ordinal: Int,
)

/** 多个等长/合法 root 路径全部保留；无法唯一决定时给全部候选。 */
data class RootPath(
    /** 路径上的稳定 id：root -> ... -> object。 */
    val objectIds: List<String>,
    val edgeIds: List<String>,
    val length: Int,
    val rootKinds: List<RootKind>,
    val shortest: Boolean,
)

data class RootPathSet(
    val objectId: String,
    val shortestLength: Int,
    val paths: List<RootPath>,
    /** 等长最短路径数（>1 即无法唯一决定）。 */
    val tieCount: Int,
    val truncated: Boolean,
    val budgetDiagnosticId: String?,
)

data class DominatorView(
    val objectId: String,
    /** 支配父级的稳定 id；虚拟根 idom 为 [GraphEngine.SUPER_ROOT]。 */
    val immediateDominator: String,
    val dominatedIds: List<String>,
    val retainedBytes: Long?,
    /** retained 无法计算（尺寸未知）时保留的原始对象 id 与原因。 */
    val retainedUnknownReason: String?,
)

data class LeakCandidate(
    val objectId: String,
    val retainedBytes: Long?,
    val shortestPathLength: Int,
    val rootPathTieCount: Int,
    val reason: String,
)

data class AnalysisResult(
    val parameterVersion: String,
    val weakPolicy: WeakPolicy,
    val inputDigest: InputDigest,
    val nodes: List<NodeView>,
    val edges: List<EdgeView>,
    val reachableIds: List<String>,
    val dominators: List<DominatorView>,
    val rootPaths: List<RootPathSet>,
    val leakCandidates: List<LeakCandidate>,
    val diagnostics: List<Diagnostic>,
    /** BOTH_VIEWS 时第二套视图（弱引用保活）。 */
    val altView: AnalysisResult?,
) {
    fun byId(id: String): DominatorView? = dominators.firstOrNull { it.objectId == id }
}
