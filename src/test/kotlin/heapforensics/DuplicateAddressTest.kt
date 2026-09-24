package heapforensics

import heapforensics.casepack.CasePack
import heapforensics.service.ServiceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DuplicateAddressTest {
    @Test
    fun `重复地址不合并身份，两个对象都保留`() {
        val svc = TestSupport.newService()
        svc.importSnapshot("dup", CasePack.resource("scenario-duplicate-address.hsf"))
        val a = (svc.analyze("snap-scn-dupaddr", 1) as ServiceResult.Ok).value.analysis!!
        assertEquals(1, a.addressCollisions.size)
        assertEquals("0x7f230010", a.addressCollisions[0].address)
        assertEquals(listOf("O1", "O2"), a.addressCollisions[0].objectIds)
        assertEquals(160L, a.dominators.retained["O1"]!!.exactBytes)
    }
}
