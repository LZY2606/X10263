package heapforensics.service

import heapforensics.core.Diagnostic
import kotlinx.serialization.Serializable

/** 服务层结果：三类失败与成功严格分离，直接可被测试调用。 */
@Serializable
sealed interface ServiceResult<out T> {
    @Serializable
    data class Ok<T>(val value: T) : ServiceResult<T>

    @Serializable
    data class BudgetExhausted(val budget: String, val detail: String) : ServiceResult<Nothing>

    @Serializable
    data class FormatCorrupt(val diagnostics: List<Diagnostic>) : ServiceResult<Nothing>

    @Serializable
    data class SemanticallyUnsolvable(val reasons: List<String>) : ServiceResult<Nothing>
}
