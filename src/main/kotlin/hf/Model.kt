package hf

import kotlinx.serialization.Serializable

// ---------- 领域枚举 ----------

enum class EdgeKind { STRONG, WEAK, INTERNAL }

enum class RootType { THREAD, JNI_GLOBAL, JNI_LOCAL, CLASS, MONITOR, STACK_LOCAL, OTHER }

enum class WeakRefPolicy { WEAK_NOT_ALIVE, WEAK_KEEP_ALIVE }

enum class JudgmentStatus { ADOPTED, NOT_ADOPTED, CONFLICTED }

/** 机器可读的判定规则 id（反例档案与事件共用）。 */
object Rules {
    const val DOM_ON_REACHABLE = "DOM_ON_REACHABLE"       // 支配树只在可达图上构建
    const val WEAK_POLICY_EXPLICIT = "WEAK_POLICY_EXPLICIT" // 弱引用是否保活由显式策略决定
    const val KEEP_EQUAL_PATHS = "KEEP_EQUAL_PATHS"       // 多个等长 root 路径全部保留
    const val ADDRESS_NOT_IDENTITY = "ADDRESS_NOT_IDENTITY" // 地址不是稳定身份
}

// ---------- 诊断与量值 ----------

@Serializable
data class Diagnostic(val position: String, val message: String, val objectId: String? = null)

/** 大小量值：原始表示 + 单位基准保留，派生字节数可空；无法转换时附诊断，不丢原值。 */
@Serializable
data class SizeValue(val raw: String, val unit: String, val bytes: Long?, val diagnostic: Diagnostic? = null)

fun parseSize(raw: String, unit: String, position: String, objectId: String?): SizeValue {
    val value = raw.trim().toDoubleOrNull()
        ?: return SizeValue(raw, unit, null, Diagnostic(position, "无法解析的大小表示: '$raw'，原值保留在原地", objectId))
    val factor = when (unit) {
        "B" -> 1L
        "KiB" -> 1024L
        "MiB" -> 1024L * 1024L
        else -> return SizeValue(raw, unit, null, Diagnostic(position, "未知大小单位: '$unit'，原值保留在原地", objectId))
    }
    return SizeValue(raw, unit, (value * factor).toLong())
}

fun formatBytes(bytes: Long): String = when {
    bytes % (1024L * 1024L) == 0L && bytes >= 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
    bytes % 1024L == 0L && bytes >= 1024L -> "${bytes / 1024L} KiB"
    else -> "$bytes B"
}

// ---------- 存储行（不可变材料层） ----------

data class SnapshotRow(val id: String, val name: String, val parserVersion: String, val createdAt: String, val paramsVersion: Int)
data class ClassRow(val id: String, val snapshotId: String, val name: String, val superName: String?)
data class ObjectRow(
    val id: String, val snapshotId: String, val origId: String, val addressRaw: String,
    val classId: String, val sizeRaw: String, val sizeUnit: String, val sizeBytes: Long?,
    val allocSite: String?, val incomplete: Boolean, val diag: String?
)
data class EdgeRow(val snapshotId: String, val fromId: String, val toId: String, val kind: EdgeKind, val name: String)
data class RootRow(val snapshotId: String, val objectId: String, val rootType: RootType)
data class IncompleteBlockRow(val id: String, val snapshotId: String, val offsetRaw: String, val payloadRaw: String, val reason: String)

data class EventRow(
    val seq: Long, val id: String, val snapshotId: String?, val type: String,
    val payloadJson: String, val inputDigest: String, val paramsVersion: Int,
    val parents: List<String>, val createdAt: String
)

data class ArchiveCaseRow(
    val id: String, val title: String, val baselineJson: String, val variantJson: String,
    val judgmentJson: String, val adoptedJson: String, val rejectedJson: String, val createdAt: String
)

// ---------- 分析输入/参数 ----------

data class SnapshotData(
    val snapshot: SnapshotRow,
    val classes: List<ClassRow>,
    val objects: List<ObjectRow>,
    val edges: List<EdgeRow>,
    val roots: List<RootRow>,
    val incompleteBlocks: List<IncompleteBlockRow> = emptyList()
)

@Serializable
data class AnalysisParams(
    val weakPolicy: WeakRefPolicy = WeakRefPolicy.WEAK_NOT_ALIVE,
    val paramsVersion: Int = 1,
    val maxPathsPerNode: Int = 16,
    val budget: Int = 1_000_000
)

// ---------- 分析结果（派生层，全部带来源） ----------

@Serializable
data class Judgment(val ruleId: String, val status: String, val detail: String, val affectedObjects: List<String>)

@Serializable
data class ObjectAnalysis(
    val objectId: String, val origId: String, val className: String, val addressRaw: String,
    val sizeRaw: String, val sizeUnit: String, val sizeBytes: Long?, val allocSite: String?,
    val incomplete: Boolean, val reachable: Boolean, val depth: Int?,
    val dominatorParent: String?, val retainedBytes: Long,
    val rootPaths: List<List<String>>, val diagnostics: List<Diagnostic> = emptyList()
)

@Serializable
data class LeakCandidate(val stableId: String, val objectId: String, val retainedBytes: Long, val paths: List<List<String>>)

@Serializable
data class AnalysisResult(
    val snapshotId: String, val paramsVersion: Int, val weakPolicy: WeakRefPolicy, val inputDigest: String,
    val objects: List<ObjectAnalysis>, val reachableCount: Int, val totalRetainedBytes: Long,
    val leakCandidates: List<LeakCandidate>, val judgments: List<Judgment>, val diagnostics: List<Diagnostic>
)

/** 三种失败必须是三种不同结果，不得混为日志。 */
sealed interface AnalysisOutcome {
    data class Success(val result: AnalysisResult) : AnalysisOutcome
    data class BudgetExhausted(val consumed: Int, val limit: Int, val stage: String) : AnalysisOutcome
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : AnalysisOutcome
    data class SemanticUnsolvable(val reason: String, val diagnostics: List<Diagnostic>) : AnalysisOutcome
}

// ---------- 类群差异（跨快照） ----------

@Serializable
data class ClassRename(val oldName: String, val newName: String, val evidenceObjectIds: List<String>)

@Serializable
data class ClassGroupDiff(
    val snapshotA: String, val snapshotB: String,
    val onlyInA: List<String>, val onlyInB: List<String>,
    val renames: List<ClassRename>, val ambiguous: List<List<ClassRename>>
)
