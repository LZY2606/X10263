package hsf.model

import kotlinx.serialization.Serializable

/**
 * 不可变材料层：解析器吐出的原始记录。所有原始表示（对象 id、地址文本、
 * 边种类、root 分类、不完整块、解析器版本）都在这里原样保留，规范化只在
 * 派生分析层进行。
 */

const val FORMAT_NAME = "HPSF"
const val FORMAT_VERSION = 1
const val PARSER_VERSION = "hpsf/1.0.0"

/** 解析坐标：文件名 + 1 起始行/列，所有诊断必须可定位。 */
@Serializable
data class Pos(val line: Int, val col: Int, val source: String = "") : Comparable<Pos> {
    override fun compareTo(other: Pos): Int {
        val s = source.compareTo(other.source)
        if (s != 0) return s
        val l = line.compareTo(other.line)
        if (l != 0) return l
        return col.compareTo(other.col)
    }
    override fun toString(): String = (if (source.isNotEmpty()) "$source:" else "") + "${line}:${col}"
}

@Serializable
data class Quantity(
    val raw: String,
    val value: Double?,
    val unit: String?,
    val diagnosticId: String? = null
) : Comparable<Quantity> {
    override fun compareTo(other: Quantity): Int =
        compareValuesBy(this, other, { it.value ?: 0.0 }, Quantity::raw, Quantity::unit)
}

@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL }

@Serializable
enum class RootKind {
    JNI_LOCAL, JNI_GLOBAL, SYSTEM_CLASS, STICKY_CLASS,
    MONITOR_USED, THREAD_OBJ, NATIVE_STACK, VM_INTERNAL,
    UNKNOWN;

    companion object {
        fun fromRaw(token: String): RootKind =
            entries.firstOrNull { it.name.equals(token, ignoreCase = true) } ?: UNKNOWN
    }
}

@Serializable
data class RawClass(
    val classId: String,
    val nameToken: String,
    val name: String,
    val fields: List<String>,
    val declaredAt: Pos,
    val rawLine: String
)

@Serializable
data class RawObject(
    val objectId: String,
    val addressRaw: String,
    val address: Long?,
    val classId: String,
    val shallowBytesRaw: String,
    val shallowBytes: Long?,
    val allocationSiteId: String?,
    val incomplete: Boolean,
    val declaredAt: Pos,
    val rawLine: String,
    val classRefDiagnostic: Boolean = false
)

@Serializable
data class RawEdge(
    val edgeId: String,
    val kind: EdgeKind,
    val fromObjectId: String,
    val toObjectId: String,
    val label: String,
    val declaredAt: Pos,
    val rawLine: String,
    val missingTarget: Boolean = false,
    val missingSource: Boolean = false
)

@Serializable
data class RawRoot(
    val objectId: String,
    val kind: RootKind,
    val kindToken: String,
    val context: String,
    val declaredAt: Pos,
    val rawLine: String,
    val missingTarget: Boolean = false
)

@Serializable
data class RawAllocationSite(
    val siteId: String,
    val location: String,
    val frame: String,
    val declaredAt: Pos,
    val rawLine: String
)

@Serializable
data class RawIncompleteBlock(
    val blockId: String,
    val beginsAt: Pos,
    val endsAt: Pos?,
    val rawHeader: String,
    val reason: String
)

@Serializable
data class RawSnapshot(
    val snapshotId: String,
    val label: String,
    val formatName: String,
    val formatVersion: Int,
    val parserVersion: String,
    val capturedAtIso: String?,
    val capturedEpochMs: Long?,
    val timeBasis: String,
    val addressUnit: String,
    val sizeUnit: String,
    val classes: List<RawClass>,
    val objects: List<RawObject>,
    val edges: List<RawEdge>,
    val roots: List<RawRoot>,
    val sites: List<RawAllocationSite>,
    val incompleteBlocks: List<RawIncompleteBlock>,
    val warnings: List<Diagnostic>,
    val sourceName: String,
    val rawDirectives: List<String>
) {
    fun classOf(classId: String): RawClass? = classes.firstOrNull { it.classId == classId }
    fun siteOf(siteId: String?): RawAllocationSite? =
        siteId?.let { id -> sites.firstOrNull { it.siteId == id } }
}

/** 三种必须严格区分的失败结果 + 普通诊断。绝不以空值/默认值伪装成功。 */
@Serializable
sealed class Diagnostic {
    abstract val id: String
    abstract val pos: Pos?
    abstract val message: String
    @Serializable data class ParseError(override val id: String, override val pos: Pos?, override val message: String, val rawLine: String? = null) : Diagnostic()
    @Serializable data class Semantic(override val id: String, override val pos: Pos?, override val message: String, val relatedIds: List<String> = emptyList()) : Diagnostic()
    @Serializable data class UnconvertibleValue(override val id: String, override val pos: Pos?, override val message: String, val raw: String, val unit: String?) : Diagnostic()
    @Serializable data class IncompleteData(override val id: String, override val pos: Pos?, override val message: String, val blockId: String) : Diagnostic()
}

@Serializable
data class ImportInput(
    val name: String,
    val text: String,
    val maxObjects: Int = 200_000,
    val maxEdges: Int = 400_000,
    val maxBytes: Int = 64 * 1024 * 1024
)

@Serializable
sealed class ImportOutcome {
    @Serializable data class Ok(val snapshot: RawSnapshot) : ImportOutcome()
    @Serializable data class Corrupted(val diagnostics: List<Diagnostic>, val parserVersion: String = PARSER_VERSION) : ImportOutcome()
    @Serializable data class SemanticUnresolved(val diagnostics: List<Diagnostic>, val snapshot: RawSnapshot? = null) : ImportOutcome()
    @Serializable data class BudgetExceeded(val diagnostics: List<Diagnostic>, val consumedObjects: Int, val consumedEdges: Int) : ImportOutcome()
}
