package heapsnapshot.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 边的存储/语义分类。业务规则在服务端生效，浏览器只负责展示。 */
@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL, UNKNOWN }

/** Root 分类（保留快照中的原始分类字符串）。 */
@Serializable
enum class RootKind {
    GC_ROOT, JAVA_LOCAL, JNI_GLOBAL, JNI_LOCAL, STATIC_FIELD,
    STICKY_CLASS, MONITOR, THREAD_OBJECT, INTERNED_STRING, FINALIZING, UNKNOWN
}

/** 诊断严重级别。 */
@Serializable
enum class Severity { ERROR, WARNING, INFO }

/**
 * 带单位/坐标基准的领域量。导入层只保留原始表示；规范化失败时停留在原位置并附诊断，
 * 绝不为了画图而丢值或用空值/默认值伪装成功。
 */
@Serializable
data class Quantity(
    val raw: String,
    val value: Double? = null,
    val unit: String? = null,
    val diagnostic: ParseDiagnostic? = null
) {
    val isConvertible: Boolean get() = value != null && unit != null
    companion object {
        fun converted(raw: String, value: Double, unit: String) = Quantity(raw, value, unit)
        fun unconvertible(raw: String, reason: String, location: String) =
            Quantity(raw, null, null, ParseDiagnostic(Severity.ERROR, "UNCONVERTIBLE_QUANTITY", reason, location, raw))
    }
}

/** 地址：原始字符串永远保留；规范化后的数值仅用于排序/展示，不能作为稳定身份。 */
@Serializable
data class AddressValue(
    val raw: String,
    val numeric: Long? = null,
    val base: String = "hex",
    val diagnostic: ParseDiagnostic? = null
)

@Serializable
data class ParseDiagnostic(
    val severity: Severity,
    val code: String,
    val message: String,
    val location: String,
    val rawValue: String? = null
)

/** 快照头：解析器版本与快照身份是一等公民。 */
@Serializable
data class SnapshotHeader(
    val snapshotId: String,
    val parserVersion: String,
    val formatVersion: String,
    val takenAtRaw: String,
    val takenAtEpochMs: Long?,
    val timeBasis: String,
    val addressUnit: String,
    val sizeUnitRaw: String,
    val sizeUnitCanonical: String,
    val raw: JsonObject
)

@Serializable
data class ClassMeta(
    val classId: String,
    val name: String,
    val superClassId: String?,
    /** 类改名对应：本类在上一快照中的 classId（类群差异的依据）。 */
    val renamedFromSnapshotId: String? = null,
    val renamedFromClassId: String? = null,
    val raw: JsonObject
)

@Serializable
data class HeapObject(
    val objectId: String,
    val address: AddressValue,
    val classId: String,
    val shallowSize: Quantity,
    val allocationSiteId: String?,
    val incomplete: Boolean = false,
    val raw: JsonObject
)

@Serializable
data class Edge(
    val edgeId: String,
    val fromObjectId: String,
    val toObjectId: String,
    val kind: EdgeKind,
    val label: String?,
    val raw: JsonObject
)

@Serializable
data class RootRecord(
    val rootId: String,
    val objectId: String,
    val kind: RootKind,
    val kindRaw: String,
    val label: String?,
    val raw: JsonObject
)

/** Allocation site：位置携带坐标基准（文件:行:列 或帧基址+偏移）。 */
@Serializable
data class AllocationSite(
    val siteId: String,
    val locationRaw: String,
    val frameBasis: String?,
    val line: Int?,
    val column: Int?,
    val raw: JsonObject
)

/** 不完整块：字节/对象范围与原因必须保留。 */
@Serializable
data class IncompleteBlock(
    val blockId: String,
    val offsetStart: Quantity,
    val offsetEnd: Quantity,
    val reason: String,
    val raw: JsonObject
)

/** 一次导入得到的不可变材料。任何层都不就地修改它。 */
@Serializable
data class RawSnapshot(
    val header: SnapshotHeader,
    val classes: List<ClassMeta>,
    val objects: List<HeapObject>,
    val edges: List<Edge>,
    val roots: List<RootRecord>,
    val allocationSites: List<AllocationSite>,
    val incompleteBlocks: List<IncompleteBlock>,
    val diagnostics: List<ParseDiagnostic>
) {
    val snapshotId: String get() = header.snapshotId
    fun objectById(id: String): HeapObject? = objectsByKey[id]
    fun classById(id: String): ClassMeta? = classesByKey[id]
    private val objectsByKey = objects.associateBy { it.objectId }
    private val classesByKey = classes.associateBy { it.classId }
}

/** 跨层使用的稳定身份：快照身份 + 局部 id。地址永不作为身份。 */
@Serializable
data class SnapshotRef(val snapshotId: String, val localId: String) {
    override fun toString(): String = "$snapshotId/$localId"
    companion object {
        fun obj(snapshotId: String, objectId: String) = SnapshotRef(snapshotId, "obj:$objectId")
        fun edge(snapshotId: String, edgeId: String) = SnapshotRef(snapshotId, "edge:$edgeId")
        const val VIRTUAL_ROOT_LOCAL = "virtual:root"
        fun virtualRoot(snapshotId: String) = SnapshotRef(snapshotId, VIRTUAL_ROOT_LOCAL)
    }
}

/** 三类互不混淆的失败结果。 */
@Serializable
sealed class FailureOutcome {
    /** 格式损坏：文件结构本身无法解析。 */
    @Serializable
    data class FormatCorrupt(val diagnostics: List<ParseDiagnostic>) : FailureOutcome()
    /** 资源预算耗尽：结构合法但超出策略预算。 */
    @Serializable
    data class BudgetExhausted(val budget: String, val limit: Long, val observed: Long, val location: String) : FailureOutcome()
    /** 语义无解：结构合法但业务语义不允许唯一决定。 */
    @Serializable
    data class SemanticallyUnresolved(val diagnostics: List<ParseDiagnostic>) : FailureOutcome()
}
