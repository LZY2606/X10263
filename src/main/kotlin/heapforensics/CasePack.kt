package heapforensics

/**
 * 堆影取证室 — the offline case pack. Loads the two bundled heap snapshots and
 *  replays the scripted narrative: baseline import, one parameter revision,
 *  one local source-data change, and one pair of conflicting human decisions.
 *  Every step is recorded as a machine-readable event so reviewers can trace
 *  any final difference back to the material, rule version and decision that
 *  caused it.
 */
object CasePack {
    const val BASELINE = "snap-baseline"
    const val RENAMED = "snap-renamed"
    const val PATCHED = "snap-baseline-patched"

    private fun resource(name: String): String =
        requireNotNull(CasePack::class.java.getResource("/casepack/$name")) { "casepack resource $name missing" }.readText()

    fun seed(lab: Lab) {
        if (lab.store.snapshotIds().isNotEmpty()) return  // already seeded

        // 1. Baseline import: raw ids, addresses, class metadata, strong/weak/
        //    internal edges, root classification, incomplete blocks and parser
        //    version are all preserved.
        lab.importSnapshot(resource("snap-baseline.json"), BASELINE)
        lab.importSnapshot(resource("snap-renamed.json"), RENAMED)
        lab.compute(BASELINE)  // default policy: weak refs COLLECT

        // 2. Parameter revision: weak references now keep targets alive.
        lab.reviseParams(WeakPolicy.KEEP_ALIVE, rationale = "reviewer suspects cache keeps entries alive via weak table")
        lab.compute(BASELINE)

        // 3. Local source-data change: repair the truncated object record in a
        //    derived snapshot version; only dependents of that snapshot change.
        lab.patchSource(BASELINE, PATCHED, "repair truncated obj-trunc shallowSize from vendor tool") { snap ->
            snap.copy(objects = snap.objects.map {
                if (it.objectId == "obj-trunc") it.copy(
                    shallowSize = it.shallowSize.copy(raw = "40", normalized = 40.0, diagnostic = null),
                    incomplete = false,
                ) else it
            })
        }
        lab.compute(PATCHED)

        // 4. Conflicting human decisions on the same leak candidate: both are
        //    retained as events; replay order decides the visible state.
        lab.decide("leak-candidate:obj-leak", "ACCEPT", "retained 4 KiB via weak cache table is a real leak under KEEP_ALIVE")
        lab.decide("leak-candidate:obj-leak", "REJECT", "weak table is cleared before GC in this runtime; not a leak")

        // 5. Cross-snapshot class-group diff (rename detection).
        lab.diff(BASELINE, RENAMED)
    }
}
