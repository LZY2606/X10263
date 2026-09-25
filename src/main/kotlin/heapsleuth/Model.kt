package heapsleuth

import kotlinx.serialization.Serializable

/** 边的种类：强引用 / 弱引用 / VM 内部引用。 */
enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class Severity { INFO, WARNING, ERROR }

/** 带位置的诊断。path 为 JSON 指针式位置（如 /objects/3/size）。 */
@Serializable
data class Diagnostic(
    val path: String,
    val code: String,
    val message: String,
    val severity: Severity = Severity.WARNING,
)

/** 不可变材料：类元数据。 */
@Serializable
data class ClassMeta(
    val classId: String,
    val name: String,
    val superName: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/** 不可变材料：堆对象。sizeRaw 保留原始表示，sizeBytes 为派生层规范化结果（不可转换时为 null）。 */
@Serializable
data class HeapObject(
    val objectId: String,
    val addressRaw: String,
    val classId: String,
    val sizeRaw: String,
    val sizeBytes: Long? = null,
    val allocSite: String? = null,
    val incomplete: Boolean = false,
)

@Serializable
data class HeapEdge(
    val edgeId: String,
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val name: String? = null,
)

@Serializable
data class HeapRoot(
    val rootId: String,
    val objectId: String,
    val rootType: String,
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    val offsetRaw: String,
    val length: Long? = null,
    val reason: String,
    val raw: String? = null,
)

/** 一个快照的全部不可变材料 + 导入期诊断。 */
@Serializable
data class SnapshotBundle(
    val snapshotId: String,
    val name: String,
    val parserVersion: String,
    val classes: List<ClassMeta> = emptyList(),
    val objects: List<HeapObject> = emptyList(),
    val edges: List<HeapEdge> = emptyList(),
    val roots: List<HeapRoot> = emptyList(),
    val incompleteBlocks: List<IncompleteBlock> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList(),
) {
    fun canonical(): SnapshotBundle = copy(
        classes = classes.sortedBy { it.classId },
        objects = objects.sortedBy { it.objectId },
        edges = edges.sortedBy { it.edgeId },
        roots = roots.sortedBy { it.rootId },
        incompleteBlocks = incompleteBlocks.sortedBy { it.blockId },
        diagnostics = diagnostics.sortedWith(compareBy({ it.path }, { it.code })),
    )
}

/** 显式分析参数（带版本）。弱引用是否保活由 weakPolicy 显式决定。 */
@Serializable
data class AnalysisParams(
    val version: Int,
    val label: String,
    val weakPolicy: WeakPolicy = WeakPolicy.IGNORE,
    val internalEdgesStrong: Boolean = true,
    val nodeBudget: Int = 100_000,
    val pathBudget: Int = 64,
    val leakThresholdBytes: Long = 0,
)

enum class WeakPolicy { IGNORE, RETAIN }

@Serializable
data class Retained(val bytes: Long, val partial: Boolean = false, val reasons: List<String> = emptyList())

@Serializable
data class LeakCandidate(
    val key: String,
    val objectId: String,
    val retainedBytes: Long,
    val partial: Boolean,
    val paths: List<List<String>>,
)

/** 一次分析运行的完整结论（持久化，回看历史事件时原样呈现，不用最新结果覆盖）。 */
@Serializable
data class AnalysisResult(
    val reachable: List<String>,
    val unreachable: List<String>,
    val idom: Map<String, String>,
    val retained: Map<String, Retained>,
    val rootPaths: Map<String, List<List<String>>>,
    val pathsTruncated: List<String>,
    val droppedEdges: List<String>,
    val diagnostics: List<Diagnostic>,
    val candidates: List<LeakCandidate>,
)

/** 运行级结果：成功 / 资源预算耗尽 / 语义无解 —— 三种不同结果。 */
@Serializable
sealed interface RunOutcome {
    @Serializable
    data class Ok(val result: AnalysisResult) : RunOutcome

    @Serializable
    data class BudgetExhausted(val visited: Int, val budget: Int, val detail: String) : RunOutcome

    @Serializable
    data class SemanticUnsolvable(val diagnostics: List<Diagnostic>) : RunOutcome
}

/** 解析级结果：成功（可带警告）/ 格式损坏 —— 与运行级结果区分。 */
sealed interface ParseOutcome {
    data class Ok(val bundle: SnapshotBundle) : ParseOutcome
    data class Corrupt(val diagnostics: List<Diagnostic>) : ParseOutcome
}

/** 类群差异（两个快照之间，按 classId 对应身份，不按名字）。 */
@Serializable
data class ClassGroupDiff(
    val classId: String,
    val nameA: String?,
    val nameB: String?,
    val countA: Long,
    val countB: Long,
    val bytesA: Long,
    val bytesB: Long,
    val flags: List<String>,
)

@Serializable
data class Decision(
    val decisionId: String,
    val runId: String,
    val candidateKey: String,
    val verdict: String, // ACCEPT | REJECT | ADJUST
    val rationale: String,
    val reviewer: String,
    val createdAt: String,
    val supersedes: String? = null,
)

@Serializable
data class JournalEvent(
    val eventId: String,
    val seq: Long,
    val caseId: String,
    val kind: String,
    val payload: String,
    val createdAt: String,
    val parentEventId: String? = null,
)
