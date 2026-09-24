package heapforensics

import heapforensics.casepack.CasePack
import heapforensics.service.ServiceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CycleGraphTest {
    private val svc = TestSupport.newService().also {
        it.importSnapshot("cycle", CasePack.resource("scenario-cycle.hsf")).let { r ->
            assertTrue(r is ServiceResult.Ok, "导入循环快照失败: $r")
        }
    }

    @Test
    fun `循环图可达且支配树正常构建并终止`() {
        val a = when (val r = svc.analyze("snap-scn-cycle", 1)) {
            is ServiceResult.Ok -> r.value.analysis!!
            else -> error("分析失败: $r")
        }
        assertEquals(listOf("O1", "O2", "O3", "O4"), a.reachability.reachable)
        assertEquals("O1", a.dominators.idom["O2"])
        assertEquals("O2", a.dominators.idom["O3"], "回边 O3->O2 不改变入口支配关系")
        assertEquals("O1", a.dominators.idom["O4"])
        assertEquals(400L, a.dominators.retained["O1"]!!.exactBytes)
        assertEquals(200L, a.dominators.retained["O2"]!!.exactBytes)
    }

    @Test
    fun `等价输入重复分析命中缓存且顺序稳定`() {
        val first = (svc.analyze("snap-scn-cycle", 1) as ServiceResult.Ok).value.cached
        assertEquals(false, first)
        val second = (svc.analyze("snap-scn-cycle", 1) as ServiceResult.Ok).value
        assertTrue(second.cached)
        val seq1 = second.analysis!!.reachability.reachable
        val seq2 = second.analysis!!.reachability.reachable
        assertEquals(seq1, seq2)
    }
}
