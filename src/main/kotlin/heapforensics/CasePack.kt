package heapforensics

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * 离线案例包「堆影取证室：内存快照、弱引用边与保留路径候选」。
 * 叙事：导入两个堆快照 -> 基线分析 -> 参数修订(弱引用策略) -> 局部源数据改动 -> 冲突人工决定。
 */
object CasePack {
    private val json = Json { encodeDefaults = true }

    private val classesA = listOf(
        ClassMeta("cls-app", "com.example.App", null, 32,
            listOf(FieldMeta("cache", 8, EdgeKind.STRONG), FieldMeta("workerA", 16, EdgeKind.STRONG))),
        ClassMeta("cls-cache", "com.example.Cache", "cls-app", 24,
            listOf(FieldMeta("entry", 8, EdgeKind.WEAK))),
        ClassMeta("cls-entry", "com.example.CacheEntry", null, 48),
        ClassMeta("cls-node", "com.example.RingNode", null, 24,
            listOf(FieldMeta("next", 8, EdgeKind.STRONG))),
        ClassMeta("cls-buf", "com.example.Buffer", null, 64,
            listOf(FieldMeta("backing", 8, EdgeKind.INTERNAL))),
        ClassMeta("cls-handler", "com.example.Handler", null, 40)
    )

    private val objectsA = listOf(
        HeapObject("obj-app", "0x1000", "cls-app", 32, "com.example.App.main(App.java:42)"),
        HeapObject("obj-cache", "0x1020", "cls-cache", 24, "com.example.App.init(App.java:57)"),
        HeapObject("obj-entry", "0x1040", "cls-entry", 48, "com.example.Cache.put(Cache.java:88)"),
        HeapObject("obj-ring-a", "0x1060", "cls-node", 24, "com.example.Ring.build(Ring.java:12)"),
        HeapObject("obj-ring-b", "0x1080", "cls-node", 24, "com.example.Ring.build(Ring.java:13)"),
        HeapObject("obj-buf", "0x10A0", "cls-buf", 64, "com.example.IO.open(IO.java:31)"),
        HeapObject("obj-buf-shadow", "0x10A0", "cls-buf", 64, "com.example.IO.mmap(IO.java:77)"), // duplicate address
        HeapObject("obj-partial", "0x10C0", "cls-handler", 40, "com.example.Net.dispatch(Net.java:5)", incomplete = true),
        HeapObject("obj-multi", "0x10E0", "cls-handler", 40, "com.example.Net.register(Net.java:20)"),
        HeapObject("obj-hop-x", "0x1100", "cls-handler", 40, "com.example.Net.left(Net.java:21)"),
        HeapObject("obj-hop-y", "0x1120", "cls-handler", 40, "com.example.Net.right(Net.java:22)")
    )

    private val edgesA = listOf(
        HeapEdge("obj-app", "obj-cache", EdgeKind.STRONG, "cache"),
        HeapEdge("obj-cache", "obj-entry", EdgeKind.WEAK, "entry"),
        HeapEdge("obj-app", "obj-ring-a", EdgeKind.STRONG, "workerA"),
        HeapEdge("obj-ring-a", "obj-ring-b", EdgeKind.STRONG, "next"),
        HeapEdge("obj-ring-b", "obj-ring-a", EdgeKind.STRONG, "next"), // cycle
        HeapEdge("obj-buf", "obj-buf-shadow", EdgeKind.INTERNAL, "backing"),
        HeapEdge("obj-app", "obj-buf", EdgeKind.STRONG, "io"),
        HeapEdge("obj-hop-x", "obj-multi", EdgeKind.STRONG, "handler"),
        HeapEdge("obj-hop-y", "obj-multi", EdgeKind.STRONG, "handler") // equal-length second root path
    )

    private val rootsA = listOf(
        RootRef("obj-app", "SYSTEM_CLASS"),
        RootRef("obj-hop-x", "THREAD_LOCAL"),
        RootRef("obj-hop-y", "JNI_GLOBAL")
    )

    val snapshotA = HeapSnapshot("heap-a", "hprof-lite/1.4.2", "2026-09-19T09:00:00+09:00",
        classesA, objectsA, edgesA, rootsA,
        listOf(IncompleteChunk("chunk-7", "0x3000", 64, "truncated segment at end of file")))

    /** 局部源数据改动：同一快照身份的修订版，只新增一条边 obj-app -> obj-partial。 */
    val snapshotA2 = HeapSnapshot("heap-a-r2", "hprof-lite/1.4.2", "2026-09-19T09:05:00+09:00",
        classesA, objectsA, edgesA + HeapEdge("obj-app", "obj-partial", EdgeKind.STRONG, "recovered"), rootsA,
        snapshotA.incompleteChunks)

