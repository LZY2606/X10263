package heapforensics.core

import kotlinx.serialization.Serializable

enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class RootKind { THREAD, JNI_GLOBAL, JNI_LOCAL, CLASS_LOADER, MONITOR, SYSTEM_CLASS, OTHER }

enum class Severity { INFO, WARN, ERROR }

@Serializable
data class HeapClass(
    val classId: String,
    val name: String,
    val superClassId: String? = null,
    val loaderId: String? = null,
    val instanceSize: Long = 0,
)

@Serializable
data class HeapObject(
    val objectId: String,
    val address: String,
    val classId: String,
    val sizeBytes: Long,
    val allocSite: String? = null,
    val incomplete: Boolean = false,
)

@Serializable
data class HeapEdge(
    val fromId: String,
    val toId: String,
    val kind: EdgeKind,
    val fieldName: String? = null,
)

@Serializable
data class RootRef(
    val objectId: String,
    val kind: RootKind,
    val note: String? = null,
)

@Serializable
data class IncompleteBlock(
    val blockId: String,
    val reason: String,
    val line: Int,
    val rawText: String,
)

@Serializable
data class Diagnostic(
    val line: Int,
    val severity: Severity,
    val message: String,
    val raw: String,
)

@Serializable
data class HeapSnapshot(
    val snapshotId: String,
    val parserVersion: String,
    val capturedAt: String,
    val epochMillis: Long,
    val classes: List<HeapClass>,
    val objects: List<HeapObject>,
    val edges: List<HeapEdge>,
    val roots: List<RootRef>,
    val incompleteBlocks: List<IncompleteBlock>,
    val diagnostics: List<Diagnostic>,
) {
    val objectIndex: Map<String, HeapObject> by lazy { objects.associateBy { it.objectId } }
    val classIndex: Map<String, HeapClass> by lazy { classes.associateBy { it.classId } }
}

/** 分析参数：弱引用/内部边是否保活由显式策略决定；预算耗尽是独立结果。 */
@Serializable
data class AnalysisParams(
    val version: Int = 1,
    val weakKeepAlive: Boolean = false,
    val internalKeepAlive: Boolean = true,
    val maxVisits: Int = 100_000,
    val maxPathsPerObject: Int = 64,
    val maxPathDepth: Int = 4_096,
    val leakThresholdBytes: Long = 1,
)

/** 三种失败必须是三种不同结果；成功带值。 */
@Serializable
sealed interface Outcome {
    @Serializable
    data class Ok(val note: String = "ok") : Outcome

    @Serializable
    data class BudgetExhausted(val budget: String, val detail: String) : Outcome

    @Serializable
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : Outcome

    @Serializable
    data class SemanticallyUnsolvable(val reasons: List<String>) : Outcome
}

sealed interface AnalysisResult<out T> {
    data class Ok<T>(val value: T) : AnalysisResult<T>
    data class BudgetExhausted(val budget: String, val detail: String) : AnalysisResult<Nothing>
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : AnalysisResult<Nothing>
    data class SemanticallyUnsolvable(val reasons: List<String>) : AnalysisResult<Nothing>

    fun statusName(): String = when (this) {
        is Ok -> "OK"
        is BudgetExhausted -> "BUDGET_EXHAUSTED"
        is FormatCorrupt -> "FORMAT_CORRUPT"
        is SemanticallyUnsolvable -> "SEMANTICALLY_UNSOLVABLE"
    }
}

fun <T, R> AnalysisResult<T>.map(block: (T) -> R): AnalysisResult<R> = when (this) {
    is AnalysisResult.Ok -> AnalysisResult.Ok(block(value))
    is AnalysisResult.BudgetExhausted -> this
    is AnalysisResult.FormatCorrupt -> this
    is AnalysisResult.SemanticallyUnsolvable -> this
}

fun <T, R> AnalysisResult<T>.flatMap(block: (T) -> AnalysisResult<R>): AnalysisResult<R> = when (this) {
    is AnalysisResult.Ok -> block(value)
    is AnalysisResult.BudgetExhausted -> this
    is AnalysisResult.FormatCorrupt -> this
    is AnalysisResult.SemanticallyUnsolvable -> this
}
