import heapforensics.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertContains

class AnalysisTest {
    private fun baseline() = CasePack.baseline()

    @Test
    fun `cycle graph stays finite and dominators are correct`() {
        val r = (analyze(baseline(), AnalysisParams(leakThresholdBytes = 200)) as AnalysisOutcome.Ok).result
        val byId = r.objects.associateBy { it.objectId }
        assertEquals("o1", byId.getValue("o2").dominatorParent)
        assertEquals("o1", byId.getValue("o3").dominatorParent)
        assertEquals("o1", byId.getValue("o4").dominatorParent)
        assertEquals("o9", byId.getValue("o8").dominatorParent)
        // o5 is the join of two roots: no unique dominator
        assertEquals(null, byId.getValue("o5").dominatorParent)
        assertEquals(368L, byId.getValue("o1").retainedBytes)
        assertEquals(352L, byId.getValue("o9").retainedBytes)
        assertEquals(256L, byId.getValue("o5").retainedBytes)
    }

    @Test
    fun `weak policy decides liveness explicitly`() {
        val ignore = (analyze(baseline(), AnalysisParams(weakPolicy = WeakPolicy.WEAK_IGNORE.id)) as AnalysisOutcome.Ok).result
        assertTrue(ignore.unreachable.contains("o7"), "o7 must be unreachable when weak does not keep alive")
        val keep = (analyze(baseline(), AnalysisParams(weakPolicy = WeakPolicy.WEAK_KEEP_ALIVE.id)) as AnalysisOutcome.Ok).result
        val byId = keep.objects.associateBy { it.objectId }
        assertEquals("o4", byId.getValue("o7").dominatorParent)
        assertEquals(176L, byId.getValue("o4").retainedBytes)
        assertTrue(byId.getValue("o7").reachableViaWeakOnly)
        assertFalse(byId.getValue("o2").reachableViaWeakOnly)
    }

    @Test
    fun `duplicate addresses never merge identities`() {
        val d = derive(baseline())
        assertEquals(9, d.objects.size)
        assertTrue(d.diagnostics.any { it.message.contains("address 0x7f20 shared") })
        val r = (analyze(baseline(), AnalysisParams()) as AnalysisOutcome.Ok).result
        val addrs = r.objects.map { it.objectId to it.address }.toMap()
        assertEquals("0x7f20", addrs["o5"])
        assertEquals("0x7f20", addrs["o9"])
    }

    @Test
    fun `incomplete object is semantically unsolvable with positioned diagnostic`() {
        val (mutated, _) = applyMutation(baseline(),
            CasePack.scenarios().scenarios.first { it.scenarioId == "incomplete-object" }.mutation)
        val outcome = analyze(mutated, AnalysisParams())
        assertTrue(outcome is AnalysisOutcome.SemanticallyUnsolvable)
        val diags = (outcome as AnalysisOutcome.SemanticallyUnsolvable).diagnostics
        assertTrue(diags.any { it.position == "\$.objects[9].sizeBytes" },
            "diagnostics: ${diags.map { it.position }}")
    }

    @Test
    fun `multiple equal-length root paths are all retained`() {
        val r = (analyze(baseline(), AnalysisParams()) as AnalysisOutcome.Ok).result
        val o5 = r.objects.first { it.objectId == "o5" }
        val hops = o5.rootPaths.map { it.hops }
        assertEquals(3, hops.size)
        assertTrue(hops.contains(listOf("o1", "o2", "o5")))
        assertTrue(hops.contains(listOf("o1", "o3", "o5")))
        assertTrue(hops.contains(listOf("o9", "o8", "o5")))
        assertEquals(setOf("o1", "o9"), o5.rootPaths.map { it.rootObjectId }.toSet())
        // extra equal-length edge adds a fourth candidate, retained size unchanged
        val (mut2, _) = applyMutation(baseline(),
            CasePack.scenarios().scenarios.first { it.scenarioId == "multi-root-equal-path" }.mutation)
        val r2 = (analyze(mut2, AnalysisParams()) as AnalysisOutcome.Ok).result
        val o5b = r2.objects.first { it.objectId == "o5" }
        assertEquals(4, o5b.rootPaths.size)
        assertTrue(o5b.rootPaths.any { it.hops == listOf("o1", "o4", "o5") })
        assertEquals(256L, o5b.retainedBytes)
    }

    @Test
    fun `class rename matches by classId not name`() {
        val d = diffSnapshots(baseline(), CasePack.renamed())
        assertEquals(4, d.groups.count { it.status == "RENAMED" }, d.groups.toString())
        assertEquals(1, d.groups.count { it.status == "ADDED" })
        val c1 = d.groups.first { it.classId == "c1" }
        assertEquals("com.acme.cache.CacheManager", c1.nameA)
        assertEquals("com.acme.legacy.CacheManager", c1.nameB)
    }

    @Test
    fun `equivalent inputs produce identical output and ordering`() {
        val p = AnalysisParams()
        val r1 = (analyze(baseline(), p) as AnalysisOutcome.Ok).result
        val r2 = (analyze(baseline(), p) as AnalysisOutcome.Ok).result
        assertEquals(r1.inputDigest, r2.inputDigest)
        assertEquals(r1.objects, r2.objects)
        assertEquals(r1.leakCandidates, r2.leakCandidates)
    }

    @Test
    fun `resource budget exhaustion is a distinct outcome`() {
        val outcome = analyze(baseline(), AnalysisParams(maxVisited = 1))
        assertTrue(outcome is AnalysisOutcome.BudgetExhausted)
    }

    @Test
    fun `format corruption is a distinct outcome`() {
        assertTrue(parseSnapshot("{ not json") is ParseOutcome.FormatCorrupt)
    }

    @Test
    fun `leak candidates sorted retained desc with identity tiebreak`() {
        val r = (analyze(baseline(), AnalysisParams(leakThresholdBytes = 200)) as AnalysisOutcome.Ok).result
        assertEquals(listOf("o1", "o9", "o5"), r.leakCandidates.map { it.objectId })
    }
}
