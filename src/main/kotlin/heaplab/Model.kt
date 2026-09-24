package heaplab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 不可变材料层：快照导入时的原始表示。地址/大小同时保留原始字符串与规范化值，
 * 无法转换的值留在原位置（规范化值为 null）并附诊断，绝不用默认值伪装成功。
 */

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
enum class RootType { THREAD, JNI_GLOBAL, JNI_LOCAL, SYSTEM_CLASS, MONITOR, NATIVE_STACK, OTHER }

@Serializable
enum class Severity { ERROR, WARNING }

/** 导入/计算三种互斥失败结果，外加 OK。 */
@Serializable
enum class RunStatus { OK, BUDGET_EXHAUSTED, FORMAT_CORRUPT, SEMANTIC_UNSOLVABLE }

@Serializable
data class Diagnostic(
    val path: String,
    val message: String,
    val severity: Severity,
)

@Serializable
data class ClassMeta(
    val classId: String,
    val name: String,
    val superClassId: String? = null,
    val loaderLabel: String? = null,
)

@Serializable
data class AllocationSite(
    val siteId: String,
    val className: String,
    val methodName: String,
    val file: String? = null,
    val line: Int? = null,
)

@Serializable
data class HeapObject(
    val objectId: String,
    /** 原始地址表示（如 "0x1020"），导入时原样保留。 */
    val addressRaw: String,
    /** 规范化地址；无法转换时为 null，诊断挂在导入记录上。 */
    val address: Long? = null,
    val classId: String,
    /** 原始大小表示（如 "4096"、"4 KiB"），原样保留。 */
    val sizeRaw: String,
    val sizeBytes: Long? = null,
    val allocSiteId: String? = null,
    val incomplete: Boolean = false,
)

@Serializable
data class HeapEdge(
    val edgeId: String,
    val fromId: String,
    val toId: String,
    val kind: EdgeKind,
    val field: String? = null,
)

@Serializable
data class RootRef(
    val objectId: String,
    val rootType: RootType,
    val label: String? = null,
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    val addressRaw: String,
    val lengthRaw: String,
    val reason: String,
)

@Serializable
data class Snapshot(
    val snapshotId: String,
    val name: String,
    val parserVersion: String,
    val capturedAt: String,
    val classes: List<ClassMeta>,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val roots: List<RootRef>,
    val incompleteBlocks: List<IncompleteBlock> = emptyList(),
    val allocationSites: List<AllocationSite> = emptyList(),
)

/** 计算参数。弱引用是否保活必须显式指定（null = 语义无解），不允许隐式默认。 */
@Serializable
data class AnalysisParams(
    val paramsVersion: Int,
    val weakRefsKeepAlive: Boolean? = null,
    val internalEdgesKeepAlive: Boolean = true,
    val maxVisitedNodes: Int = 200_000,
    val maxPathsPerObject: Int = 64,
)

@Serializable
data class PathHop(
    val fromId: String,
    val toId: String,
    val edgeId: String,
    val kind: EdgeKind,
)

data class AnalysisResult(
    val reachable: Set<String>,
    /** objectId -> 直接支配者；虚拟根用 VIRTUAL_ROOT 表示。 */
    val idomParent: Map<String, String>,
    val retainedBytes: Map<String, Long>,
    val depth: Map<String, Int>,
    /** objectId -> 全部等长最短 root 路径（超过上限时截断并记录）。 */
    val rootPaths: Map<String, List<List<PathHop>>>,
    val pathsTruncated: Set<String>,
) {
    companion object {
        const val VIRTUAL_ROOT = "@root"
    }
}

data class AnalysisOutcome(
    val status: RunStatus,
    val result: AnalysisResult?,
    val diagnostics: List<Diagnostic>,
)

data class ClassGroup(
    val classId: String,
    val name: String,
    val count: Int,
    val totalBytes: Long?,
)

data class ClassGroupDiff(
    val onlyInA: List<ClassGroup>,
    val onlyInB: List<ClassGroup>,
    val changed: List<ClassGroupChange>,
    val note: String,
)

data class ClassGroupChange(
    val classIdA: String?,
    val classIdB: String?,
    val name: String,
    val countA: Int?,
    val countB: Int?,
    val bytesA: Long?,
    val bytesB: Long?,
)

/** 机器可读事件。runId/snapshotId 指向不可变冻结结果，回到旧事件即恢复当时结论。 */
@Serializable
data class EventRecord(
    val eventId: String,
    val kind: String,
    val at: String,
    val snapshotId: String? = null,
    val runId: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
)

object EventKinds {
    const val SNAPSHOT_IMPORTED = "snapshot.imported"
    const val IMPORT_REJECTED = "import.rejected"
    const val ANALYSIS_COMPLETED = "analysis.completed"
    const val ANALYSIS_FAILED = "analysis.failed"
    const val PARAMS_REVISED = "params.revised"
    const val RULE_JUDGMENT = "rule.judgment"
    const val REVIEW_ACCEPTED = "review.accepted"
    const val REVIEW_REJECTED = "review.rejected"
    const val DECISION_CONFLICT = "decision.conflict"
    const val SOURCE_AMENDED = "source.amended"
    const val CASE_RECORDED = "case.recorded"
    const val EXPORT_CREATED = "export.created"
    const val DOCUMENT_IMPORTED = "document.imported"
}

/** 反例档案条目：基线 + 单一直接来源改动，采纳与未采纳结果都保留。 */
@Serializable
data class CaseRecord(
    val caseId: String,
    val title: String,
    val ruleId: String,
    val baselineSnapshotId: String,
    val mutatedSnapshotId: String? = null,
    val directSourceChange: String,
    val expectedAffectedScope: List<String> = emptyList(),
    val acceptedRunId: String? = null,
    val rejectedRunId: String? = null,
    val rejectedImportDiagnostics: List<Diagnostic> = emptyList(),
    val note: String = "",
)

/** 历史可见结论：某事件时刻冻结的依赖版本。 */
data class HistoricalView(
    val event: EventRecord,
    val runId: String?,
    val paramsVersion: Int?,
    val validFromSeq: Long?,
    val invalidAtSeq: Long?,
    val invalidationReason: String?,
)
