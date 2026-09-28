package heaplab.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 领域模型分两层：
 *  - Raw*  : 不可变导入材料。原始对象 id / 地址 / 类元数据 / 边 / root 分类 /
 *            不完整块 / 解析器版本全部原样保留，导入时不做数值规范化。
 *  - 派生层: 可达性、支配树、保留大小、root 路径、类群差异，均带输入摘要、
 *            参数版本与父级依赖，可随事件重放。
 */

// ---------------------------------------------------------------------------
// 原始快照格式（HSF, Heap Snapshot Forensics）。rawText 原样保留。
// ---------------------------------------------------------------------------

@Serializable
enum class EdgeKind { @SerialName("strong") STRONG, @SerialName("weak") WEAK, @SerialName("internal") INTERNAL }

@Serializable
enum class RootKind {
    @SerialName("jni-global") JNI_GLOBAL,
    @SerialName("jni-local") JNI_LOCAL,
    @SerialName("system-class") SYSTEM_CLASS,
    @SerialName("stack-local") STACK_LOCAL,
    @SerialName("monitor") MONITOR,
    @SerialName("native-stack") NATIVE_STACK,
    @SerialName("alloc-site") ALLOC_SITE,
    @SerialName("unknown") UNKNOWN
}

@Serializable
data class RawClassMeta(
    val classId: String,
    val name: String,
    val rawName: String,
    val classLoaderId: String? = null,
    val superClassId: String? = null,
    val renamedFrom: String? = null,
    val unknown: JsonObject? = null
)

@Serializable
data class RawObject(
    val objectId: String,
    val address: String,                 // 原样字符串，例如 "0x1000" 或 "1000"，不以地址当稳定身份
    val classId: String,
    val shallowSize: JsonElement,        // 可能是数字 / 带单位字符串 / 无法转换 -> 留在原位
    val sizeUnit: String = "B",
    val stableId: String? = null,
    val allocationSite: String? = null,
    val incomplete: Boolean = false,
    val incompleteReason: String? = null,
    val unknown: JsonObject? = null
)

@Serializable
data class RawEdge(
    val edgeId: String,
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val field: String? = null,
    val weakReferenceName: String? = null,
    val unknown: JsonObject? = null
)

@Serializable
data class RawRoot(
    val rootId: String,
    val objectId: String,
    val kind: RootKind,
    val label: String? = null
)

@Serializable
data class RawIncompleteChunk(
    val chunkId: String,
    val offset: String? = null,
    val length: JsonElement? = null,
    val reason: String,
    val rawText: String
)

@Serializable
data class RawAllocationSite(
    val siteId: String,
    val className: String,
    val methodName: String,
    val location: String,
    val line: String? = null,            // 原始表示
    val unknown: JsonObject? = null
)

@Serializable
data class RawSnapshot(
    val snapshotId: String,
    val label: String,
    val takenAt: String,                 // 时间基准原样保留
    val timeBasis: String = "wall-clock",
    val parserVersion: String,
    val formatVersion: String,
    val addressBasis: String = "heap-virtual",
    val classes: List<RawClassMeta>,
    val objects: List<RawObject>,
    val edges: List<RawEdge>,
    val roots: List<RawRoot>,
    val allocationSites: List<RawAllocationSite> = emptyList(),
    val incompleteChunks: List<RawIncompleteChunk> = emptyList(),
    val unknown: JsonObject? = null
)

/** 跨快照身份对应与类改名映射，属于不可变输入材料。 */
@Serializable
data class IdentityCorrespondence(
    val stableIdBySnapshotObject: Map<String, Map<String, String>> = emptyMap(),
    val classAliases: Map<String, List<String>> = emptyMap()
)

@Serializable
data class RawBundle(
    val bundleId: String,
    val parserVersion: String,
    val snapshots: List<RawSnapshot>,
    val identity: IdentityCorrespondence = IdentityCorrespondence()
)

// ---------------------------------------------------------------------------
// 诊断与三种必须可区分的结果
// ---------------------------------------------------------------------------

@Serializable
enum class Severity { INFO, WARNING, ERROR }

@Serializable
data class Diagnostic(
    val code: String,
    val severity: Severity,
    val message: String,
    val location: String,               // 带位置，例如 snapshot[1].objects[3].shallowSize
    val rawValue: JsonElement? = null
)

/** 资源预算耗尽 / 格式损坏 / 语义无解 —— 三种不同结果。 */
sealed class LoadResult {
    data class Ok(val bundle: RawBundle, val diagnostics: List<Diagnostic>) : LoadResult()
    data class Corrupt(val diagnostics: List<Diagnostic>) : LoadResult() {
        companion object { const val KIND = "format-corrupt" }
    }
    data class SemanticUnresolved(val diagnostics: List<Diagnostic>, val candidates: List<String>) : LoadResult() {
        companion object { const val KIND = "semantic-unresolved" }
    }
    data class BudgetExhausted(val diagnostics: List<Diagnostic>, val used: Long, val budget: Long) : LoadResult() {
        companion object { const val KIND = "budget-exhausted" }
    }
}

// ---------------------------------------------------------------------------
// 参数层（显式策略；每次修订是一个新版本）
// ---------------------------------------------------------------------------

@Serializable
enum class WeakPolicy { @SerialName("keep-alive") KEEP_ALIVE, @SerialName("clear") CLEAR, @SerialName("mark-only") MARK_ONLY }

@Serializable
data class AnalysisParams(
    val version: Int,
    val weakPolicy: WeakPolicy = WeakPolicy.CLEAR,
    val treatInternalAsStrong: Boolean = true,
    val maxNodes: Int = 200_000,
    val maxEdges: Int = 1_000_000
)
