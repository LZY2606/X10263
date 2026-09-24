package heapforensics

import heapforensics.core.AnalysisParams
import heapforensics.core.AnalysisResult
import heapforensics.core.HsfParser
import heapforensics.service.ServiceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreeOutcomesTest {
    private fun simple(id: String = "s") = """HSF/1
parser p/1
captured t epoch=1
snapshot $id
class C1 name=N size=10
object O1 addr=0x1 class=C1 size=10
object O2 addr=0x2 class=C1 size=10
edge O1 -> O2 kind=STRONG
root O1 kind=OTHER
"""

    @Test
    fun `资源预算耗尽是独立结果`() {
        val r = HsfParser.parse(HsfParser.ParseInput(simple(), "s", maxObjects = 1))
        assertTrue(r is AnalysisResult.BudgetExhausted)
        assertTrue((r as AnalysisResult.BudgetExhausted).budget.contains("maxObjects"))
    }

    @Test
    fun `分析访问预算耗尽独立于格式损坏`() {
        val svc = TestSupport.newService()
        svc.importSnapshot("s", simple())
        svc.reviseParams(AnalysisParams(version = 2, maxVisits = 0), "极小预算")
        val r = svc.analyze("s", 2)
        assertTrue(r is ServiceResult.BudgetExhausted)
    }

    @Test
    fun `语义无解：无 root 时支配树无定义`() {
        val noRoots = """HSF/1
parser p/1
captured t epoch=1
snapshot s2
class C1 name=N size=10
object O9 addr=0x9 class=C1 size=10
"""
        val svc = TestSupport.newService()
        svc.importSnapshot("s2", noRoots)
        val r = svc.analyze("s2", 1)
        assertTrue(r is ServiceResult.SemanticallyUnsolvable, (r).toString())
    }

    @Test
    fun `三种失败互不相同`() {
        val types = listOf(
            HsfParser.parse(HsfParser.ParseInput("bad", "s"))::class.simpleName,
            HsfParser.parse(HsfParser.ParseInput(simple(), "s", maxObjects = 0))::class.simpleName,
        ).toSet()
        assertEquals(2, types.size)
    }
}
