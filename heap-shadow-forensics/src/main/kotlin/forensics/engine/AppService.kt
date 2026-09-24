package forensics.engine

import forensics.archive.CounterExampleArchive
import forensics.archive.CounterExampleFinding
import forensics.diff.ClassGroupDiff
import forensics.diff.ClassGroupDiffer
import forensics.graph.GraphAnalysis
import forensics.graph.GraphEngine
import forensics.graph.WeakPolicy
import forensics.model.HeapSnapshot
import forensics.parse.*
import forensics.store.LedgerEvent
import forensics.store.Store
import forensics.store.StoredSnapshot
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class IngestReport(
    val snapshotId: String?,
    val accepted: Boolean,
    val outcome: String,
    val diagnostics: List<forensics.model.ParseDiagnostic> = emptyList(),
    val violations: List<forensics.parse.SemanticViolation> = emptyList(),
    val eventId: String? = null,
)

@Serializable
data class DecisionRecord(
    val decisionId: String,
    val verdict: String, // accept | reject | adjust
    val targetEventId: String?,
    val note: String,
    val paramVersion: String?,
    val conflictsWith: List<String>,
)

@Serializable
data class EventView(
    val event: LedgerEvent,
    val snapshotId: String?,
    val role: String?,
    val analysis: GraphAnalysis?,
    val diff: ClassGroupDiff?,
    val finding: CounterExampleFinding?,
    val decision: DecisionRecord?,
)

@Serializable
data class TimelineState(val atSeq: Int, val branch: String, val events: List<EventView>)

class AppService(val store: Store) {

    fun ingest(text: String, role: String = "snapshot", branch: String = "main",
               budget: Budget = Budget(), simulateCrashAfterStage: String? = null): IngestReport {
        val importId = "imp-${UUID.randomUUID()}"
        store.journalStage(importId, "PARSE", "${text.length} bytes")
        if (simulateCrashAfterStage == "PARSE") {
            // process dies before material write; journal survives and is recovered on next open
            return IngestReport(null, false, "SIMULATED_HALF_WRITE")
        }
        return when (val parsed = HsfParser(budget).parse(text)) {
            is ParseOutcome.Corrupt -> {
                store.appendEvent("INGEST_FAILED", "format-corrupt", branch,
                    "parse of $role failed at line ${parsed.diagnostic.position.line}", null, emptyList(),
                    parsed.diagnostic)
                IngestReport(null, false, "FORMAT_CORRUPT", diagnostics = listOf(parsed.diagnostic))
            }
            is ParseOutcome.BudgetExhausted -> {
                store.appendEvent("INGEST_FAILED", "budget-exhausted", branch,
                    parsed.diagnostic.message, null, emptyList(), parsed.diagnostic)
                IngestReport(null, false, "BUDGET_EXHAUSTED", diagnostics = listOf(parsed.diagnostic))
            }
            is ParseOutcome.Ok -> {
                val snap = parsed.snapshot
                when (val sem = IngestSemantics.validate(snap)) {
                    is IngestResult.SemanticImpossible -> {
                        store.appendEvent("INGEST_FAILED", "semantic-impossible", branch,
                            "${sem.violations.size} semantic violations", null, emptyList(), sem.violations)
                        IngestReport(snap.header.snapshotId, false, "SEMANTIC_IMPOSSIBLE",
                            violations = sem.violations)
                    }
                    is IngestResult.Accepted -> {
                        store.journalStage(importId, "MATERIAL", snap.header.snapshotId)
                        if (simulateCrashAfterStage == "MATERIAL") return IngestReport(null, false, "SIMULATED_HALF_WRITE")
                        val ev = store.appendEvent("SNAPSHOT_IMPORTED", "accepted", branch,
                            "snapshot ${snap.header.snapshotId} parser ${snap.header.parserVersion} " +
                                "objects=${snap.objects.size} edges=${snap.edges.size} roots=${snap.roots.size} " +
                                "incomplete=${snap.incompleteBlocks.size} diags=${snap.diagnostics.size}",
                            null, emptyList(),
                            mapOf("snapshotId" to snap.header.snapshotId, "parserVersion" to snap.header.parserVersion,
                                "addressBase" to snap.header.addressBase, "timeBase" to snap.header.timeBase,
                                "unitBase" to snap.header.unitBase))
                        store.putMaterial(StoredSnapshot(
                            snap.header.snapshotId, snap.header.parserVersion, ev.eventId, role,
                            store.json.encodeToString(HeapSnapshot.serializer(), snap)))
                        store.clearJournal(importId)
                        IngestReport(snap.header.snapshotId, true, "ACCEPTED", snap.diagnostics, eventId = ev.eventId)
                    }
                }
            }
        }
    }

