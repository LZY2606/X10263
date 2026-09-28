package forensics

import forensics.model.ImportOutcome
import forensics.parse.ParseBudget
import forensics.parse.SnapshotParser
import java.io.File
import kotlin.test.*

class ParserTest {

    private fun res(name: String) =
        File("src/test/resources/cases/$name").readText()

    @Test
    fun `baseline retains raw object id, address basis, units and parser version`() {
        val out = SnapshotParser().parse("baseline.json", res("baseline.json"))
        assertIs<ImportOutcome.Ok>(out)
        val s = out.snapshot
        assertEquals("heap-shadow-parser/1.4.0", s.parserVersion)
        assertEquals("JVM heap virtual address, hex, 0-based", s.addressBasis)
        assertEquals("0x6000", s.objectById["obj-6"]!!.addressRaw)
        assertEquals(0x6000L, s.objectById["obj-6"]!!.address)
        // 2 KiB 被规范化为 byte，原始表示仍保留
        val size = s.objectById["obj-2"]!!.shallowSize!!
        assertEquals("2 KiB", size.raw)
        assertEquals(2048.0, size.value)
        // weak/internal edge kinds preserved
        assertEquals(setOf("WEAK", "INTERNAL"),
            s.edges.filter { it.edgeId in listOf("e-6", "e-3") }.map { it.kind.name }.toSet())
        assertEquals("NATIVE_STACK", s.roots.first().kind.name)
    }

    @Test
    fun `unconvertible measure stays in place with diagnostic instead of fake default`() {
        val out = SnapshotParser().parse("cx-incomplete-object.json", res("cx-incomplete-object.json"))
        assertIs<ImportOutcome.Ok>(out)
        val bad = out.snapshot.objectById["i-bad"]!!.shallowSize!!
        assertNull(bad.value)
        assertEquals("?? many", bad.raw)
        assertTrue(out.warnings.any { it.position.contains("i-bad.shallowSize") })
    }

    @Test
    fun `format corrupt, budget exhausted and semantic unresolved are three distinct outcomes`() {
        val broken = SnapshotParser().parse("broken.json", res("fail-format-corrupt.json"))
        assertIs<ImportOutcome.FormatCorrupt>(broken)
        assertTrue(broken.diagnostics.first().position.isNotBlank())

        val huge = SnapshotParser(ParseBudget(maxObjects = 1)).parse("h.json", res("baseline.json"))
        assertIs<ImportOutcome.BudgetExhausted>(huge)

        val semantic = SnapshotParser().parse("s.json", res("fail-semantic.json"))
        assertIs<ImportOutcome.SemanticUnresolved>(semantic)
        assertTrue(semantic.diagnostics.any { it.code == "S002" }) // duplicate objectId
        assertTrue(semantic.diagnostics.any { it.code == "S007" }) // dangling edge target
    }

    @Test
    fun `duplicate address is detected but never merges stable identities`() {
        val out = SnapshotParser().parse("d.json", res("cx-duplicate-address.json"))
        assertIs<ImportOutcome.Ok>(out)
        assertEquals(1, out.snapshot.addressDuplicates.size)
        assertEquals(listOf("x-1", "x-2"), out.snapshot.addressDuplicates.single().objectIds)
    }
}
