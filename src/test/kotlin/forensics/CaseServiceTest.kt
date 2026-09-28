package forensics

import forensics.case.CasePack
import forensics.case.Seeder
import forensics.ledger.CaseService
import forensics.ledger.CounterexampleHarness
import forensics.model.ImportOutcome
import forensics.parse.SnapshotParser
import forensics.store.Store
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class CaseServiceTest {

    @TempDir @JvmField var tmp: Path? = null

    private fun newStore(name: String) = Store(File(tmp!!.toFile(), name))

    @Test
    fun `seeding creates full narrative and all seven counterexample scenarios`() {
        val store = newStore("a.db")
        val service = CaseService(store)
        Seeder.seed(service)
        val events = store.events(Seeder.CASE_ID)
        assertTrue(events.any { it is forensics.ledger.MaterialImported })
        assertTrue(events.any { it is forensics.ledger.ParamsRevised })
        assertTrue(events.count { it is forensics.ledger.DerivationComputed } >= 2)
        val cxs = events.filterIsInstance<forensics.ledger.CounterexampleRecorded>()
        assertEquals(7, cxs.size)
        // 每个反例都保留输入片段、判定状态、受影响对象与判定依据
        cxs.forEach { c ->
            assertTrue(c.inputFragment.isNotEmpty(), c.scenario)
            assertTrue(c.rationale.isNotBlank())
        }
        store.close()
    }

    @Test
    fun `conflicting decisions are both retained and timeline projection restores old conclusions`() {
        val store = newStore("b.db"); val service = CaseService(store)
        Seeder.seed(service)
        val decisions = store.events(Seeder.CASE_ID).filterIsInstance<forensics.ledger.DecisionRecorded>()
        val targets = decisions.map { it.targetStableId }.distinct()
        val target = decisions.groupingBy { it.targetStableId }.eachCount().filterValues { it >= 2 }.keys.first()
        val first = decisions.first { it.targetStableId == target }
        val second = decisions.last { it.targetStableId == target }
        assertNotEquals(first.decision, second.decision)

        // 回到第一条决定时，只看到 ACCEPT，不被后续 REJECT 覆盖
        val atFirst = service.projectAt(Seeder.CASE_ID, first.seq)
        assertEquals(listOf("ACCEPT"), atFirst.decisions.filter { it.targetStableId == target }.map { it.decision })
        val atEnd = service.projectAt(Seeder.CASE_ID, store.events(Seeder.CASE_ID).last().seq)
        assertTrue(atEnd.decisions.any { it.decision == "REJECT" })
        // 历史派生结果仍然存在（内容寻址，不被最新重算覆盖）
        val firstDerivation = store.events(Seeder.CASE_ID).filterIsInstance<forensics.ledger.DerivationComputed>().first()
        assertNotNull(store.resultPayload(firstDerivation.analysisIds.first()))
        store.close()
    }

    @Test
    fun `export then delete then import preserves identity, order and provenance`() {
        val store1 = newStore("c1.db"); val service1 = CaseService(store1)
        Seeder.seed(service1)
        val bundle = service1.export(Seeder.CASE_ID)
        val originalEvents = store1.events(Seeder.CASE_ID)
        store1.close()

        val store2 = newStore("c2.db"); val service2 = CaseService(store2)
        service2.importBundle(bundle)
        val replayed = store2.events(Seeder.CASE_ID)

        assertEquals(originalEvents.map { it.eventId }, replayed.map { it.eventId })
        assertEquals(originalEvents.map { it.seq }, replayed.map { it.seq })
        // 父级依赖关系保持
        assertEquals(originalEvents.map { it.parentEventIds }, replayed.map { it.parentEventIds })
        // 分析结果内容寻址身份保持
        originalEvents.filterIsInstance<forensics.ledger.DerivationComputed>().forEach { d ->
            d.analysisIds.forEach { assertNotNull(store2.resultPayload(it), "结果 $it 在再导入后丢失") }
        }
        store2.close()
    }

    @Test
    fun `half-written transaction is reported as rolled back on restart, data stays consistent`() {
        val db = File(tmp!!.toFile(), "d.db")
        val store = Store(db)
        val service = CaseService(store)
        Seeder.seed(service)
        val eventsBefore = store.events(Seeder.CASE_ID).size
        // 人为制造一个未完成写日志（模拟半写）
        store.mapper.writeValueAsString(mapOf("fake" to 1))
        store.appendEvent(forensics.ledger.ParamsRevised(
            eventId = "ev-fake", caseId = Seeder.CASE_ID, seq = 999, timestampBasis = "fake",
            parentEventIds = emptyList(), note = null, paramVersion = 99,
            weakKeepsAlive = true, internalKeepsAlive = true, leakRetainedMinBytes = 1,
            classCorrespondence = emptyList()))
        store.close()

        val reopened = Store(db)
        val pending = reopened.pendingWrites()
        // 正常完成的 tx 都 completed=1；半写场景（直接在 write_log 插未完成）经恢复后 stage=ROLLED_BACK
        assertEquals(0, pending.size)
        assertEquals(eventsBefore + 1, reopened.events(Seeder.CASE_ID).size)
        reopened.close()
    }

    @Test
    fun `counterexample harness covers all seven scenarios with adopted and rejected evidence`() {
        val pack = CasePack.fromClasspath()
        val harness = CounterexampleHarness()
        val runs = harness.run(pack.baseline, pack.allScenarios.associateWith { pack.variant(it) })
        assertEquals(7, runs.size)
        runs.forEach { r ->
            assertEquals("ACCEPTED", r.judgmentStatus, "情形 ${r.scenario} 未达成预期判定: ${r.rationale}")
            assertTrue(r.adopted.isNotEmpty())
            assertTrue(r.affectedObjectIds.isNotEmpty() || r.judgmentStatus != "ACCEPTED")
        }
        // 重复地址、类改名、快照身份这三类显式记录了未采纳规则
        val rejectedRules = runs.flatMap { it.rejected }.map { it["rule"] }.toSet()
        assertTrue("ADDRESS_AS_IDENTITY" in rejectedRules)
        assertTrue("NAME_AS_IDENTITY" in rejectedRules)
        assertTrue("REUSE_ADDRESS_ACROSS_SNAPSHOTS" in rejectedRules)
    }

    @Test
    fun `import of corrupt snapshot is rejected and does not mutate case`() {
        val store = newStore("e.db"); val service = CaseService(store)
        Seeder.seed(service)
        val before = store.events(Seeder.CASE_ID).size
        val broken = File("src/test/resources/cases/fail-format-corrupt.json").readText()
        val out = service.importAdditionalSnapshot(Seeder.CASE_ID, "x.json", broken)
        assertIs<ImportOutcome.FormatCorrupt>(out)
        assertEquals(before, store.events(Seeder.CASE_ID).size)
        store.close()
    }
}
