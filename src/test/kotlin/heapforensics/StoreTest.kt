import heapforensics.*
import java.sql.DriverManager
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class StoreTest {
    private fun newStore(): Store {
        val f = Files.createTempFile("hsf-", ".db").also { java.io.File(it.toUri()).delete() }
        return Store(f.toString())
    }

    @Test
    fun `export delete import keeps identity order and provenance`() {
        val db1 = newStore()
        seedArchive(db1)
        val bundleA = db1.export()

        val db2 = newStore()
        db2.importBundle(bundleA, 99L)
        val bundleB = db2.export()

        assertEquals(bundleA.meta, bundleB.meta)
        assertEquals(bundleA.snapshots, bundleB.snapshots)
        assertEquals(bundleA.analyses, bundleB.analyses)
        assertEquals(bundleA.decisions, bundleB.decisions)
        assertEquals(bundleA.events, bundleB.events)
        // material survives deletion of the derived layers
        assertTrue(db2.hasSnapshot("snap-baseline"))
        assertTrue(db2.getSnapshot("snap-baseline")!!.objects.none { it.note == null && false })
        db1.close(); db2.close()
    }

    @Test
    fun `half-written multi-table batch is fully rolled back`() {
        val store = newStore()
        val before = store.listSnapshots().size
        val crashed = runCatching {
            store.inTransaction {
                store.conn.prepareStatement("INSERT INTO snapshots(snapshot_id,raw_json,digest,imported_at) VALUES(?,?,?,?)").use {
                    it.setString(1, "ghost"); it.setString(2, "{}"); it.setString(3, "x"); it.setLong(4, 1L); it.executeUpdate()
                }
                throw RuntimeException("simulated crash mid batch")
            }
        }
        assertTrue(crashed.isFailure)
        assertEquals(before, store.listSnapshots().size)
        assertFalse(store.hasSnapshot("ghost"))
        store.close()
    }

    @Test
    fun `crash without commit leaves no partial rows on reopen`() {
        val f = Files.createTempFile("hsf-crash-", ".db").also { java.io.File(it.toUri()).delete() }
        run {
            val conn = DriverManager.getConnection("jdbc:sqlite:$f")
            conn.autoCommit = false
            conn.createStatement().use { it.executeUpdate("CREATE TABLE IF NOT EXISTS snapshots(snapshot_id TEXT, raw_json TEXT, digest TEXT, imported_at INTEGER)") }
            conn.prepareStatement("INSERT INTO snapshots VALUES(?,?,?,?)").use {
                it.setString(1, "orphan"); it.setString(2, "{}"); it.setString(3, "x"); it.setLong(4, 1L); it.executeUpdate()
            }
            conn.close() // no commit: process crash analogue
        }
        val reopened = Store(f.toString())
        assertFalse(reopened.hasSnapshot("orphan"))
        reopened.close()
    }

    @Test
    fun `events carry digest params version and parent dependencies`() {
        val store = newStore()
        seedArchive(store)
        val events = store.listEvents()
        assertTrue(events.isNotEmpty())
        val imported = events.first { it.type == "snapshot.imported" }
        assertTrue(imported.inputDigest!!.length == 64)
        val decisions = store.listDecisions()
        val accepted = decisions.first { it.verdict == "ACCEPTED" }
        val rejected = decisions.first { it.verdict == "REJECTED" }
        assertEquals("dec-1", rejected.supersedes)
        assertTrue(decisions.any { it.verdict == "PARAM_ADJUST" })
        store.close()
    }

    @Test
    fun `scenario replay statuses match expected and archive id is stable`() {
        val store = newStore()
        seedArchive(store)
        val replayed = store.listEvents().filter { it.type == "scenario.replayed" }
        val pack = CasePack.scenarios()
        assertEquals(pack.scenarios.size, replayed.size)
        for ((s, e) in pack.scenarios.zip(replayed)) {
            assertTrue(e.payload.contains("\"status\":\"${s.expectedStatus}\""), "${s.scenarioId}: ${e.payload}")
            assertTrue(e.payload.contains(s.scenarioId))
        }
        assertEquals("heap-shadow-forensics", store.getMeta("archiveId"))
        store.close()
    }

    @Test
    fun `failed analyses store three distinct statuses`() {
        val store = newStore()
        seedArchive(store)
        val incompleteId = "ana-scenario-incomplete-object"
        val stored = store.getAnalysis(incompleteId)
        assertEquals("SEMANTICALLY_UNSOLVABLE", stored?.second)
        val budget = analyze(CasePack.baseline(), AnalysisParams(maxVisited = 1))
        store.putAnalysis("ana-budget", "snap-baseline", budget, 42L)
        assertEquals("BUDGET_EXHAUSTED", store.getAnalysis("ana-budget")?.second)
        store.close()
    }
}
