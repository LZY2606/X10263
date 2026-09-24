package heapshadow.model

import kotlinx.serialization.Serializable

/**
 * 一个带有单位的领域量。导入时 raw 逐字保留；派生层才尝试规范化。
 * 无法转换时 normalized 为 null，diagnostic 必填，值留在原位置。
 */
@Serializable
data class RawQuantity(
    val raw: String,
    val unit: String? = null,
    val base: String? = null,
    val normalized: Double? = null,
    val diagnostic: String? = null
)

@Serializable
data class Diagnostic(
    val code: String,
    val severity: Severity = Severity.WARNING,
    val message: String,
    /** 定位：字节偏移（0 起）、行、列，以及原始片段附近的文本。 */
    val offset: Long? = null,
    val line: Int? = null,
    val column: Int? = null,
    val snippet: String? = null,
    /** 语义定位：对象 id / 边索引 / 字段名。 */
    val objectId: String? = null,
    val edgeIndex: Int? = null,
    val field: String? = null
) {
    enum class Severity { WARNING, ERROR }
}

/** 不完整块：源文件中被截断或损坏但仍需保留的原始区间。 */
@Serializable
data class RawBlock(
    val blockId: String,
    val kind: String,
    val byteStart: Long? = null,
    val byteEnd: Long? = null,
    val raw: String,
    val reason: String
)

@Serializable
data class RawClass(
    val classId: String,
    val name: String,
    val classLoaderId: String? = null,
    val superClassId: String? = null,
    val sourceName: String? = null
)

@Serializable
data class RawAllocationSite(
    val siteId: String,
    val methodName: String,
    val classId: String? = null,
    val location: String? = null,
    val lineNumber: Int? = null
)

@Serializable
data class RawObject(
    val objectId: String,
    /** 地址按原始文本保留；地址不是稳定身份，只作证据展示。 */
    val address: String? = null,
    val classId: String,
    val shallowSize: RawQuantity,
    val allocationSiteId: String? = null,
    val extra: Map<String, String> = emptyMap()
)

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
data class RawEdge(
    val edgeIndex: Int,
    val fromObjectId: String,
    val toObjectId: String,
    val kind: EdgeKind = EdgeKind.STRONG,
    val label: String? = null,
    /** 引用指向未知对象时保留记录并标记未解析，不静默丢弃。 */
    val unresolved: Boolean = false
)

@Serializable
enum class RootKind {
    STICKY, JNI_GLOBAL, JNI_LOCAL, JAVA_FRAME, NATIVE_STACK,
    SYSTEM_CLASS, BUSY_MONITOR, THREAD_OBJECT, INTERNED_STRING, FINALIZING, UNKNOWN
}

@Serializable
data class RawRoot(
    val objectId: String,
    val kind: RootKind,
    val description: String? = null,
    val unresolved: Boolean = false
)

/**
 * 不可变材料层：解析后快照。rawPayload 是导入文本逐字副本。
 */
@Serializable
data class RawSnapshot(
    val snapshotId: String,
    val format: String,
    val parserVersion: String,
    /** 时间基准，如 unix-millis；capturedAt 与 capturedAtEpochMillis 同时保留。 */
    val timeBase: String? = null,
    val capturedAt: String,
    val capturedAtEpochMillis: Long? = null,
    /** 地址进制基准，如 0x16。 */
    val addressBase: String = "0x16",
    val sizeUnit: String = "bytes",
    val classes: List<RawClass>,
    val objects: List<RawObject>,
    val edges: List<RawEdge>,
    val roots: List<RawRoot>,
    val allocationSites: List<RawAllocationSite> = emptyList(),
    val incompleteBlocks: List<RawBlock> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList()
)

/** 弱引用是否保活由显式策略决定；internal 边按策略选择是否保活。 */
@Serializable
data class WeakPolicy(
    val keepWeakAlive: Boolean = false,
    val keepInternalAlive: Boolean = true,
    val reason: String = "default: weak references do not retain objects"
) {
    companion object {
        const val INITIAL_VERSION = "policy-weak-v1"
        const val REVISED_VERSION = "policy-weak-v2"
    }

    fun keeps(kind: EdgeKind): Boolean = when (kind) {
        EdgeKind.STRONG -> true
        EdgeKind.WEAK -> keepWeakAlive
        EdgeKind.INTERNAL -> keepInternalAlive
    }
}

@Serializable
data class ParseBudget(
    val maxObjects: Int = 100_000,
    val maxEdges: Int = 500_000,
    val maxBytes: Long = 64L * 1024 * 1024
) {
    companion object { val DEFAULT = ParseBudget() }
}

/** 导入的三种不同失败结果与成功严格区分。 */
sealed class ImportOutcome {
    data class Success(val snapshot: RawSnapshot) : ImportOutcome()
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : ImportOutcome()
    data class BudgetExhausted(val diagnostics: List<Diagnostic>) : ImportOutcome()
    data class SemanticallyIncoherent(val diagnostics: List<Diagnostic>) : ImportOutcome()
}
