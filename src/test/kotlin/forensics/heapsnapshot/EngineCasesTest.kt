package forensics.heapsnapshot

import forensics.heapsnapshot.domain.Diagnostic
import forensics.heapsnapshot.domain.EdgeKind
import forensics.heapsnapshot.domain.HspParser
import forensics.heapsnapshot.engine.AnalysisParams
import forensics.heapsnapshot.engine.Analyzer
import forensics.heapsnapshot.engine.ClassDiffEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFalse

/**
 * 验收：循环图、弱引用策略、重复地址、不完整对象、多 root 等长路径、
 * 类改名与快照身份对应。
 */
class EngineCasesTest {

    private val sample = """
SNAPSHOT S1 demo hsp-v1 2026-09-28T00:00:00+08:00 bytes
CLASS C1 Cache KEY k-cache
CLASS C2 Listener KEY k-listener
CLASS C3 Box KEY k-box
OBJECT A C1 0x1000 256b
OBJECT B C2 0x1000 64b
OBJECT C C2 0x2000 128b
OBJECT D C3 0x3000 64b
OBJECT E C3 0x4000 NaN-size INCOMPLETE
EDGE A B strong
EDGE B A strong
EDGE A C strong
EDGE B C strong
EDGE A D weak
ROOT A JNI_GLOBAL cache-root
INCOMPLETE BLOCK-X truncated "字段截断"
""".trimIndent()

    private fun parse(raw: String = sample) = (HspParser().parse(raw) as HspParser.Result.Ok).snapshot

    @Test
    fun `baseline preserves all raw material`() {
        val snap = parse()
        assertEquals("hsp-v1", snap.meta.parserVersion)
        // 原始对象 id / 地址原值 / 类元数据 / 边 / root 分类 / 不完整块
        assertEquals(listOf("A", "B", "C", "D", "E"), snap.objects.map { it.id })
        assertEquals("0x1000", snap.objects.first { it.id == "A" }.addressRaw)
        assertEquals(EdgeKind.STRONG, snap.edges.first { it.from == "A" && it.to == "B" }.kind)
        assertEquals(EdgeKind.WEAK, snap.edges.first { it.from == "A" && it.to == "D" }.kind)
        assertEquals("JNI_GLOBAL", snap.roots.first().kind)
        assertEquals(1, snap.incompleteMarks.size)
        // 重复地址保留为两个不同对象（地址不是身份）
        val sameAddr = snap.objects.filter { it.address == 0x1000L }
        assertEquals(2, sameAddr.size)
        assertEquals(setOf("A", "B"), sameAddr.map { it.id }.toSet())
        // 不可转换大小留在原位置并附诊断，不为 null 伪造
        val e = snap.objects.first { it.id == "E" }
        assertFalse(e.size.convertible)
        assertEquals("NaN-size", e.size.raw)
        assertTrue(snap.diagnostics.any { it.code == "UNCONVERTIBLE_SIZE" && it.line == e.line })
    }

    @Test
    fun `cycle graph does not break reachability or dominator tree`() {
        val snap = parse()
        val r = Analyzer.analyze(snap, AnalysisParams(version = 1), emptyList())
        // A<->B 环：两者都可达；环上节点互不支配，支配父均为 VROOT(null)
        val nodeIds = r.nodes.associateBy { it.id }
        assertEquals(setOf("A", "B", "C"), r.nodes.filter { it.reachable }.map { it.id }.toSet())
        assertNull(nodeIds["A"]!!.dominator)
        assertNull(nodeIds["B"]!!.dominator)
        // C 被 A 与 B 共同引用，支配父是 VROOT
        assertNull(nodeIds["C"]!!.dominator)
        // D 仅经弱边，weak=false 时不可达，不进入支配树
        assertFalse(nodeIds["D"]!!.reachable)
        assertEquals("UNREACHABLE_EXCLUDED", nodeIds["D"]!!.retainedStatus)
    }

    @Test
    fun `weak reference keep-alive is decided by explicit policy`() {
        val snap = parse()
        val weakOff = Analyzer.analyze(snap, AnalysisParams(version = 1, weakEdgesKeepAlive = false), emptyList())
        val weakOn = Analyzer.analyze(snap, AnalysisParams(version = 2, weakEdgesKeepAlive = true), emptyList())
        assertFalse(weakOff.nodes.first { it.id == "D" }.reachable)
        assertTrue(weakOn.nodes.first { it.id == "D" }.reachable)
        // 显式策略证据进入候选理由码与参数版本
        val candD = weakOn.leakCandidates.first { it.objectId == "D" }
        assertTrue(candD.reasonCodes.any { it.startsWith("REACHABLE_ONLY_VIA_WEAK_EDGE_POLICY") })
        assertEquals(1, weakOff.paramsVersion)
        assertEquals(2, weakOn.paramsVersion)
    }

    @Test
    fun `multiple equal-length root paths are all retained`() {
        val snap = parse()
        val r = Analyzer.analyze(snap, AnalysisParams(version = 1), emptyList())
        val cPaths = r.rootPaths["C"]!!
        assertEquals(2, cPaths.size)
        assertEquals(1, cPaths.map { it.hops }.toSet().size)
        assertEquals(setOf(listOf("A", "C"), listOf("A", "B", "C")).let {
            cPaths.all { p -> p.objectIds in it }
        }, true)
        val candC = r.leakCandidates.first { it.objectId == "C" }
        assertTrue(candC.reasonCodes.contains("MULTIPLE_EQUAL_LENGTH_ROOT_PATHS"))
    }

