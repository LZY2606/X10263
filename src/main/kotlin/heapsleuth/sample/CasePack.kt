package heapsleuth.sample

import heapsleuth.events.App
import heapsleuth.events.ImportOutcome
import heapsleuth.model.AnalysisParams
import heapsleuth.model.JudgmentCode

/**
 * 离线案例包「堆影取证室：内存快照、弱引用边与保留路径候选」。
 * Deterministic replay: baseline import -> param revision -> local source change -> conflicting decisions.
 */
object CasePack {

    val baselineJson = """
{
  "format": "heap-sleuth-snapshot",
  "parserVersion": "1.0",
  "snapshotId": "snap-baseline",
  "classes": [
    {"id": "c1", "classKey": "k-cache-node", "name": "com.acme.cache.CacheNode", "superName": "java.lang.Object", "instanceSizeBytes": "64"},
    {"id": "c2", "classKey": "k-cache-entry", "name": "com.acme.cache.CacheEntry", "superName": "java.lang.Object", "instanceSizeBytes": "48"},
    {"id": "c3", "classKey": "k-session", "name": "com.acme.model.UserSession", "superName": "java.lang.Object", "instanceSizeBytes": "96"},
    {"id": "c4", "classKey": "k-listener", "name": "com.acme.OldListener", "superName": "java.lang.Object", "instanceSizeBytes": "32"}
  ],
  "objects": [
    {"id": "o1", "address": "0x1000", "classId": "c1", "sizeBytes": "64", "allocationSite": "com.acme.App.main:42"},
    {"id": "o2", "address": "0x2000", "classId": "c2", "sizeBytes": "48", "allocationSite": "com.acme.cache.Cache.put:87"},
    {"id": "o3", "address": "0x3000", "classId": "c2", "sizeBytes": "48", "allocationSite": "com.acme.cache.Cache.put:91"},
    {"id": "o4", "address": "0x4000", "classId": "c3", "sizeBytes": "96", "allocationSite": "com.acme.web.Login:133"},
    {"id": "o5", "address": "0x5000", "classId": "c3", "sizeBytes": "96", "allocationSite": "com.acme.web.Login:140"},
    {"id": "o6", "address": "0x1000", "classId": "c1", "sizeBytes": "64", "allocationSite": "com.acme.App.main:55"},
    {"id": "o7", "address": "0x7000", "classId": "c4", "sizeBytes": "N/A", "incomplete": true},
    {"id": "o8", "address": "0x8000", "classId": "c2", "sizeBytes": "48"},
    {"id": "o10", "address": "0xA000", "classId": "c1", "sizeBytes": "32"},
    {"id": "o11", "address": "0xB000", "classId": "c1", "sizeBytes": "32"},
    {"id": "o12", "address": "0xC000", "classId": "c2", "sizeBytes": "128", "allocationSite": "com.acme.cache.Cache.<init>:12"}
  ],
  "edges": [
    {"from": "o1", "to": "o2", "kind": "STRONG", "name": "head"},
    {"from": "o2", "to": "o3", "kind": "STRONG", "name": "next"},
    {"from": "o3", "to": "o2", "kind": "STRONG", "name": "prev"},
    {"from": "o1", "to": "o4", "kind": "WEAK", "name": "sessionRef"},
    {"from": "o4", "to": "o5", "kind": "STRONG", "name": "attributes"},
    {"from": "o6", "to": "o7", "kind": "INTERNAL", "name": "listener"},
    {"from": "o10", "to": "o12", "kind": "STRONG", "name": "sharedCache"},
    {"from": "o11", "to": "o12", "kind": "STRONG", "name": "sharedCache"}
  ],
  "roots": [
    {"object": "o1", "rootType": "THREAD_STACK"},
    {"object": "o6", "rootType": "THREAD_OBJECT"},
    {"object": "o10", "rootType": "SYSTEM_CLASS"},
    {"object": "o11", "rootType": "JNI_GLOBAL"}
  ],
  "incompleteBlocks": [
    {"offset": "0xF000", "lengthBytes": "256", "reason": "dump truncated by writer"}
  ]
}
""".trimIndent()

