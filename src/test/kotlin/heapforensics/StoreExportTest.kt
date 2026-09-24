package heapforensics

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class StoreExportTest {
    private val tmp = Files.createTempDirectory("hftest").toFile()
    private val dbPath = tmp.resolve("test.db").path

    private fun freshStore() = Store(dbPath)

    @AfterTest fun cleanup() { tmp.deleteRecursively() }

    @Test fun `seed replays full narrative`() {
        freshStore().use { store ->
            CasePack.seed(store) { "2026-09-19T00:00:00Z" }
            assertEquals(3, store.listSnapshots().size)
            val types = store.allEvents().map { it.type }
            assertTrue(types.contains("SNAPSHOT_IMPORTED"))
            assertTrue(types.contains("PARAMS_SET"))
            assertTrue(types.contains("WEAK_NOT_KEPT"))
            assertTrue(types.contains("WEAK_KEPT_ALIVE"))
            assertEquals(2, store.allEvents().count { it.type == "DECISION" })
            assertEquals(1, store.effectiveDecisions(store.maxSeq()).values.count { it.attributes["target"] == "finding-leak-obj-ring-a" })
            // latest conflicting decision wins: REJECT, but both are retained
            val eff = store.effectiveDecisions(store.maxSeq())["finding-leak-obj-ring-a"]!!
            assertEquals("REJECT", eff.attributes["kind"])
        }
    }

    @Test fun `half-write batch rolls back atomically`() {
        freshStore().use { store ->
            CasePack.seed(store)
            val before = store.allEvents().size
            val good = DomainEvent("evt-half-1", 0, "DECISION", null, 2, "ok", emptyList(),
                mapOf("target" to "t1", "kind" to "ACCEPT"), "t")
            val bad = good.copy(eventId = "evt-half-1") // duplicate event_id -> constraint violation
            val threw = try { store.appendEvents(listOf(good, bad)); false } catch (e: Throwable) { true }
            assertTrue(threw)
            assertEquals(before, store.allEvents().size, "half-written batch must not leave partial rows")
            assertNull(store.verifyEventChain())
        }
    }

    @Test fun `export then delete then import preserves identity order and provenance`() {
        val archive = freshStore().use { store ->
            CasePack.seed(store)
            ExportImport.exportAll(store)
        }
        Files.delete(Paths.get(dbPath))
        Files.deleteIfExists(Paths.get("$dbPath-wal"))
        Files.deleteIfExists(Paths.get("$dbPath-shm"))
        freshStore().use { store ->
            val outcome = ExportImport.importAll(store, archive)
            assertTrue(outcome is ImportOutcome.Ok, outcome.toString())
            assertEquals(3, store.listSnapshots().size)
            CasePack.seed(store) // now must be a no-op (idempotent, same ids)
        }
        // reopen and assert identities/order/provenance intact
        freshStore().use { store ->
            val events = store.allEvents()
            val original = events.map { it.seq }
            assertEquals(original, (1L..events.size.toLong()).toList(), "sequence order preserved")
            assertTrue(events.any { it.eventId == "evt-import-heap-a" })
            assertTrue(events.any { it.eventId == "evt-decision-reject-ring" })
            assertNull(store.verifyEventChain())
            // source relationship: runs reference input digests and supersession
            val runs = store.runs()
            val v1 = runs.first { it.runId == "run-heap-a-v1" }
            assertNotEquals(null, v1.supersededBy)
            assertTrue(v1.invalidationReason!!.contains("v2"))
        }
    }

    @Test fun `truncated archive is FormatCorrupt and store stays unchanged`() {
        val archive = freshStore().use { store ->
            CasePack.seed(store)
            ExportImport.exportAll(store)
        }
        freshStore().use { store ->
            val truncated = archive.take(archive.length / 2)
            val outcome = ExportImport.importAll(store, truncated)
            assertTrue(outcome is ImportOutcome.FormatCorrupt)
            assertEquals(0, store.listSnapshots().size, "corrupt import must not mutate store")
            // checksum tampering is also rejected
            val tampered = archive.replace("\"checksum\"", "\"checksumX\"")
            assertTrue(ExportImport.importAll(store, tampered) is ImportOutcome.FormatCorrupt)
        }
    }

    @Test fun `replay at old event seq restores params and visible conclusions`() {
        freshStore().use { store ->
            CasePack.seed(store)
            // seq of the v2 PARAMS_SET event
            val v2Seq = store.allEvents().first { it.type == "PARAMS_SET" && it.attributes["version"] == "2" }.seq
            val atV1 = store.paramsAsOf(v2Seq - 1)
            assertFalse(atV1.weakKeepAlive)
            val atV2 = store.paramsAsOf(v2Seq)
            assertTrue(atV2.weakKeepAlive)
            // before the conflicting pair, no decision exists
            val acceptSeq = store.allEvents().first { it.eventId == "evt-decision-accept-ring" }.seq
            assertFalse(store.decisionsAsOf(acceptSeq - 1).any { it.attributes["target"] == "finding-leak-obj-ring-a" })
            assertEquals("ACCEPT", store.effectiveDecisions(acceptSeq)["finding-leak-obj-ring-a"]?.attributes?.get("kind"))
        }
    }

    @Test fun `local source change only invalidates its own dependency`() {
        freshStore().use { store ->
            CasePack.seed(store)
            val runs = store.runs()
            val r2Run = runs.first { it.runId == "run-heap-a-r2-v2" }
            assertNull(r2Run.supersededBy)
            val bRun = runs.first { it.runId == "run-heap-b-v2" }
            assertNull(bRun.supersededBy, "heap-b analysis must not be invalidated by heap-a-r2 change")
        }
    }
}
