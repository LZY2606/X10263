package heapforensics

import heapforensics.casepack.CasePack
import heapforensics.core.ClassDiff
import heapforensics.service.ServiceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassRenameTest {
    @Test
    fun `类改名按 classId 对应，地址变化不破坏对象身份`() {
        val svc = TestSupport.newService()
        svc.importSnapshot("a", CasePack.resource("scenario-classrename-a.hsf"))
        svc.importSnapshot("b", CasePack.resource("scenario-classrename-b.hsf"))
        val view = (svc.diff("snap-scn-rename-a", "snap-scn-rename-b") as ServiceResult.Ok).value
        val c7 = view.diff.groups.first { it.classId == "C7" }
        assertEquals(ClassDiff.Status.RENAMED, c7.status)
        assertEquals("com.acme.util.Helper", c7.nameA)
        assertEquals("com.acme.util.Helper2", c7.nameB)
        assertTrue(view.diff.identityNotes.any { it.note.contains("地址不是稳定身份") })
        assertTrue(view.diff.identityNotes.any { it.examples.any { x -> x.startsWith("H1:") } })
    }
}
