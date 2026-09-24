package heapforensics

import heapforensics.casepack.CasePack
import heapforensics.service.ReviewService
import heapforensics.service.ServiceResult
import heapforensics.store.Database

object TestSupport {
    fun newService(): ReviewService = ReviewService(Database.memory())

    fun seeded(): ReviewService {
        val s = newService()
        CasePack.seed(s)
        return s
    }

    fun <T> ServiceResult<T>.requireOk(): T = when (this) {
        is ServiceResult.Ok -> value
        else -> error("期望成功，实际: $this")
    }
}
