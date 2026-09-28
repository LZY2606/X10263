package forensics.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 领域模型分两层：
 *  - 原始材料层（Raw* / Snapshot）：导入即原样保留，不可变。
 *  - 派生层（*View / AnalysisResult / Diff）：由材料 + 参数版本计算，可重放。
 * 所有领域量携带单位或坐标基准；无法规范化的值留在原位置并附诊断。
 */

/** 带原始表示、单位、坐标基准的量。value 仅在可转换时存在。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Measure(
    val raw: String,
    val unit: String?,
    val basis: String?,
    val value: Double? = null,
    val convertDiagnostic: String? = null,
)

enum class EdgeKind { STRONG, WEAK, INTERNAL, UNKNOWN }

enum class RootKind {
    STICKY, JNI_LOCAL, JNI_GLOBAL, NATIVE_STACK, SYSTEM_CLASS, VM_INTERNAL,
    THREAD_BLOCK, REFERENCE_CLEANUP, BUSY_MONITOR, NATIVE_STATIC,
    INTERNED_STRING, FINALIZING, DEBUGGER, UNREFERENCED, UNKNOWN;

    companion object {
        fun parse(raw: String?): RootKind =
            entries.firstOrNull { it.name.equals(raw?.replace('-', '_')?.uppercase(), true) } ?: UNKNOWN
    }
}

/** 类元数据（原始层）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RawClass(
    val classId: String,
    val name: String,
    val classLoaderId: String? = null,
    val superClassId: String? = null,
    val allocationSiteId: String? = null,
    val instanceSize: Measure? = null,
)

/** Allocation site：地址/坐标基准 + 原始坐标。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AllocationSite(
    val siteId: String,
    val rawLocation: String,
    val coordinateBasis: String? = null,
    val coordinate: String? = null,
    val method: String? = null,
)

/** 对象（原始层）。地址不是稳定身份，objectId 才是。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RawObject(
    val objectId: String,
    val addressRaw: String?,
    val address: Long? = null,
    val addressBasis: String? = null,
    val classId: String?,
    val shallowSize: Measure? = null,
    val allocationSiteId: String? = null,
    val incomplete: Boolean = false,
    val incompleteReason: String? = null,
    val raw: Map<String, Any?> = emptyMap(),
)

/** 边（原始层）：强/弱/内部。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RawEdge(
    val edgeId: String,
    val fromId: String,
    val toId: String,
    val kind: EdgeKind,
    val field: String? = null,
    val order: Int = 0,
    val raw: Map<String, Any?> = emptyMap(),
)

/** root 分类（原始层）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RawRoot(
    val rootId: String,
    val objectId: String,
    val kind: RootKind,
    val origin: String? = null,
    val order: Int = 0,
)

/** 不完整块：字节/记录偏移与原文片段。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IncompleteChunk(
    val chunkId: String,
    val position: String,
    val byteOffset: Long? = null,
    val reason: String,
    val rawSnippet: String,
)

/** 原始材料诊断：始终携带位置。severity=ERROR 时该快照不可成功声明。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Diagnostic(
    val code: String,
    val severity: String,
    val position: String,
    val message: String,
)

/** 堆快照：不可变原始材料。addressDuplicates 记录"地址不当身份"的直接证据。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Snapshot(
    val snapshotId: String,
    val sourceName: String,
    val parserVersion: String,
    val formatVersion: String?,
    val capturedAtBasis: String?,
    val capturedAtRaw: String?,
    val addressBasis: String?,
    val sizeUnitBasis: String?,
    val objects: List<RawObject>,
    val classes: List<RawClass>,
    val edges: List<RawEdge>,
    val roots: List<RawRoot>,
    val allocationSites: List<AllocationSite>,
    val incompleteChunks: List<IncompleteChunk>,
    val diagnostics: List<Diagnostic>,
    val addressDuplicates: List<AddressDuplicate> = emptyList(),
    val rawEnvelope: Map<String, Any?> = emptyMap(),
) {
    val objectById: Map<String, RawObject> by lazy { objects.associateBy { it.objectId } }
    val classById: Map<String, RawClass> by lazy { classes.associateBy { it.classId } }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AddressDuplicate(val address: Long, val addressRaw: String?, val objectIds: List<String>)

/** 导入报告：三类不同结果（格式损坏/预算耗尽/语义无解）与成功 + 诊断并存。 */
sealed interface ImportOutcome {
    data class Ok(val snapshot: Snapshot, val warnings: List<Diagnostic>) : ImportOutcome
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : ImportOutcome
    data class BudgetExhausted(val diagnostics: List<Diagnostic>) : ImportOutcome
    data class SemanticUnresolved(val diagnostics: List<Diagnostic>) : ImportOutcome
}
