package hsf.analysis

import hsf.model.*

/**
 * Emits machine-readable judgment events for the four tricky invariants.
 * Each verdict carries evidence key/value pairs instead of prose-only logs,
 * and states whether the result was ADOPTED into the visible graph.
 */
object InvariantJudgments {

    data class Judgment(val key: String, val verdict: String, val evidence: Map<String, String>, val adopted: Boolean)

    fun forResult(snapshots: List<HeapSnapshot>, params: AnalysisParams, result: AnalysisResult): List<Judgment> {
        val out = mutableListOf<Judgment>()

        // 1. Dominator tree built only over reachable nodes.
        val total = result.objects.size
        val reachable = result.objects.count { it.reachable }
        val unreachableWithDomParent = result.objects.count { !it.reachable && it.domParent != null }
        out += Judgment(
            JudgmentKey.DOMINATOR_ON_REACHABLE,
            if (unreachableWithDomParent == 0) "holds" else "violated",
            mapOf(
                "totalObjects" to total.toString(),
                "reachableObjects" to reachable.toString(),
                "unreachableWithDominatorParent" to unreachableWithDomParent.toString(),
                "rule" to "dominator tree constructed only over root-reachable subgraph"
            ),
            adopted = unreachableWithDomParent == 0
        )

        // 2. Weak edge liveness is explicit-policy driven.
        val weakTotal = result.edges.count { it.kind == EdgeKind.WEAK }
        val weakParticipating = result.edges.count { it.kind == EdgeKind.WEAK && it.participatesInReachability }
        val policyConsistent = when (params.weakPolicy) {
            WeakPolicy.IGNORE -> weakParticipating == 0
            WeakPolicy.KEEP_ALIVE -> weakParticipating == weakTotal
        }
        out += Judgment(
            JudgmentKey.WEAK_POLICY_EXPLICIT,
            if (policyConsistent) "holds" else "violated",
            mapOf(
                "weakPolicy" to params.weakPolicy.name,
                "paramsVersion" to params.paramsVersion,
                "weakEdges" to weakTotal.toString(),
                "weakEdgesKeepingAlive" to weakParticipating.toString()
            ),
            adopted = policyConsistent
        )

        // 3. Equal-length root paths all retained: check every ambiguous target.
        val engine = GraphEngine()
        var ambiguousTargets = 0
        var tiesKept = 0
        for (snap0 in snapshots) {
            val graph = engine.buildGraph(snap0, params)
            val dom = engine.reachableAndDominators(graph)
            val s = snap0
            for (o in s.objects) {
                val idx = graph.indexOf[s.snapshotId + "#" + o.objectId] ?: continue
                if (idx !in dom.reachable || idx == 0) continue
                val paths = engine.shortestRootPaths(graph, idx, dom.reachable)
                if (paths.size > 1) {
                    ambiguousTargets++
                    if (paths.map { it.length }.toSet().size == 1) tiesKept++
                }
            }
        }
        out += Judgment(
            JudgmentKey.EQUAL_LENGTH_PATHS_KEPT,
            if (ambiguousTargets == tiesKept) "holds" else "violated",
            mapOf(
                "ambiguousTargets" to ambiguousTargets.toString(),
                "targetsWithAllEqualLengthPathsRetained" to tiesKept.toString(),
                "rule" to "no arbitrary winner among equal-length shortest root paths"
            ),
            adopted = ambiguousTargets == tiesKept
        )

        // 4. Addresses are not stable identity.
        val dupAddrs = snapshots.sumOf { s ->
            s.objects.mapNotNull { it.addressRaw }.let { it.size - it.toSet().size }
        }
        val identitiesAreIds = result.objects.all { it.identity.endsWith("#" + it.objectId) }
        out += Judgment(
            JudgmentKey.ADDRESS_NOT_IDENTITY,
            if (identitiesAreIds) "holds" else "violated",
            mapOf(
                "duplicateAddressOccurrences" to dupAddrs.toString(),
                "identityScheme" to "snapshotId#objectId",
                "rule" to "graph identity uses object id; reused addresses remain diagnostics"
            ),
            adopted = identitiesAreIds
        )
        return out
    }
}
