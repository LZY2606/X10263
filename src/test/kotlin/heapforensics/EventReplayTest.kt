package heapforensics

import heapforensics.service.ServiceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventReplayTest {
    @Test
    fun `回到旧计算事件恢复当时参数版本与结论`() {
        val svc = TestSupport.seeded()
        val weakV1 = svc.db.listComputations().first {
            it.snapshotId == "snap-scn-weak" && it.paramsVersion == 1
        }
        val tv = (svc.eventTimeline(weakV1.eventSeq) as ServiceResult.Ok).value
        assertEquals(1, tv.pinnedComputation!!.paramsVersion)
        assertEquals("OK", tv.pinnedComputation.status)
        val reach = tv.pinnedComputation.analysis!!.reachability.reachable
        assertEquals(listOf("O1"), reach, "历史事件不能被 v2 的最新结果覆盖")
    }

    @Test
    fun `回到参数修订事件展示版本血缘`() {
        val svc = TestSupport.seeded()
        val rev = svc.events().first { it.kind == "REVISE_PARAMS" }
        val tv = (svc.eventTimeline(rev.seq) as ServiceResult.Ok).value
        assertEquals(1, tv.at.payload["fromVersion"].toString().toInt())
        assertEquals(2, tv.at.payload["toVersion"].toString().toInt())
    }

    @Test
    fun `补丁只产生新版本快照，父快照历史结论不受影响`() {
        val svc = TestSupport.seeded()
        val base = (svc.analyze("snap-base-001", 1) as ServiceResult.Ok).value.analysis!!
        val patched = (svc.analyze("snap-base-001+pinned-entry", 2) as ServiceResult.Ok).value.analysis!!
        assertTrue(base.dominators.retained["O2"]!!.exactBytes!! in listOf(96L))
        assertEquals(128L, patched.dominators.retained["O2"]!!.exactBytes)
        // 父快照仍在且可重新读回，身份不变
        assertTrue(svc.db.snapshotExists("snap-base-001"))
        assertTrue(svc.db.snapshotExists("snap-base-001+pinned-entry"))
    }
}