    @Test
    fun `retained size handles incomplete and unconvertible values without faking`() {
        val snap = parse()
        val r = Analyzer.analyze(snap, AnalysisParams(version = 1), emptyList())
        // 环 A<->B：A 不支配 B；C 被 A、B 共同引用，支配父为 VROOT。
        // 因此每个节点的保留大小=自身大小，不存在被环重复计入的假象。
        val a = r.nodes.first { it.id == "A" }
        val b = r.nodes.first { it.id == "B" }
        val c = r.nodes.first { it.id == "C" }
        assertEquals(256L, a.retainedSizeBytes)
        assertEquals(64L, b.retainedSizeBytes)
        assertEquals(128L, c.retainedSizeBytes)
        assertEquals("EXACT", a.retainedStatus)
        val e = r.nodes.first { it.id == "E" }
        assertEquals("UNREACHABLE_EXCLUDED", e.retainedStatus)
    }

    @Test
    fun `corrupt format and semantic ambiguity and budget are distinct outcomes`() {
        val corrupt = HspParser().parse("NOTHISP at all\n")
        assertTrue(corrupt is HspParser.Result.Corrupt)
        val sem = """
SNAPSHOT S2 d hsp-v1 now bytes
OBJECT X C1 0x1 1b
EDGE X Y strong
ROOT Z JNI_GLOBAL z
""".trimIndent()
        val sr = HspParser().parse(sem)
        assertTrue(sr is HspParser.Result.SemanticAmbiguity)
        assertEquals(2, (sr as HspParser.Result.SemanticAmbiguity).diagnostics.count { it.code == "SEMANTIC_AMBIGUITY" })
        val big = (1..300_000).joinToString("\n") { "SNAPSHOT S$it d hsp-v1" }
        assertTrue(HspParser(maxLines = 1000).parse(big) is HspParser.Result.BudgetExhausted)
    }

    @Test
    fun `class rename maps via explicit correspondence directive`() {
        val before = parse()
        val afterRaw = """
SNAPSHOT S2 d2 hsp-v1 now bytes
CLASS C1 Cache KEY k-cache
CLASS C2 Subscriber KEY k-subscriber
OBJECT A C1 0x1001 300b
OBJECT F C2 0x5000 70b
ROOT A JNI_GLOBAL cache-root
CORRESPOND class k-listener = k-subscriber "rename"
""".trimIndent()
        val after = (HspParser().parse(afterRaw) as HspParser.Result.Ok).snapshot
        val diff = ClassDiffEngine.diff(before, after)
        val listenerDelta = diff.deltas.first { it.classKey == "k-listener" }
        assertEquals("RENAMED_VIA_DIRECTIVE", listenerDelta.verdict)
        assertEquals("CORRESPONDENCE_DIRECTIVE", listenerDelta.identityBasis)
        assertEquals("Listener", listenerDelta.nameBefore)
        assertEquals("Subscriber", listenerDelta.nameAfter)
        // k-box 在修订后消失；通过指令映射的 k-listener 不应被当成 ADDED/REMOVED
        val boxDelta = diff.deltas.first { it.classKey == "k-box" }
        assertEquals("REMOVED_IN_AFTER", boxDelta.verdict)
        assertFalse(diff.deltas.any { it.classKey == "k-subscriber" && it.identityBasis != "CORRESPONDENCE_DIRECTIVE" })
    }

    @Test
    fun `snapshot identity uses object id not address after address change`() {
        val snap = parse()
        // 修改 B 的地址（局部源改动）：对象身份仍为 B，图结构与支配关系不变
        val patched = snap.rawLines.toMutableList().apply {
            val i = indexOfFirst { it.startsWith("OBJECT B ") }
            this[i] = "OBJECT B C2 0x9999 64b"
        }.joinToString("\n")
        val reParsed = (HspParser().parse(patched) as HspParser.Result.Ok).snapshot
        val r1 = Analyzer.analyze(snap, AnalysisParams(version = 1), emptyList())
        val r2 = Analyzer.analyze(reParsed, AnalysisParams(version = 1), emptyList())
        assertEquals(
            r1.nodes.map { it.id to (it.dominator ?: "") }.sortedBy { it.first },
            r2.nodes.map { it.id to (it.dominator ?: "") }.sortedBy { it.first },
        )
        // 地址只作为观察值变化
        assertEquals("0x9999", r2.nodes.first { it.id == "B" }.addressRaw)
    }

    @Test
    fun `equivalent input yields stable ordering`() {
        val shuffled = sample.split("\n").filter { !it.startsWith("EDGE") && !it.startsWith("OBJECT") }
            .joinToString("\n") + "\n" +
            sample.split("\n").filter { it.startsWith("OBJECT") }.shuffled().joinToString("\n") + "\n" +
            sample.split("\n").filter { it.startsWith("EDGE") }.shuffled().joinToString("\n")
        val r1 = Analyzer.analyze(parse(), AnalysisParams(version = 1), emptyList())
        val r2 = Analyzer.analyze((HspParser().parse(shuffled) as HspParser.Result.Ok).snapshot, AnalysisParams(version = 1), emptyList())
        assertEquals(r1.nodes.map { it.id }, r2.nodes.map { it.id })
        assertEquals(r1.edges.map { it.from to it.to }, r2.edges.map { it.from to it.to })
    }

    @Test
    fun `diagnostics carry line positions and unknown units stay raw`() {
        val raw = """
SNAPSHOT S3 d hsp-v1 now bytes
CLASS C1 X KEY k
OBJECT A C1 0x1 300xy
""".trimIndent()
        val r = HspParser().parse(raw) as HspParser.Result.Ok
        val d = r.snapshot.diagnostics.first { it.code == "UNKNOWN_UNIT" }
        assertEquals(3, d.line)
        assertEquals("300xy", r.snapshot.objects.first().size.raw)
    }
}
