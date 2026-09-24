package heapforensics

import heapforensics.casepack.CasePack
import heapforensics.core.AnalysisParams
import heapforensics.service.ServiceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeakRefPolicyTest {
    private val svc = TestSupport.newService()

    init {
        svc.importSnapshot("weak", CasePack.resource("scenario-weakref.hsf"))
        svc.analyze("snap-scn-weak", 1)
        svc.reviseParams(
            AnalysisParams(version = 2, weakKeepAlive = true, internalKeepAlive = true),
            "实验：弱引用保活",
        )
        svc.analyze("snap-scn-weak", 2)
    }

    @Test
    fun `v1 默认弱引用不保活，O2 不可达`() {
        val a = (svc.analyze("snap-scn-weak", 1) as ServiceResult.Ok).value.analysis!!
        assertEquals(listOf("O1"), a.reachability.reachable)
        assertEquals(listOf("O2"), a.reachability.unreachable)
        assertEquals(1, a.reachability.droppedEdges.size)
        assertTrue(a.dominators.idom["O2"] == null)
    }

    @Test
    fun `v2 显式保活策略下 O2 可达，两版结论并存`() {
        val a = (svc.analyze("snap-scn-weak", 2) as ServiceResult.Ok).value.analysis!!
        assertEquals(listOf("O1", "O2"), a.reachability.reachable)
        assertEquals(1040L, a.dominators.retained["O1"]!!.exactBytes)
        val comps = svc.db.listComputations()
        assertEquals(setOf(1, 2), comps.filter { it.snapshotId == "snap-scn-weak" }.map { it.paramsVersion }.toSet())
    }
}
