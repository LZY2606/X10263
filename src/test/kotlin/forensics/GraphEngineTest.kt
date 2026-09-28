package forensics

import forensics.graph.AnalysisParams
import forensics.graph.DiffEngine
import forensics.graph.GraphEngine
import forensics.graph.SUPER_ROOT
import forensics.model.ImportOutcome
import forensics.parse.SnapshotParser
import java.io.File
import kotlin.test.*

class GraphEngineTest {
    private val parser = SnapshotParser()
    private fun snap(name: String) =
        (parser.parse(name, File("src/test/resources/cases/$name").readText()) as ImportOutcome.Ok).snapshot
    private val engine = GraphEngine()

    @Test
    fun `cycle graph still produces a unique dominator tree on the reachable graph`() {
        val s = snap("cx-cycle.json")
        val r = engine.analyze("c", s, AnalysisParams(1))
        listOf("snap-cx-cycle:a", "snap-cx-cycle:b", "snap-cx-cycle:c", "snap-cx-cycle:r").forEach {
            assertTrue(r.dominator.containsKey(it), "dominator missing for $it")
        }
        // r is directly rooted -> dominator super root (null after filtering); a dominated by r
        assertNull(r.dominator["snap-cx-cycle:r"])
        assertEquals("snap-cx-cycle:r", r.dominator["snap-cx-cycle:a"])
    }

    @Test
    fun `weak reference liveness is decided solely by explicit policy`() {
        val s = snap("cx-weak-policy.json")
        val off = engine.analyze("c", s, AnalysisParams(1, weakKeepsAlive = false))
        val on = engine.analyze("c", s, AnalysisParams(2, weakKeepsAlive = true))
        assertFalse(off.nodes.first { it.objectId == "target" }.reachable)
        assertTrue(on.nodes.first { it.objectId == "target" }.reachable)
        // dominator tree only covers reachable nodes
        assertNull(off.dominator["snap-cx-weak:target"])
        assertNotNull(on.dominator["snap-cx-weak:target"])
    }

    @Test
    fun `duplicate addresses do not merge retained sizes`() {
        val s = snap("cx-duplicate-address.json")
        val r = engine.analyze("c", s, AnalysisParams(1))
        assertEquals(100L, r.retainedBytes["snap-cx-dupaddr:x-1"])
        assertEquals(200L, r.retainedBytes["snap-cx-dupaddr:x-2"])
    }

    @Test
    fun `incomplete objects yield positional validity and diagnostics, not fake success`() {
        val s = snap("cx-incomplete-object.json")
        val r = engine.analyze("c", s, AnalysisParams(1))
        assertFalse(r.validity.valid)
        assertTrue(r.validity.invalidPositions.any { it.contains("i-bad") })
        assertTrue(r.diagnostics.all { it.position.isNotBlank() })
        assertNotNull(r.validity.invalidReason)
    }

    @Test
    fun `multiple equal-length root paths are all retained as candidates`() {
        val s = snap("cx-equal-root-paths.json")
        val r = engine.analyze("c", s, AnalysisParams(1))
        val target = "snap-cx-equalpaths:p-target"
        val paths = r.rootPaths.getValue(target)
        assertEquals(2, paths.size)
        assertEquals(setOf("JNI_LOCAL", "JNI_GLOBAL"), paths.map { it.rootKind }.toSet())
        assertEquals(1, paths.map { it.lengthEdges }.distinct().size)
        val pathStarts = paths.map { it.nodeIds.first() }.toSet()
        assertEquals(2, pathStarts.size)
    }

    @Test
    fun `class rename maps through canonical identity in diff`() {
        val a = snap("baseline.json")
        val b = snap("snapshot-B.json")
        val params = AnalysisParams(2,
            classCorrespondence = listOf(forensics.graph.ClassCorrespondence(
                "canonical:CacheEntry", mapOf("snap-A" to "c-entry", "snap-B" to "c-cachex"))))
        val ra = engine.analyze("c", a, params)
        val rb = engine.analyze("c", b, params)
        val d = DiffEngine().diff("c", a, b, ra, rb, params)
        val entry = d.deltas.first { it.canonicalId == "canonical:CacheEntry" }
        assertTrue(entry.renamed)
        assertNull(entry.onlyInSnapshot)
        assertNotNull(entry.retainedBytesDelta)
    }

    @Test
    fun `equivalent inputs produce stable order and snapshot-scoped identity`() {
        val s = snap("cx-snapshot-identity.json")
        val r1 = engine.analyze("case#1", s, AnalysisParams(1))
        val r2 = engine.analyze("case#2", s, AnalysisParams(1))
        assertEquals(r1.nodes.map { it.nodeId }, r2.nodes.map { it.nodeId })
        assertEquals(r1.leakCandidates.map { it.nodeId }, r2.leakCandidates.map { it.nodeId })
        assertTrue(r1.nodes.all { it.nodeId.startsWith("snap-cx-identity:") })
        assertEquals(r1.analysisId, r2.analysisId)
    }

    @Test
    fun `unreachable objects never appear in dominator tree`() {
        val s = snap("baseline.json")
        val r = engine.analyze("c", s, AnalysisParams(1, weakKeepsAlive = false))
        assertFalse(r.dominator.containsKey("snap-A:obj-6"))
        assertFalse(r.nodes.first { it.objectId == "obj-6" }.reachable)
    }

    @Test
    fun `internal edge policy changes reachability explicitly`() {
        val s = snap("baseline.json")
        val keep = engine.analyze("c", s, AnalysisParams(1, internalKeepsAlive = true))
        val drop = engine.analyze("c", s, AnalysisParams(2, internalKeepsAlive = false))
        assertTrue(keep.nodes.first { it.objectId == "obj-4" }.reachable)
        assertFalse(drop.nodes.first { it.objectId == "obj-4" }.reachable)
    }
}
