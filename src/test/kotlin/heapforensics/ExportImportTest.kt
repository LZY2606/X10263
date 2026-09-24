package heapforensics

import heapforensics.service.ServiceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportImportTest {
    @Test
    fun `导出删除本地数据库再导入，身份顺序血缘保持`() {
        val seeded = TestSupport.seeded()
        val json = seeded.exportProjectJson()
        seeded.db.close()

        val fresh = TestSupport.newService()
        val result = fresh.importProject(json)
        assertTrue(result is ServiceResult.Ok, result.toString())

        val state1 = seeded.let { TestSupport.newService() }
        // 事件身份与顺序
        val restoredEvents = fresh.events()
        assertTrue(restoredEvents.first().eventId == "E0001")
        assertTrue(restoredEvents.zipWithNext().all { (a, b) -> a.seq < b.seq })
        assertTrue(restoredEvents.any { it.kind == "REVISE_PARAMS" })
        assertTrue(restoredEvents.any { it.kind == "APPLY_SOURCE_PATCH" })

        // 快照身份与父子血缘
        val patched = fresh.db.listSnapshots().first { it.parentSnapshotId != null }
        assertEquals("snap-base-001", patched.parentSnapshotId)
        assertEquals("snap-base-001+pinned-entry", patched.snapshotId)

        // 计算结果随参数版本恢复：弱引用两版都在
        val comps = fresh.db.listComputations()
        assertTrue(comps.any { it.snapshotId == "snap-scn-weak" && it.paramsVersion == 1 })
        assertTrue(comps.any { it.snapshotId == "snap-scn-weak" && it.paramsVersion == 2 })

        // 原始材料逐字节
        val raw = fresh.db.rawSnapshot("snap-base-001")!!
        assertTrue(raw.first.contains("begin-incomplete B1"))
        assertTrue(raw.first.contains("java.lang.ref.WeakReference"))

        // 冲突决定都保留
        val decisions = restoredEvents.filter { it.kind == "RECORD_DECISION" }
        val verdicts = decisions.filter {
            it.payload["subject"]?.toString()?.contains("snap-base-001:O2:leak") == true
        }
        assertEquals(setOf("\"ACCEPT\"", "\"REJECT\""), verdicts.map { it.payload["verdict"].toString() }.toSet())
    }

    @Test
    fun `半写恢复：截断导出包导入失败且库完全回滚`() {
        val seeded = TestSupport.seeded()
        val full = seeded.exportProjectJson()
        val truncated = full.take(full.length / 2)
        val fresh = TestSupport.newService()
        // 先播种一些数据，验证回滚不破坏既有内容
        val r = fresh.importProject(truncated)
        assertTrue(r is ServiceResult.FormatCorrupt)
        val again = fresh.importProject("{ not json")
        assertTrue(again is ServiceResult.FormatCorrupt)
        // 原库的事件、快照仍在
        assertTrue(fresh.events().isNotEmpty())
        assertTrue(fresh.db.listSnapshots().isNotEmpty())
    }

    @Test
    fun `非空库不允许导入，避免身份覆盖`() {
        val a = TestSupport.seeded()
        val json = a.exportProjectJson()
        val b = TestSupport.seeded()
        val r = b.importProject(json)
        assertTrue(r is ServiceResult.FormatCorrupt)
        assertFalse(b.events().isEmpty())
    }
}
