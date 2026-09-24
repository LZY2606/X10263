package heaplab.api

import heaplab.analysis.AnalysisOutcome
import heaplab.analysis.Analyzer
import heaplab.model.*
import heaplab.store.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
data class CaseBundle(
    val caseId: String,
    val title: String,
    val timeBase: String,
    val sizeUnitBase: String,
    val snapshots: List<HeapSnapshot>,
    val scenario: List<kotlinx.serialization.json.JsonObject> = emptyList(),
)

class Service(val store: Store) {
    private val json = store.json

    /** Import a snapshot as immutable material. Diagnostics returned; nothing is silently defaulted. */
    fun importSnapshot(s: HeapSnapshot): Pair<heaplab.store.StoredEvent, List<Diagnostic>> {
        val (corrupt, semantic) = Analyzer.validate(s)
        store.putSnapshot(s)
        val evt = store.appendEvent(
            "snapshot.imported",
            json.encodeToString(HeapSnapshot.serializer(), s),
            store.snapshotDigest(s),
            "n/a",
            store.listEvents().lastOrNull()?.eventId,
        )
        store.setCurrentEvent(evt.eventId)
        return evt to (corrupt + semantic)
    }

    fun runAnalysis(snapshotId: String, params: AnalysisParams): Pair<AnalysisOutcome, heaplab.store.StoredEvent?> {
        val s = store.getSnapshot(snapshotId)
            ?: return AnalysisOutcome.CorruptFormat(listOf(
                Diagnostic("snapshotId", "SNAPSHOT_NOT_FOUND", "no snapshot '$snapshotId'"))) to null
        val digest = store.snapshotDigest(s)
        val outcome = Analyzer.analyze(s, params, digest)
        val kind = outcome::class.simpleName!!
        val analysisId = "ana-" + store.sha256("$snapshotId|${params.version}|${params.weakPolicy}|$digest").take(12)
        val parent = store.listEvents().lastOrNull()?.eventId
        store.putAnalysis(StoredAnalysis(analysisId, snapshotId, params, digest, parent,
            json.encodeToString(AnalysisOutcome.serializer(), outcome), kind))
        val evt = store.appendEvent(
            "analysis.completed",
            json.encodeToString(AnalysisOutcome.serializer(), outcome),
            digest, params.version, parent)
        store.setCurrentEvent(evt.eventId)
        return outcome to evt
    }

    fun decide(analysisEventId: String, targetObjectId: String, verdict: String, rationale: String): Decision {
        val id = "dec-" + store.sha256("$analysisEventId|$targetObjectId|$verdict|$rationale|${Instant.now()}").take(12)
        val d = Decision(id, analysisEventId, targetObjectId, verdict, rationale, Instant.now().toString())
        store.putDecision(d)
        store.appendEvent("decision.${verdict.lowercase()}", json.encodeToString(Decision.serializer(), d),
            store.getEvent(analysisEventId)?.inputDigest ?: "", "n/a", analysisEventId)
        return d
    }

    /** Revert: point the visible view back at a historical event — history itself is never overwritten. */
    fun revertTo(eventId: String): Boolean {
        val evt = store.getEvent(eventId) ?: return false
        if (evt.status != "committed") return false
        store.setCurrentEvent(eventId)
        store.appendEvent("view.reverted", """{"to":"$eventId"}""", evt.inputDigest, evt.paramsVersion, eventId)
        return true
    }

    fun seedCase(bundle: CaseBundle) {
        if (store.listSnapshots().isNotEmpty()) return
        bundle.snapshots.forEach { importSnapshot(it) }
    }

    fun loadCaseResource(): CaseBundle {
        val text = javaClass.getResource("/cases/heap-forensics-lab/case.json")!!.readText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(CaseBundle.serializer(), text)
    }
}