    fun analyze(snapshotId: String, policy: WeakPolicy, branch: String = "main",
                parentEventId: String? = null): Pair<LedgerEvent, GraphAnalysis> {
        val stored = requireMaterial(snapshotId)
        val deps = listOf(stored.importEventId)
        val existing = store.eventsUpTo().lastOrNull { it.kind == "ANALYSIS" && it.deps == deps && it.paramVersion == policy.version }
        if (existing != null && parentEventId == null) {
            val json = store.analyses().firstOrNull { it.second == existing.eventId }?.third
            if (json != null) return existing to store.json.decodeFromString(json)
        }
        val analysis = GraphEngine(stored.snapshot, policy).analyze()
        val ev = store.appendEvent("ANALYSIS", "computed", branch,
            "analysis of $snapshotId reachable=${analysis.objects.count { it.keptAlive }} " +
                "weakOnly=${analysis.objects.count { it.reachableOnlyViaWeak }} leaks=${analysis.objects.count { it.leakCandidate }}",
            policy.version, deps,
            mapOf("snapshotId" to snapshotId, "policyVersion" to policy.version),
            parentEventId)
        store.saveAnalysis("an-${snapshotId}-${policy.version}", ev.eventId, snapshotId, policy.version,
            store.json.encodeToString(GraphAnalysis.serializer(), analysis))
        return ev to analysis
    }

    fun runCounterExamples(snapshotId: String, policy: WeakPolicy, branch: String = "main"): List<CounterExampleFinding> {
        val stored = requireMaterial(snapshotId)
        val (baseEvent, baseAnalysis) = analyze(snapshotId, policy, branch)
        val findings = CounterExampleArchive.variants(stored.sourceText).map { v ->
            CounterExampleArchive.runVariant(stored.snapshot, baseAnalysis, v, policy)
        }
        store.appendEvent("COUNTER_EXAMPLE_ARCHIVE", "recorded", branch,
            "${findings.size} single-source variants; adopted=${findings.count { it.adopted }} " +
                "rejected=${findings.count { !it.adopted }}", policy.version, listOf(baseEvent.eventId),
            mapOf("claimDomReachable" to CounterExampleArchive.CLAIM_DOM_REACHABLE,
                "claimTiesKept" to CounterExampleArchive.CLAIM_TIES_KEPT,
                "findings" to findings))
        return findings
    }

    fun diff(snapshotA: String, snapshotB: String, policy: WeakPolicy, branch: String = "main"): ClassGroupDiff {
        val a = requireMaterial(snapshotA); val b = requireMaterial(snapshotB)
        val aa = GraphEngine(a.snapshot, policy).analyze()
        val ba = GraphEngine(b.snapshot, policy).analyze()
        val d = ClassGroupDiffer.diff(a.snapshot, aa, b.snapshot, ba)
        val ev = store.appendEvent("CLASS_GROUP_DIFF", "computed", branch,
            "class groups $snapshotA -> $snapshotB rows=${d.rows.size}", policy.version,
            listOf(a.importEventId, b.importEventId), mapOf("rows" to d.rows.size))
        store.saveDiff("diff-${snapshotA}-${snapshotB}", ev.eventId, snapshotA, snapshotB,
            store.json.encodeToString(ClassGroupDiff.serializer(), d))
        return d
    }

