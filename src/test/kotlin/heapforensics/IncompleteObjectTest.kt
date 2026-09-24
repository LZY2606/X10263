package heapforensics

import heapforensics.casepack.CasePack
import heapforensics.core.HsfParser
import heapforensics.core.AnalysisResult
import heapforensics.core.EdgeKind
import heapforensics.service.ServiceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncompleteObjectTest {
    @Test
    fun `不完整对象与缺失类元数据使保留大小为下界并附失效原因`() {
        val svc = TestSupport.newService()
        svc.importSnapshot("incomplete", CasePack.resource("scenario-incomplete.hsf"))
        val a = (svc.analyze("snap-scn-incomplete", 1) as ServiceResult.Ok).value.analysis!!
        val o1 = a.dominators.retained["O1"]!!
        assertNull(o1.exactBytes)
        assertEquals(368L, o1.lowerBoundBytes)
        assertTrue(o1.invalidReasons.any { it.contains("O2") && it.contains("不完整") })
        assertTrue(o1.invalidReasons.any { it.contains("C99") })
        assertTrue(a.leakCandidates.none { it.objectId == "O2" && it.retainedExact != null })
    }

    @Test
    fun `未闭合不完整块是格式损坏，不是成功`() {
        val text = "HSF/1\nparser p/1\ncaptured t epoch=1\nsnapshot x\nobject O1 addr=0x1 class=C1 size=1\nbegin-incomplete B9 reason=x\nraw junk"
        val r = HsfParser.parse(HsfParser.ParseInput(text, "x"))
        assertTrue(r is AnalysisResult.FormatCorrupt)
        assertTrue((r as AnalysisResult.FormatCorrupt).diagnostics.any { it.message.contains("未闭合") })
    }

    @Test
    fun `坏魔数返回带位置的格式损坏诊断`() {
        val r = HsfParser.parse(HsfParser.ParseInput("garbage", "x"))
        assertTrue(r is AnalysisResult.FormatCorrupt)
        assertEquals(1, (r as AnalysisResult.FormatCorrupt).diagnostics.first().line)
    }

    @Test
    fun `不可解析的行保留为诊断而不丢失`() {
        val text = """HSF/1
parser p/1
captured t epoch=1
snapshot x
class C1 name=N size=10
object O1 addr=0x1 class=C1 size=10
bogus directive here
edge O1 -> O1 kind=WEAK
root O1 kind=OTHER"""
        val r = HsfParser.parse(HsfParser.ParseInput(text, "x"))
        val snap = (r as AnalysisResult.Ok).value.snapshot
        assertEquals(1, snap.diagnostics.size)
        assertEquals(7, snap.diagnostics[0].line)
        assertEquals("bogus directive here", snap.diagnostics[0].raw)
        assertEquals(EdgeKind.WEAK, snap.edges.single().kind)
    }
}
