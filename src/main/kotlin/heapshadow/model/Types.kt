package heapshadow.model

import kotlinx.serialization.Serializable

/**
 * 带单位/坐标基准的量。raw 是导入时的原始表示，规范化只在派生层发生；
 * 无法转换时 normalizedBytes / normalizedOffset 为 null，并由诊断说明原因，
 * 原值仍留在原位，绝不为画图而丢弃。
 */
@Serializable
data class Quantity(
    val raw: String,
    val unit: String?,
    val normalizedBytes: Long? = null,
    val normalizedOffset: Long? = null,
    val convertible: Boolean = true,
    val conversionDiagnostic: String? = null,
) {
    /** 派生层可用字节值；不可转换时调用方必须另行处理诊断，这里不伪装成 0。 */
    fun bytesOrThrow(): Long = normalizedBytes
        ?: throw UnconvertibleQuantityException(raw, conversionDiagnostic ?: "unconvertible quantity")
}

class UnconvertibleQuantityException(val rawValue: String, val reason: String) :
    IllegalStateException("quantity not convertible to bytes: '$rawValue' ($reason)")

/** 边分类：强 / 弱 / 内部。unknown 表示源里出现了无法识别的分类，附诊断而非丢弃。 */
@Serializable
enum class EdgeKind { STRONG, WEAK, INTERNAL, UNKNOWN }

/** root 分类（运行时常见根类型）。UNKNOWN 保留源值。 */
@Serializable
enum class RootType {
    JNI_LOCAL, JNI_GLOBAL, STATIC_FIELD, STACK_LOCAL,
    SYSTEM_CLASS, NATIVE_STACK, THREAD, MONITOR, BUSY_MONITOR,
    INTERNED_STRING, FINALIZING, DEBUGGER, VM_INTERNAL, OTHER, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): RootType =
            entries.firstOrNull { it.name.equals(raw?.replace('-', '_')?.replace(' ', '_'), true) }
                ?: UNKNOWN
    }
}

fun edgeKindFromRaw(raw: String?): EdgeKind =
    EdgeKind.entries.firstOrNull { it.name.equals(raw?.replace('-', '_')?.replace(' ', '_'), true) }
        ?: EdgeKind.UNKNOWN

/**
 * 带位置的诊断。severity 区分错误与降级；code 让 UI/测试机器可读。
 * position 是 JSON Pointer，blocked 表示该条记录是否被阻止进入派生层。
 */
@Serializable
data class Diagnostic(
    val code: String,
    val severity: Severity,
    val message: String,
    val position: String? = null,
    val recordId: String? = null,
)

@Serializable
enum class Severity { ERROR, WARNING, INFO }

/** 三种不同的顶层结果：格式损坏 / 语义无解 / 资源预算耗尽。 */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : Outcome<Nothing>()
    data class SemanticUnresolvable(val diagnostics: List<Diagnostic>) : Outcome<Nothing>()
    data class BudgetExhausted(val diagnostics: List<Diagnostic>) : Outcome<Nothing>()
}