    fun revisePolicy(policy: WeakPolicy, reason: String, branch: String = "main"): LedgerEvent =
        store.appendEvent("PARAM_REVISION", "active", branch,
            "weak policy revision -> ${policy.version} ($reason); re-analysis required, history untouched",
            policy.version, store.eventsUpTo().lastOrNull()?.let { listOf(it.eventId) } ?: emptyList(),
            mapOf("keepWeakAlive" to policy.keepWeakAlive, "reason" to reason))

    fun decide(verdict: String, targetEventId: String?, note: String, branch: String = "main"): DecisionRecord {
        require(verdict in setOf("accept", "reject", "adjust"))
        val params = store.eventsUpTo().lastOrNull { it.kind == "PARAM_REVISION" }?.paramVersion
            ?: WeakPolicy.WEAK_DOES_NOT_KEEP_ALIVE.version
        val conflicts = store.eventsUpTo().mapNotNull { e ->
            if (e.kind == "DECISION") runCatching {
                store.decode<DecisionRecord>(e.payload)
            }.getOrNull()?.takeIf { d ->
                d.verdict != verdict && (d.targetEventId == targetEventId || targetEventId == null)
            }?.decisionId else null
        }
        val record = DecisionRecord("dec-${UUID.randomUUID().toString().take(8)}", verdict, targetEventId,
            note, params, conflicts)
        store.appendEvent("DECISION", if (conflicts.isEmpty()) "active" else "conflicting", branch,
            "manual $verdict${targetEventId?.let { " of $it" } ?: ""}: $note",
            params, listOfNotNull(targetEventId), record)
        return record
    }

    fun timeline(branch: String = "main", atSeq: Int? = null): TimelineState {
        val all = store.eventsUpTo(atSeq).filter { it.branch == branch }
        val views = all.map { ev ->
            val analysis = store.analyses().firstOrNull { it.second == ev.eventId }?.let {
                store.json.decodeFromString<GraphAnalysis>(it.third)
            }
            val diff = store.diffs().firstOrNull { runCatching {
                store.json.decodeFromString<Map<String, String>>(it.second).isNotEmpty()
            }.getOrDefault(false) && store.eventsUpTo().firstOrNull { e ->
                store.diffs().any { d -> d.first == it.first }
            }?.eventId == ev.eventId && store.eventsUpTo(atSeq).any { e ->
                store.diffs().any { d -> d.first.let { id -> true } }
            } }?.let { null } ?: store.diffsForEvent(ev.eventId)
            val payload = ev.payload
            val finding = runCatching {
                val map = store.json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(payload)
                if ("findings" in map) null else null
            }.getOrNull()
            val decision = if (ev.kind == "DECISION") store.decode<DecisionRecord>(ev.payload) else null
            val snapId = if (ev.kind.startsWith("SNAPSHOT"))
                store.decode<Map<String, String>>(ev.payload)["snapshotId"] else null
            EventView(ev, snapId, snapId?.let { store.material(it)?.role }, analysis, diff, null, decision)
        }
        return TimelineState(atSeq ?: (all.lastOrNull()?.seq ?: 0), branch, views)
    }

    private fun requireMaterial(id: String): StoredSnapshot =
        store.material(id) ?: error("snapshot '$id' not found")

    fun snapshots(): List<StoredSnapshot> = store.materials()
}

fun Store.diffsForEvent(eventId: String): ClassGroupDiff? {
    val row = conn.createStatement().executeQuery(
        "SELECT result_json FROM diffs WHERE event_id='$eventId'").use { q ->
        if (q.next()) q.getString(1) else null }
    return row?.let { json.decodeFromString<ClassGroupDiff>(it) }
}
