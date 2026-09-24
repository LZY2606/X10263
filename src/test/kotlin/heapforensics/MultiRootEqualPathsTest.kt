package heapforensics

import heapforensics.casepack.CasePack
import heapforensics.service.ServiceResult
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiRootEqualPathsTest {
    @Test
    fun `多个等长 root 路径全部保留且顺序确定`() {
        val svc = TestSupport.newService()
        svc.importSnapshot("mr", CasePack.resource("scenario-multiroot.hsf"))
        val a1 = (svc.analyze("snap-scn-multiroot", 1) as ServiceResult.Ok).value.analysis!!
        val a2 = (svc.analyze("snap-scn-multiroot", 1) as ServiceResult.Ok).value.analysis!!
        assertEquals(2, a1.paths["A"]!!.paths.size)
        assertEquals(2, a1.paths["T"]!!.paths.size)
        assertEquals(1, a1.paths["A"]!!.shortestDepth)
        assertEquals(2, a1.paths["T"]!!.shortestDepth)
        val kinds = a1.paths["A"]!!.paths.map { it.rootKind.name }.sorted()
        assertEquals(listOf("JNI_GLOBAL", "THREAD"), kinds)
        assertEquals(a1.paths["T"]!!.paths.toString(), a2.paths["T"]!!.paths.toString())
    }
}
