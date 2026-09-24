package heapforensics

import kotlinx.serialization.Serializable

/** 边类型：强引用 / 弱引用 / 内部引用 */
enum class EdgeKind { STRONG, WEAK, INTERNAL }

/** root 分类 */
enum class RootKind { THREAD_LOCAL, JNI_GLOBAL, JNI_LOCAL, SYSTEM_CLASS, MONITOR, STACK_LOCAL, NATIVE_STATIC, OTHER }

/** 弱引用是否保活，由显式策略决定 */
enum class WeakPolicy { KEEP_ALIVE, DROP }

enum class Severity { INFO, WARNING, ERROR }

/** 带位置的诊断：输入不完整时返回诊断，绝不用空值/默认值伪装成功 */
@Serializable
data class Diagnostic(
    val code: String,
    val location: String,
    val message: String,
    val severity: Severity = Severity.WARNING
)

// ---------- 不可变原始材料层（导入时保留原始表示） ----------

@Serializable
data class RawClass(val id: String, val name: String, val superId: String? = null)

@Serializable
data class AllocationSite(val id: String, val function: String, val file: String, val line: Int)

@Serializable
data class RawObject(
    val id: String,               // 原始对象 id —— 稳定身份的组成部分
    val addressRaw: String,       // 地址保留原始表示；地址不是稳定身份
    val classId: String? = null,
    val shallowSizeRaw: String,   // 原始表示（如 "128B"、"1.5KiB"、"??"），派生层才规范化
    val siteId: String? = null
)

@Serializable
data class RawEdge(val from: String, val to: String, val kind: EdgeKind, val label: String = "")

@Serializable
data class RawRoot(val objectId: String, val rootKind: RootKind)

@Serializable
data class IncompleteChunk(val offsetRaw: String, val lengthRaw: String, val reason: String)

@Serializable
data class RawSnapshot(
    val snapshotId: String,
    val revision: Int = 1,
    val capturedAt: String,          // 时间基准，ISO-8601 原始字符串
    val parserVersion: String,       // 解析器版本必须保留
    val classes: List<RawClass> = emptyList(),
    val objects: List<RawObject> = emptyList(),
    val edges: List<RawEdge> = emptyList(),
    val roots: List<RawRoot> = emptyList(),
    val allocationSites: List<AllocationSite> = emptyList(),
    val incompleteChunks: List<IncompleteChunk> = emptyList(),
    val sourceNote: String = ""
) {
    /** 稳定身份 = 快照 id + 修订号 + 原始对象 id；地址不参与身份 */
    fun identityOf(objectId: String) = "$snapshotId@$revision/$objectId"
}

/** 三种必须区分开的失败结果 */
sealed class ForensicsFailure(message: String) : Exception(message) {
    /** 资源预算耗尽 */
    class BudgetExhausted(val budget: String, val location: String, message: String) : ForensicsFailure(message)
    /** 格式损坏 */
    class FormatCorrupt(val location: String, message: String) : ForensicsFailure(message)
    /** 语义无解 */
    class SemanticUnsolvable(val location: String, message: String) : ForensicsFailure(message)
}