    /** v2: ONLY a local source change — class c4 renamed, same classKey (identity preserved). */
    val renamedJson = baselineJson
        .replace("\"snapshotId\": \"snap-baseline\"", "\"snapshotId\": \"snap-renamed\"")
        .replace("com.acme.OldListener", "com.acme.NewListener")

    val paramsV1 = AnalysisParams(paramVersion = "v1", weakKeepAlive = false)
    val paramsV2 = AnalysisParams(paramVersion = "v2", weakKeepAlive = true)

    fun load(app: App) {
        if (app.db.listSnapshots().isNotEmpty()) return

        // 1. baseline import: raw ids, addresses, class metadata, strong/weak/internal edges,
        //    root classification, incomplete blocks and parser version all preserved
        val imp = app.importSnapshot(baselineJson, paramsV1)
        check(imp is ImportOutcome.Ok) { "case pack baseline must import" }

        // 2. analysis under explicit weak policy v1 (weak refs do NOT keep alive)
        val (r1, _) = app.runAnalysis("snap-baseline", paramsV1)!!

        // 3. parameter revision: weakKeepAlive flipped -> new param version, new event chain
        val revEvent = app.recordEvent("PARAM_REVISION", mapOf(
            "from" to "v1", "to" to "v2", "field" to "weakKeepAlive",
            "reason" to "runtime team policy: weak references keep alive during leak triage"
        ))
        app.runAnalysis("snap-baseline", paramsV2, parents = listOf(revEvent))

        // 4. local source change: class rename only -> class diff must show RENAMED, not add+remove
        app.importSnapshot(renamedJson, paramsV2)
        val (groups, diffJudgments) = app.classDiff("snap-baseline", "snap-renamed")!!
        app.recordJudgmentEvent(JudgmentCode.CLASS_RENAME_MATCHED, mapOf(
            "snapshotA" to "snap-baseline", "snapshotB" to "snap-renamed",
            "renamed" to groups.filter { it.status == "RENAMED" }.joinToString(",") { it.classKey }
        ))

        // 5. machine-readable judgment events for the four tricky rules
        app.recordJudgmentEvent(JudgmentCode.DOMINATOR_REACHABLE_ONLY, mapOf(
            "rule" to "dominator tree is built on the reachable graph only",
            "evidence" to "analysis ${r1.analysisId}: unreachable=${r1.unreachable.joinToString(",")}"
        ))
        app.recordJudgmentEvent(JudgmentCode.WEAK_POLICY_EXPLICIT, mapOf(
            "rule" to "weak-reference liveness is decided by explicit policy, not silently",
            "evidence" to "v1 weakKeepAlive=false -> o4,o5 unreachable; v2 weakKeepAlive=true -> reachable"
        ))
        app.recordJudgmentEvent(JudgmentCode.EQUAL_LENGTH_PATHS_KEPT, mapOf(
            "rule" to "multiple equal-length root paths are all preserved",
            "evidence" to "o12 has 2 shortest paths of length 2 (via o10 SYSTEM_CLASS, via o11 JNI_GLOBAL)"
        ))
        app.recordJudgmentEvent(JudgmentCode.ADDRESS_NOT_IDENTITY, mapOf(
            "rule" to "address is not a stable identity",
            "evidence" to "o1 and o6 share address 0x1000 but keep distinct identities"
        ))

        // 6. conflicting human decisions on leak candidate o2 — both kept, conflict surfaced
        app.decide("o2", "ACCEPT", "chen", "dominator of the o2<->o3 cycle; retained 96B under o1")
        app.decide("o2", "REJECT", "wang", "cycle is bounded by cache capacity; not a leak")
    }
}
