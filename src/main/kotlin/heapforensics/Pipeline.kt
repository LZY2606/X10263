package heapforensics

import kotlinx.serialization.json.*
import java.util.UUID

/** Shared import/compute pipeline used by both the HTTP layer and tests. */
class Pipeline(val store: Store) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun lastEventId(): List<String> =
        store.listEvents().lastOrNull()?.let { listOf(it.eventId) } ?: emptyList()

    fun addParams(weakPolicy: WeakPolicy, note: String, budgetNodes: Int = 100_000, budgetPaths: Int = 10_000): Params {
        val p = Params((store.listParams().maxOfOrNull { it.version } ?: 0) + 1,
            weakPolicy, budgetNodes, budgetPaths, note)
        store.saveParams(p)
        store.addEvent("param-revision", buildJsonObject {
            put("version", p.version); put("weakPolicy", p.weakPolicy.name)
            put("budgetNodes", p.budgetNodes); put("budgetPaths", p.budgetPaths)
            put("note", p.note)
        }.toString(), lastEventId())
        return p
    }

    fun importSnapshot(snapshotId: String, name: String, text: String): Outcome<SnapshotData> {
        return when (val parsed = Parser.parse(snapshotId, name, text)) {
            is Outcome.CorruptFormat -> {
                store.addEvent("import-failed", buildJsonObject {
                    put("snapshotId", snapshotId)
                    put("diagnostics", JsonArray(parsed.diagnostics.map {
                        buildJsonObject {
                            put("position", it.position); put("severity", it.severity); put("message", it.message)
                        }
                    }))
                }.toString(), lastEventId())
                parsed
            }
            is Outcome.Ok -> {
                store.saveSnapshot(parsed.value)
                store.addEvent("import", buildJsonObject {
                    put("snapshotId", snapshotId); put("name", name)
                    put("parserVersion", parsed.value.parserVersion)
                    put("objects", parsed.value.objects.size); put("edges", parsed.value.edges.size)
                    put("roots", parsed.value.roots.size)
                    put("diagnostics", parsed.value.diagnostics.size)
                }.toString(), lastEventId())
                parsed
            }
            else -> error("unreachable")
        }
    }

    /** Recompute with the latest params. Parent dependency = last event + input digest. */
    fun compute(snapshotId: String): Outcome<AnalysisResult> {
        val snap = store.getSnapshot(snapshotId)
            ?: return Outcome.SemanticUnsolvable("snapshot $snapshotId not found")
        val params = store.latestParams()
            ?: return Outcome.SemanticUnsolvable("no parameter version defined")
        val analysisId = "an-" + UUID.randomUUID().toString().take(8)
        return when (val r = Analysis.compute(analysisId, snap, params)) {
            is Outcome.Ok -> {
                store.saveAnalysis("ok", r.value)
                store.addEvent("compute", buildJsonObject {
                    put("analysisId", r.value.analysisId); put("snapshotId", snapshotId)
                    put("paramsVersion", params.version); put("inputDigest", r.value.inputDigest)
                    put("reachable", r.value.reachable.size); put("unreachable", r.value.unreachable.size)
                    put("judgments", JsonArray(r.value.judgments.map {
                        buildJsonObject {
                            put("rule", it.rule); put("detail", it.detail)
                            put("affected", JsonArray(it.affected.map { a -> JsonPrimitive(a) }))
                        }
                    }))
                }.toString(), lastEventId(), r.value.analysisId)
                r
            }
            is Outcome.ResourceExhausted -> {
                store.recordFailedAnalysis(analysisId, snapshotId, params.version,
                    "resource-exhausted:${r.resource}:${r.limit}")
                store.addEvent("compute-failed", buildJsonObject {
                    put("analysisId", analysisId); put("snapshotId", snapshotId)
                    put("paramsVersion", params.version)
                    put("outcome", "resource-exhausted"); put("resource", r.resource); put("limit", r.limit)
                }.toString(), lastEventId())
                r
            }
            is Outcome.SemanticUnsolvable -> {
                store.recordFailedAnalysis(analysisId, snapshotId, params.version, "semantic-unsolvable")
                store.addEvent("compute-failed", buildJsonObject {
                    put("analysisId", analysisId); put("snapshotId", snapshotId)
                    put("paramsVersion", params.version)
                    put("outcome", "semantic-unsolvable"); put("reason", r.reason)
                }.toString(), lastEventId())
                r
            }
            is Outcome.CorruptFormat -> r
        }
    }

    /** Local source change: re-parse patched text under the SAME snapshot id; only dependent analyses re-run. */
    fun patchSnapshot(snapshotId: String, newText: String, reason: String): Outcome<SnapshotData> {
        val old = store.getSnapshot(snapshotId)
            ?: return Outcome.SemanticUnsolvable("snapshot $snapshotId not found")
        return when (val parsed = Parser.parse(snapshotId, old.name, newText)) {
            is Outcome.Ok -> {
                store.saveSnapshot(parsed.value)
                store.addEvent("source-patch", buildJsonObject {
                    put("snapshotId", snapshotId); put("reason", reason)
                    put("fragment", newText.lines().filter { it.contains("O9") || it.contains("incomplete") }
                        .joinToString(" | ").take(400))
                }.toString(), lastEventId())
                parsed
            }
            is Outcome.CorruptFormat -> parsed
            else -> error("unreachable")
        }
    }

    fun decide(objectId: String, verdict: String, note: String) = store.addDecision(objectId, verdict, note)

    fun diff(snapshotA: String, snapshotB: String): Outcome<List<ClassGroupDiff>> {
        val a = store.getSnapshot(snapshotA) ?: return Outcome.SemanticUnsolvable("snapshot $snapshotA not found")
        val b = store.getSnapshot(snapshotB) ?: return Outcome.SemanticUnsolvable("snapshot $snapshotB not found")
        val d = Analysis.diffClasses(a, b)
        store.addEvent("class-diff", buildJsonObject {
            put("snapshotA", snapshotA); put("snapshotB", snapshotB)
            put("renamed", JsonArray(d.filter { it.renamed }.map { JsonPrimitive(it.classId) }))
            put("groups", d.size)
        }.toString(), lastEventId())
        return Outcome.Ok(d)
    }
}
