package hf

/** 带位置的诊断信息：行/列基于原始快照文本。 */
data class Diagnostic(
    val line: Int,
    val column: Int,
    val severity: String, // "error" | "warning"
    val message: String,
) {
    override fun toString(): String = "line $line:$column [$severity] $message"
}

/**
 * 三类失败必须可区分：格式损坏(Corrupt)、语义无解(Unsolvable)、资源预算耗尽(BudgetExhausted)。
 * 成功为 Ok。任何失败都不得用空值/默认值伪装成功。
 */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>

    /** 格式损坏：词法/语法层面无法解析。 */
    data class Corrupt(val diagnostics: List<Diagnostic>) : Outcome<Nothing>

    /** 语义无解：格式合法但引用悬空、缺少 root、参数缺失等。 */
    data class Unsolvable(val reason: String, val diagnostics: List<Diagnostic>) : Outcome<Nothing>

    /** 资源预算耗尽：节点/边/路径数超过显式预算。 */
    data class BudgetExhausted(val limit: String, val spent: Long, val budget: Long) : Outcome<Nothing>
}

fun <T, R> Outcome<T>.map(block: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(block(value))
    is Outcome.Corrupt -> this
    is Outcome.Unsolvable -> this
    is Outcome.BudgetExhausted -> this
}
