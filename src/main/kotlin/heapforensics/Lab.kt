package heapforensics

import kotlinx.serialization.json.*

/**
 * The lab service: immutable materials (snapshots) and mutable decisions are
 * layered; every computation records input digest, parameter version and
 * parent dependencies. Replaying to an old event restores the parameter
 * version and visible conclusions of that moment instead of overwriting
 * history with the latest computation.
 */
class Lab(val store: Store) {

    fun events(): List<Event> = store.events()

    fun currentParamVersion(atEvent: Long? = null): ParamSet {
        var params = ParamSet(version = 1, weakPolicy = WeakPolicy.COLLECT)
        for (e in store.events()) {
            if (atEvent != null && e.id > atEvent) break
            if (e.type == EventType.PARAM_REVISION) {
                params = ParamSet(
                    version = e.payload["version"]!!.jsonPrimitive.int,
                    weakPolicy = WeakPolicy.valueOf(e.payload["weakPolicy"]!!.jsonPrimitive.content),
                    pathBudget = e.payload["pathBudget"]?.jsonPrimitive?.intOrNull ?: 10_000,
                    nodeBudget = e.payload["nodeBudget"]?.jsonPrimitive?.intOrNull ?: 1_000_000,
                )
            }
        }
        return params
    }

    fun importSnapshot(jsonText: String, snapshotId: String, budget: Importer.Budget = Importer.Budget()): AnalysisOutcome<HeapSnapshot> {
        return when (val r = Importer.import(jsonText, snapshotId, budget)) {
            is AnalysisOutcome.Failed -> r
            is AnalysisOutcome.Ok -> {
                store.saveSnapshot(r.value)
                store.appendEvent(EventType.IMPORT_SNAPSHOT, buildJsonObject {
                    put("snapshotId", snapshotId)
                    put("objects", r.value.objects.size)
                    put("edges", r.value.edges.size)
                    put("roots", r.value.roots.size)
                    put("parser", "${r.value.parser.name}@${r.value.parser.version}")
                    put("diagnostics", r.value.diagnostics.size)
                })
                r
            }
        }
    }

    fun reviseParams(weakPolicy: WeakPolicy, pathBudget: Int = 10_000, nodeBudget: Int = 1_000_000, rationale: String = ""): Event {
        val next = currentParamVersion().version + 1
        return store.appendEvent(EventType.PARAM_REVISION, buildJsonObject {
            put("version", next)
            put("weakPolicy", weakPolicy.name)
            put("pathBudget", pathBudget)
            put("nodeBudget", nodeBudget)
            put("rationale", rationale)
        })
    }

    /** A local source-data change: produces a NEW snapshot version derived
     *  from an existing one; only dependents of that snapshot are affected. */
    fun patchSource(snapshotId: String, newSnapshotId: String, description: String, transform: (HeapSnapshot) -> HeapSnapshot): AnalysisOutcome<HeapSnapshot> {
        val base = store.loadSnapshot(snapshotId)
            ?: return AnalysisOutcome.Failed(FailureKind.SEMANTIC_UNSOLVABLE, listOf(Diagnostic("$", "snapshot '$snapshotId' not found")))
        val patched = transform(base).copy(snapshotId = newSnapshotId)
        store.saveSnapshot(patched)
        store.appendEvent(EventType.SOURCE_PATCH, buildJsonObject {
            put("baseSnapshotId", snapshotId)
            put("newSnapshotId", newSnapshotId)
            put("description", description)
        })
        return AnalysisOutcome.Ok(patched)
    }

    /** Human decision. Conflicting decisions are ALL retained as events;
     *  the visible state is derived by replay order, never by deletion. */
    fun decide(subject: String, verdict: String, rationale: String): Event =
        store.appendEvent(EventType.DECISION, buildJsonObject {
            put("subject", subject)
            put("verdict", verdict)
            put("rationale", rationale)
        })

    fun compute(snapshotId: String, atEvent: Long? = null): AnalysisOutcome<AnalysisResult> {
        val snapshot = store.loadSnapshot(snapshotId)
            ?: return AnalysisOutcome.Failed(FailureKind.SEMANTIC_UNSOLVABLE, listOf(Diagnostic("$", "snapshot '$snapshotId' not found")))
        val params = currentParamVersion(atEvent)
        store.loadResult(snapshotId, params.version)?.let { cached ->
            if (cached.inputDigest == Analyzer.digest(snapshot)) return AnalysisOutcome.Ok(cached)
        }
        val parents = store.events().filter { atEvent == null || it.id <= atEvent }.map { it.id }
        return when (val r = Analyzer.analyze(snapshot, params, parents)) {
            is AnalysisOutcome.Failed -> r
            is AnalysisOutcome.Ok -> {
                val ev = store.appendEvent(EventType.COMPUTED, buildJsonObject {
                    put("snapshotId", snapshotId)
                    put("paramVersion", params.version)
                    put("weakPolicy", params.weakPolicy.name)
                    put("inputDigest", r.value.inputDigest)
                    putJsonArray("parentEventIds") { parents.forEach { add(it) } }
                })
                store.saveResult(r.value, ev.id)
                AnalysisOutcome.Ok(r.value)
            }
        }
    }

    /** Visible state as of an event: the parameter version, snapshots,
     *  decisions and computed conclusions visible at that moment. */
    fun stateAt(eventId: Long?): JsonObject {
        val evs = store.events().filter { eventId == null || it.id <= eventId }
        val params = currentParamVersion(eventId)
        val decisions = evs.filter { it.type == EventType.DECISION }.map {
            buildJsonObject {
                put("eventId", it.id)
                put("subject", it.payload["subject"]!!.jsonPrimitive.content)
                put("verdict", it.payload["verdict"]!!.jsonPrimitive.content)
                put("rationale", it.payload["rationale"]!!.jsonPrimitive.content)
            }
        }
        val computed = evs.filter { it.type == EventType.COMPUTED }.map {
            buildJsonObject {
                put("eventId", it.id)
                put("snapshotId", it.payload["snapshotId"]!!.jsonPrimitive.content)
                put("paramVersion", it.payload["paramVersion"]!!.jsonPrimitive.int)
                put("inputDigest", it.payload["inputDigest"]!!.jsonPrimitive.content)
            }
        }
        return buildJsonObject {
            put("asOfEvent", eventId ?: (evs.lastOrNull()?.id ?: 0))
            put("paramVersion", params.version)
            put("weakPolicy", params.weakPolicy.name)
            putJsonArray("snapshots") { store.snapshotIds().forEach { add(it) } }
            putJsonArray("decisions") { decisions.forEach { add(it) } }
            putJsonArray("computed") { computed.forEach { add(it) } }
        }
    }

    fun diff(snapshotA: String, snapshotB: String, atEvent: Long? = null): AnalysisOutcome<List<ClassGroupDiff>> {
        val a = compute(snapshotA, atEvent)
        val b = compute(snapshotB, atEvent)
        if (a is AnalysisOutcome.Failed) return a
        if (b is AnalysisOutcome.Failed) return b
        return AnalysisOutcome.Ok(Analyzer.diffClassGroups((a as AnalysisOutcome.Ok).value, (b as AnalysisOutcome.Ok).value))
    }
}
