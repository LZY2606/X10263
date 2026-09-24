package heapproom.parser

import heapproom.domain.Diagnostic

/**
 * 导入只可能落到三类互斥结果之一，绝不混用：
 *  - FORMAT_CORRUPTED        语法层面无法解析
 *  - SEMANTIC_UNRESOLVABLE   语法有效但领域约束无解（重复 id、悬空引用...）
 *  - RESOURCE_BUDGET_EXHAUSTED 超过调用方声明的对象/边预算
 *  - ACCEPTED                成功（可附带 warning/info 诊断）
 */
sealed class ImportOutcome {
    abstract val diagnostics: List<Diagnostic>

    data class FormatCorrupted(
        override val diagnostics: List<Diagnostic>,
        val consumedFragmentOrdinal: Int,
    ) : ImportOutcome()

    data class SemanticUnresolvable(
        override val diagnostics: List<Diagnostic>,
    ) : ImportOutcome()

    data class ResourceBudgetExhausted(
        override val diagnostics: List<Diagnostic>,
        val budgetName: String,
        val observed: Int,
        val limit: Int,
    ) : ImportOutcome()

    data class Accepted(
        val parsed: heapproom.domain.Snapshot,
        override val diagnostics: List<Diagnostic>,
    ) : ImportOutcome()
}
