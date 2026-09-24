package heapforensics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContains

class AnalysisTest {
    private val now = "2026-09-19T00:00:00Z"

    private fun analyze(p: AnalysisParams) = Analysis.analyze(CasePack.snapshotA, p, now)

    @Test fun `cycle detected and dominator tree built on reachable graph only`() {
        val r = analyze(AnalysisParams(1, weakKeepAlive = false))
        val cyc = r.events.first { it.type == "CYCLE_DETECTED" }
        assertEquals(setOf("obj-ring-a", "obj-ring-b"), cyc.affected.toSet())
        // ring reachable via app; every reachable node has a dominator parent
        assertTrue("obj-ring-a" in r.idom)
        assertTrue("obj-ring-b" in r.idom)
    }

    @Test fun `weak references kept or collected only by explicit policy`() {
        val collected = analyze(AnalysisParams(1, weakKeepAlive = false))
        assertFalse("obj-entry" in collected.reachable)
        assertTrue(collected.events.any { it.type == "WEAK_NOT_KEPT" && it.attributes["policy"] == "COLLECT" })

        val kept = analyze(AnalysisParams(2, weakKeepAlive = true))
        assertTrue("obj-entry" in kept.reachable)
        assertTrue(kept.events.any { it.type == "WEAK_KEPT_ALIVE" && it.attributes["policy"] == "KEEP_ALIVE" })
        assertTrue((kept.retainedBytes["obj-cache"] ?: 0) >= 24 + 48)
    }

    @Test fun `duplicate address does not merge identity`() {
        val r = analyze(AnalysisParams(1, weakKeepAlive = false))
        val e = r.events.first { it.type == "DUPLICATE_ADDRESS" }
        assertEquals("0x10A0", e.attributes["address"])
        assertEquals(setOf("obj-buf", "obj-buf-shadow"), e.affected.toSet())
    }

    @Test fun `incomplete object is retained with positional diagnostic`() {
        val import = Importer.parse(CasePack.snapshotJson(CasePack.snapshotA)) as ImportResult.Imported
        assertTrue(import.diagnostics.any { it.code == "INCOMPLETE_OBJECT" && it.position == "objects/obj-partial" })
        assertTrue(import.diagnostics.any { it.code == "INCOMPLETE_CHUNK" })
        val r = analyze(AnalysisParams(1, weakKeepAlive = false))
        assertTrue(r.events.any { it.type == "INCOMPLETE_OBJECT" && it.affected == listOf("obj-partial") })
    }

    @Test fun `multiple equal-length root paths are all retained`() {
        val r = analyze(AnalysisParams(1, weakKeepAlive = false))
        val paths = r.rootPaths["obj-multi"]!!
        assertEquals(2, paths.size)
        assertEquals(paths[0].size, paths[1].size) // equal length
        assertTrue(paths.any { it.first() == "obj-hop-x" })
        assertTrue(paths.any { it.first() == "obj-hop-y" })
        assertTrue(r.events.any { it.type == "MULTI_EQUAL_ROOT_PATHS" })
    }

    @Test fun `class rename corresponds by stable classId not name`() {
        val diff = Analysis.classGroupDiff(CasePack.snapshotA, CasePack.snapshotB)
        val entry = diff.first { it.classId == "cls-entry" }
        assertTrue(entry.renamed)
        assertEquals("com.example.CacheEntry", entry.nameA)
        assertEquals("com.example.CacheSlot", entry.nameB)
        assertEquals(1L, entry.countA); assertEquals(3L, entry.countB)
    }

    @Test fun `equivalent input yields stable event order`() {
        val a = analyze(AnalysisParams(1, weakKeepAlive = false)).events.map { it.eventId }
        val b = analyze(AnalysisParams(1, weakKeepAlive = false)).events.map { it.eventId }
        assertEquals(a, b)
    }

    @Test fun `three distinct failure outcomes`() {
        // format corrupt: invalid JSON, position must be reported
        val bad = Importer.parse("{ this is not json")
        assertTrue(bad is ImportResult.FormatCorrupt)
        assertTrue((bad as ImportResult.FormatCorrupt).position.isNotBlank())

        // budget exhausted: distinct result type, not an exception or silent default
        val tight = Analysis.analyze(CasePack.snapshotA, AnalysisParams(1, false, nodeBudget = 3), now)
        assertTrue(tight.status is AnalysisStatus.BudgetExhausted)
        val importTight = Importer.parse(CasePack.snapshotJson(CasePack.snapshotA), objectBudget = 2)
        assertTrue(importTight is ImportResult.BudgetExhausted)

        // semantic unsolvable: unknown class cannot be normalized
        val broken = CasePack.snapshotA.copy(
            objects = CasePack.snapshotA.objects + HeapObject("obj-x", "0x1", "cls-missing", 8))
        val sem = Importer.parse(CasePack.snapshotJson(broken))
        assertTrue(sem is ImportResult.SemanticUnsolvable)
        val semAnalysis = Analysis.analyze(broken, AnalysisParams(1, false), now)
        assertTrue(semAnalysis.status is AnalysisStatus.SemanticUnsolvable)
    }
}
