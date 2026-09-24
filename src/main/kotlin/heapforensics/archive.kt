package heapforensics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Mutation(
    val kind: String,
    val edge: RawEdge? = null,
    val object_: RawObject? = null,
    val weakPolicy: String? = null,
    val snapshotId: String? = null
)

@Serializable
data class Scenario(
    val scenarioId: String,
    val title: String,
    val mutation: JsonMutation,
    val expectedStatus: String,
    val affectedObjects: List<String>,
    val note: String
)

@Serializable
data class JsonMutation(
    val kind: String,
    val edge: RawEdge? = null,
    val `object`: RawObject? = null,
    val weakPolicy: String? = null,
    val snapshotId: String? = null
)

@Serializable
data class ScenarioPack(val archiveId: String, val title: String, val scenarios: List<Scenario>)

private val eventJson = Json

object CasePack {
    private fun res(name: String): String =
        requireNotNull(CasePack::class.java.getResource("/casepack/$name")) { "missing casepack/$name" }.readText()

    fun baseline(): RawSnapshot = snapshotJson.decodeFromString(RawSnapshot.serializer(), res("baseline.heap.json"))
    fun renamed(): RawSnapshot = snapshotJson.decodeFromString(RawSnapshot.serializer(), res("renamed.heap.json"))
    fun scenarios(): ScenarioPack = Json { ignoreUnknownKeys = true }.decodeFromString(ScenarioPack.serializer(), res("scenarios.json"))
}

/** Apply a scenario mutation to a snapshot; each scenario touches exactly one direct source. */
fun applyMutation(base: RawSnapshot, m: JsonMutation): Pair<RawSnapshot, AnalysisParams?> = when (m.kind) {
    "addEdge" -> base.copy(edges = base.edges + requireNotNull(m.edge)) to null
    "addObject" -> base.copy(
        objects = base.objects + requireNotNull(m.`object`),
        edges = if (m.edge != null) base.edges + m.edge else base.edges) to null
    "setWeakPolicy" -> base to AnalysisParams(weakPolicy = requireNotNull(m.weakPolicy))
    "useSnapshot" -> base to null
    else -> throw IllegalArgumentException("unknown mutation ${m.kind}")
}

/**
 * Seed the counterexample archive into an empty store:
 * baseline + one param revision + one local source change + conflicting human decisions.
 * Deterministic timestamps keep export/import round-trips stable.
 */
fun seedArchive(store: Store, baseNow: Long = 1_758_000_000_000L) {
    if (store.getMeta("archiveId") != null) return
    val baseline = CasePack.baseline()
    val renamed = CasePack.renamed()
    val pack = CasePack.scenarios()
    var now = baseNow

    store.setMeta("archiveId", pack.archiveId)
    store.putSnapshot(baseline, now++)
    store.putSnapshot(renamed, now++)

    // Baseline analyses under both explicit weak policies.
    val pIgnore = AnalysisParams(weakPolicy = WeakPolicy.WEAK_IGNORE.id, leakThresholdBytes = 200)
    val pKeep = AnalysisParams(weakPolicy = WeakPolicy.WEAK_KEEP_ALIVE.id, leakThresholdBytes = 200)
    store.putAnalysis("ana-baseline-ignore", baseline.snapshotId, analyze(baseline, pIgnore), now++)
    store.putAnalysis("ana-baseline-keep", baseline.snapshotId, analyze(baseline, pKeep), now++)

    // Scenario replays: each leaves input fragment, verdict status and affected objects.
    for (sc in pack.scenarios) {
        val (mutated, paramOverride) = applyMutation(baseline, sc.mutation)
        if (sc.mutation.kind == "useSnapshot") {
            val diff = diffSnapshots(baseline, renamed)
            store.appendEvent("scenario.replayed", canonicalDigest(renamed), ANALYSIS_VERSION, emptyList(),
                eventJson.encodeToString(mapOf("scenarioId" to sc.scenarioId, "status" to "OK",
                    "affectedObjects" to sc.affectedObjects.joinToString(","),
                    "renamed" to diff.groups.count { it.status == "RENAMED" }.toString())), now++)
            continue
        }
        val params = paramOverride ?: pIgnore
        val outcome = analyze(mutated, params)
        store.putAnalysis("ana-scenario-${sc.scenarioId}", mutated.snapshotId + "@" + sc.scenarioId, outcome, now)
        // Materialize the mutated input fragment as its own snapshot so it stays inspectable.
        if (!store.hasSnapshot(mutated.snapshotId + "@" + sc.scenarioId)) {
            store.putSnapshot(mutated.copy(snapshotId = mutated.snapshotId + "@" + sc.scenarioId), now)
        }
        store.appendEvent("scenario.replayed", canonicalDigest(mutated), params.version, emptyList(),
            eventJson.encodeToString(mapOf(
                "scenarioId" to sc.scenarioId,
                "status" to when (outcome) {
                    is AnalysisOutcome.Ok -> "OK"
                    is AnalysisOutcome.BudgetExhausted -> "BUDGET_EXHAUSTED"
                    is AnalysisOutcome.SemanticallyUnsolvable -> "SEMANTICALLY_UNSOLVABLE"
                },
                "expectedStatus" to sc.expectedStatus,
                "affectedObjects" to sc.affectedObjects.joinToString(","),
                "note" to sc.note)), now++)
    }

    // Class-group diff between the two snapshots, stored as a machine-readable event.
    val diff = diffSnapshots(baseline, renamed)
    store.appendEvent("diff.computed", canonicalDigest(baseline), ANALYSIS_VERSION, emptyList(),
        eventJson.encodeToString(SnapshotDiff.serializer(), diff), now++)

    // Conflicting human decisions on the same leak candidate; both kept, one supersedes.
    store.addDecision(Decision("dec-1", "ana-baseline-ignore", "o5", "ACCEPTED",
        "o5 由 3 条等长 root 路径保活，判定为真实泄漏候选", now++))
    store.addDecision(Decision("dec-2", "ana-baseline-ignore", "o5", "REJECTED",
        "复核后认为 o5 是多 root 共享会话，非泄漏；推翻 dec-1", now++, supersedes = "dec-1"))
    store.addDecision(Decision("dec-3", "ana-baseline-keep", null, "PARAM_ADJUST",
        "弱引用策略显式置为 weak-keep-alive 后 o7 计入保留集", now++))
}
