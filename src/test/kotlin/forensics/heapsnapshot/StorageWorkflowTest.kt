package forensics.heapsnapshot

import forensics.heapsnapshot.domain.HspParser
import forensics.heapsnapshot.engine.AnalysisParams
import forensics.heapsnapshot.storage.Database
import forensics.heapsnapshot.storage.ImportOutcome
import forensics.heapsnapshot.storage.ProjectService
import forensics.heapsnapshot.storage.Reducer
import forensics.heapsnapshot.storage.Repository
import forensics.heapsnapshot.storage.SimulatedHalfWrite
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StorageWorkflowTest {

    @TempDir
    lateinit var temp: Path

    private val baseline = """
SNAPSHOT S9 case hsp-v1 t bytes
CLASS C1 Cache KEY k-cache
CLASS C2 L KEY k-l
OBJECT A C1 0x1000 256b
OBJECT B C2 0x2000 64b
OBJECT W C2 0x3000 10b
EDGE A B strong
EDGE A W weak
ROOT A JNI_GLOBAL r
INCOMPLETE BLOCK-1 truncated "x"
""".trimIndent()

    private fun svc(dbFile: File): Triple<Database, Repository, ProjectService> {
        val db = Database(dbFile.absolutePath)
        val repo = Repository(db)
        repo.recoverHalfWrites()
        return Triple(db, repo, ProjectService(repo, db))
    }

    @Test
    fun `time travel restores historical params and conclusions`() {
        val (db, _, service) = svc(temp.resolve("a.sqlite").toFile())
        val p = service.createProject("p", null).id
        val snap = (service.importSnapshot(p, "b.hsp", baseline, snapshotIdOverride = "snap-S9") as ImportOutcome.Imported).snapshotId

        val v1 = service.computeAnalysis(p, snap, paramsOverride = AnalysisParams(version = 1, weakEdgesKeepAlive = false)).first
        service.reviseParams(p, AnalysisParams(version = 2, weakEdgesKeepAlive = true), "开启弱保活")
        val v2 = service.computeAnalysis(p, snap).first

        // 回到 v1：参数是 v1，最新分析指针只含 v1 的
        val stateAtV1 = service.stateAt(p, v1.eventId)
        assertEquals(1, stateAtV1.currentParams.version)
        assertEquals(false, stateAtV1.currentParams.weakEdgesKeepAlive)
        val stateAtV2 = service.stateAt(p, v2.eventId)
        assertEquals(2, stateAtV2.currentParams.version)
        // 历史 blob 仍可独立读回：同一对象 W 在 v1 不可达、在 v2 可达，历史不被覆盖
        val r1 = Repository(db).analysisResult(v1.eventId)!!
        val r2 = Repository(db).analysisResult(v2.eventId)!!
        assertTrue(!r1.nodes.first { it.id == "W" }.reachable)
        assertTrue(r2.nodes.first { it.id == "W" }.reachable)
        assertEquals(1, r1.paramsVersion)
        assertEquals(2, r2.paramsVersion)
        db.close()
    }

    @Test
    fun `conflicting human decisions are both retained in history`() {
        val (db, _, service) = svc(temp.resolve("b.sqlite").toFile())
        val p = service.createProject("p", null).id
        val snap = (service.importSnapshot(p, "b.hsp", baseline, snapshotIdOverride = "snap-S9") as ImportOutcome.Imported).snapshotId
        val (_, result) = service.computeAnalysis(p, snap,
            paramsOverride = AnalysisParams(version = 2, weakEdgesKeepAlive = true))
        val cand = result.leakCandidates.first { it.objectId == "W" }
        service.adjudicate(p, cand.id, "ACCEPTED", "A_ACCEPT", "接受")
        service.adjudicate(p, cand.id, "REJECTED", "B_REJECT", "否定")
        val events = Repository(db).events(p).filter { it.type == "CandidateAdjudicated" }
        assertEquals(2, events.size)
        db.close()
    }

    @Test
    fun `export then import preserves identity order and provenance`() {
        val (db1, _, service1) = svc(temp.resolve("e1.sqlite").toFile())
        val p = service1.createProject("roundtrip", "case-x").id
        val snap = (service1.importSnapshot(p, "b.hsp", baseline, snapshotIdOverride = "snap-S9") as ImportOutcome.Imported).snapshotId
        service1.computeAnalysis(p, snap, paramsOverride = AnalysisParams(version = 1))
        val envelope = service1.exportProject(p)
        db1.close()

        val (db2, repo2, service2) = svc(temp.resolve("e2.sqlite").toFile())
        service2.importProject(envelope)
        val imported = repo2.events(p)
        val original = envelope.events
        assertEquals(original.map { it.eventId }, imported.map { it.eventId })
        assertEquals(original.map { it.seq }, imported.map { it.seq })
        // 父子来源关系保持
        original.zip(imported).forEach { (a, b) -> assertEquals(a.parentEventId, b.parentEventId) }
        // 材料层 hash 一致（原始表示未被规范化破坏）
        val raw = repo2.rawSnapshot(snap)!!
        assertEquals(baseline, raw.raw)
        // 分析 blob 身份一致
        assertEquals(envelope.analysisBlobs.map { it.eventId }.toSet(),
            repo2.allAnalysis(p).map { it.first }.toSet())
        db2.close()
    }

    @Test
    fun `half-write recovery leaves consistent state`() {
        val dbFile = temp.resolve("half.sqlite").toFile()
        val db = Database(dbFile.absolutePath)
        val repo = Repository(db)
        repo.recoverHalfWrites()
        val service = ProjectService(repo, db)
        val p = service.createProject("p", null).id

        // 正常导入
        val snap = (service.importSnapshot(p, "b.hsp", baseline, snapshotIdOverride = "snap-S9") as ImportOutcome.Imported).snapshotId
        val beforeCrash = repo.events(p).size

        // 通过直接调用追加并模拟崩溃：事件行已写、COMMITTED 未完成
        val state = service.stateAt(p, repo.tipEventId(p))
        val crashEvent = forensics.heapsnapshot.storage.StoredEvent(
            eventId = "evt-crash0001", seq = repo.nextSeq(p),
            parentEventId = repo.tipEventId(p), branch = state.branch,
            at = "2026-09-28T00:00:00Z", actor = "test", type = "ParamsRevised",
            payload = forensics.heapsnapshot.storage.EventPayload.ParamsRevised(
                newVersion = 9, params = AnalysisParams(version = 9),
                rationale = forensics.heapsnapshot.storage.Rationale("CRASH_TEST", "x"),
            ),
        )
        assertFailsWith<SimulatedHalfWrite> {
            repo.appendEventWithSnapshots(p, crashEvent, emptyList(), simulateCrashAfterAppend = true)
        }
        db.close()

        // 重新打开，模拟“崩溃后再启动”：journal 中残留 EVENTS_APPENDED，需清理
        val db2 = Database(dbFile.absolutePath)
        val repo2 = Repository(db2)
        val recovered = repo2.recoverHalfWrites()
        assertTrue(recovered.contains(p))
        val eventsAfter = repo2.events(p)
        assertEquals(beforeCrash, eventsAfter.size)
        assertTrue(eventsAfter.none { it.eventId == "evt-crash0001" })
        // tip 未被污染，状态仍可投影
        val state2 = Reducer.projectAt(eventsAfter, repo2.tipEventId(p))
        assertEquals(1, state2.currentParams.version)
        db2.close()
    }

    @Test
    fun `three failure kinds remain distinct through service api`() {
        val (db, _, service) = svc(temp.resolve("f.sqlite").toFile())
        val p = service.createProject("p", null).id
        assertTrue(service.importSnapshot(p, "x", "GARBAGE") is ImportOutcome.Corrupt)
        assertTrue(service.importSnapshot(p, "x",
            "SNAPSHOT S x hsp-v1 t bytes\nOBJECT A C1 0x1 1b\nEDGE A NOPE strong") is ImportOutcome.SemanticAmbiguity)
        val huge = (1..5000).joinToString("\n") { "SNAPSHOT S$it x hsp-v1" }
        assertTrue(service.importSnapshot(p, "x", huge) is ImportOutcome.BudgetExhausted)
        db.close()
    }

    @Test
    fun `localized source patch keeps old material and changes only one line`() {
        val (db, repo, service) = svc(temp.resolve("g.sqlite").toFile())
        val p = service.createProject("p", null).id
        val snap = (service.importSnapshot(p, "b.hsp", baseline, snapshotIdOverride = "snap-S9") as ImportOutcome.Imported).snapshotId
        val lineNo = baseline.split("\n").indexOfFirst { it.startsWith("OBJECT B ") } + 1
        service.patchSourceLine(p, snap, lineNo, "OBJECT B C2 0x2999 64b", "address-fix", "修正地址")
        // 旧材料仍可原样读回
        assertEquals(baseline, repo.rawSnapshot(snap)!!.raw)
        // 新材料身份为旧 id@L{n}，仅指定行变化
        val patched = repo.rawSnapshot("$snap@L$lineNo")!!
        val changedLines = baseline.split("\n").zip(patched.raw.split("\n")).count { (a, b) -> a != b }
        assertEquals(1, changedLines)
        db.close()
    }
}