    /** 第二个快照：cls-entry 改名 CacheEntry -> CacheSlot（classId 不变），entry 实例增长。 */
    val snapshotB = HeapSnapshot("heap-b", "hprof-lite/1.4.2", "2026-09-19T15:00:00+09:00",
        classesA.map { if (it.classId == "cls-entry") it.copy(name = "com.example.CacheSlot") else it },
        objectsA + listOf(
            HeapObject("obj-entry-2", "0x1140", "cls-entry", 48, "com.example.Cache.put(Cache.java:91)"),
            HeapObject("obj-entry-3", "0x1160", "cls-entry", 48, "com.example.Cache.put(Cache.java:94)")
        ),
        edgesA + listOf(
            HeapEdge("obj-cache", "obj-entry-2", EdgeKind.WEAK, "entry"),
            HeapEdge("obj-cache", "obj-entry-3", EdgeKind.WEAK, "entry")
        ),
        rootsA, emptyList())

    fun snapshotJson(s: HeapSnapshot): String = json.encodeToString(s)

    /** 把案例包完整重放进 store（仅当 store 为空）。返回是否执行了播种。 */
    fun seed(store: Store, now: () -> String = { java.time.Instant.now().toString() }): Boolean {
        if (store.listSnapshots().isNotEmpty()) return false

        fun importSnap(s: HeapSnapshot, note: String) {
            store.saveSnapshot(s, snapshotJson(s))
            store.appendEvent(DomainEvent("evt-import-" + s.snapshotId, 0, "SNAPSHOT_IMPORTED",
                s.snapshotId, null, note,
                listOf(s.snapshotId),
                mapOf("parserVersion" to s.parserVersion, "capturedAt" to s.capturedAt,
                    "objects" to s.objects.size.toString(), "edges" to s.edges.size.toString(),
                    "roots" to s.roots.size.toString(),
                    "incompleteChunks" to s.incompleteChunks.size.toString()), now()))
        }

        fun runAnalysis(s: HeapSnapshot, params: AnalysisParams, reason: String): AnalysisResult {
            val result = Analysis.analyze(s, params, now())
            val runId = "run-" + s.snapshotId + "-v" + params.version
            store.runs().filter { it.snapshotId == s.snapshotId && it.supersededBy == null }.forEach {
                store.supersedeRun(it.runId, runId, reason)
            }
            store.saveRun(AnalysisRun(runId, s.snapshotId, params.version,
                Analysis.inputDigest(s, params), store.maxSeq(), result.status.toString(), now()))
            store.appendEvents(result.events)
            return result
        }

        // 1) 基线导入：原始 id/地址/类元数据/强弱内部边/root 分类/不完整块/解析器版本全部保留
        importSnap(snapshotA, "baseline import of heap-a; raw layer fully retained")
        importSnap(snapshotB, "second snapshot heap-b for class-group diff")

        // 2) 基线分析 v1：弱引用不保活
        val p1 = AnalysisParams(1, weakKeepAlive = false)
        store.appendEvent(DomainEvent("evt-params-v1", 0, "PARAMS_SET", null, 1,
            "baseline analysis params v1: weak references collected",
            emptyList(), mapOf("version" to "1", "weakKeepAlive" to "false",
                "maxPathCandidates" to "8", "nodeBudget" to "100000"), now()))
        runAnalysis(snapshotA, p1, "initial baseline")

        // 3) 参数修订：弱引用保活 v2
        val p2 = AnalysisParams(2, weakKeepAlive = true)
        store.appendEvent(DomainEvent("evt-params-v2", 0, "PARAMS_SET", null, 2,
            "param revision: weak references keep targets alive",
            listOf("obj-entry"), mapOf("version" to "2", "weakKeepAlive" to "true",
                "maxPathCandidates" to "8", "nodeBudget" to "100000"), now()))
        runAnalysis(snapshotA, p2, "superseded by params v2 (weakKeepAlive=true)")

        // heap-b 用当前参数版本独立分析；它与 heap-a 的改动互不失效
        runAnalysis(snapshotB, p2, "second-snapshot baseline")

        // 4) 局部源数据改动：heap-a-r2 只新增一条边，仅其依赖失效
        importSnap(snapshotA2, "local source change: one recovered edge obj-app -> obj-partial")
        runAnalysis(snapshotA2, p2, "superseded by source revision heap-a-r2")

        // 5) 冲突人工决定：先接受后否定同一泄漏路径候选，两条都保留
        val finding = "finding-leak-obj-ring-a"
        store.appendEvent(DomainEvent("evt-decision-accept-ring", 0, "DECISION", "heap-a-r2", 2,
            "ACCEPT leak candidate obj-ring-a retained via cycle",
            listOf("obj-ring-a", "obj-ring-b"),
            mapOf("target" to finding, "kind" to "ACCEPT",
                "rationale" to "cycle keeps ring alive; treat as cache-like"), now()))
        store.appendEvent(DomainEvent("evt-decision-reject-ring", 0, "DECISION", "heap-a-r2", 2,
            "REJECT leak candidate obj-ring-a after review",
            listOf("obj-ring-a", "obj-ring-b"),
            mapOf("target" to finding, "kind" to "REJECT",
                "rationale" to "ring is bounded by design; not a leak",
                "conflictsWith" to "evt-decision-accept-ring"), now()))
        store.setMeta("casepack", "heap-forensics-v1")
        return true
    }
}
