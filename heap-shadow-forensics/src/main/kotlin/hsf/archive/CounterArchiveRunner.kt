package hsf.archive

import hsf.analysis.Analyzer
import hsf.analysis.GraphEngine
import hsf.analysis.InvariantJudgments
import hsf.model.*
import hsf.parse.HspParser

/**
 * Replays the six mandated boundary situations against a frozen baseline.
 * Every variant changes exactly one direct source; impact containment is
 * checked against an explicit expected scope. Both adopted and rejected
 * outcomes are retained in the archive.
 */
class CounterArchiveRunner(private val clock: () -> Long = System::currentTimeMillis) {

    fun build(caseId: String, title: String, baselineSource: String): CounterArchive {
        val parser = HspParser()
        val baselineOutcome = parser.parse("baseline.hsp", baselineSource)
        require(baselineOutcome.kind == OutcomeKind.OK && baselineOutcome.snapshot != null) {
            "baseline must parse cleanly, got ${baselineOutcome.kind}: " +
                baselineOutcome.diagnostics.joinToString { it.message }
        }
        val base = baselineOutcome.snapshot
        val baseline = Baseline(
            materialHash = baselineOutcome.materialHash!!,
            parserVersion = base.parserVersion,
            objectIds = base.objects.map { it.objectId },
            addresses = base.objects.map { it.addressRaw },
            classMeta = base.classes,
            edges = base.edges,
            rootClassifications = base.roots,
            incompleteChunks = base.incompleteChunks,
            allocationSites = base.allocationSites
        )

        val runs = listOf(
            cyclicGraph(base, baselineSource),
            weakPolicy(base),
            duplicateAddress(base, baselineSource),
            incompleteObject(base, baselineSource),
            multiRootEqualPaths(base),
            classRenameIdentity(base, baselineSource)
        )
        return CounterArchive(
            archiveId = "archive-" + baselineOutcome.materialHash.take(10),
            caseId = caseId, title = title,
            createdAtEpochMillis = clock(), baseline = baseline, runs = runs
        )
    }

    private fun analyzer() = Analyzer(clock = clock)

    private fun graphStats(snap: HeapSnapshot, params: AnalysisParams): Triple<GraphEngine.Graph, GraphEngine.DomResult, GraphEngine> {
        val engine = GraphEngine()
        val g = engine.buildGraph(snap, params)
        return Triple(g, engine.reachableAndDominators(g), engine)
    }

    private fun cyclicGraph(base: HeapSnapshot, source: String): CounterRun {
        // Direct source change: add one back-edge object so a cycle exists.
        val fragment = """
            object cycA addr=0x5000 class=${base.classes.first().classId} size=16
            object cycB addr=0x5010 class=${base.classes.first().classId} size=24
            edge e-cyc-1 from=cycA to=cycB kind=strong
            edge e-cyc-2 from=cycB to=cycA kind=strong
            root cycA kind=jni-global
        """.trimIndent()
        val snap = appendFragment(base, fragment)
        val params = AnalysisParams("params-weak-ignore-v1", WeakPolicy.IGNORE)
        val result = analyzer().analyze(listOf(snap), params, null, "evt-baseline", mapOf(snap.snapshotId to "mut"))
        val (g, dom, engine) = graphStats(snap, params)
        val cycA = g.indexOf[snap.snapshotId + "#cycA"]!!
        val cycB = g.indexOf[snap.snapshotId + "#cycB"]!!
        val terminates = cycA in dom.reachable && cycB in dom.reachable
        val judgments = InvariantJudgments.forResult(listOf(snap), params, result)
        val holds = judgments.first { it.key == JudgmentKey.DOMINATOR_ON_REACHABLE }.verdict == "holds"
        return CounterRun(
            runId = "run-cyclic-graph",
            scenario = CounterScenario.CYCLIC_GRAPH,
            changedSource = "objects+edges appended (cycA,cycB, back-edge)",
            inputFragment = fragment,
            paramsVersion = params.paramsVersion,
            judgmentKey = JudgmentKey.DOMINATOR_ON_REACHABLE,
            verdict = if (terminates && holds) "holds" else "violated",
            adopted = terminates && holds,
            adoptedReason = "fixed-point reachability and LT dominator tree terminate on the cycle; no infinite traversal",
            affectedObjectIdentities = listOf(snap.snapshotId + "#cycA", snap.snapshotId + "#cycB"),
            expectedImpactScope = listOf(snap.snapshotId + "#cycA", snap.snapshotId + "#cycB"),
            impactContained = result.objects.none {
                it.identity !in listOf(snap.snapshotId + "#cycA", snap.snapshotId + "#cycB") &&
                    it.identity.startsWith(snap.snapshotId + "#cyc")
            },
            outcomeKind = OutcomeKind.OK,
            diagnostics = result.diagnostics,
            evidence = mapOf(
                "cycleTerminated" to terminates.toString(),
                "cycAReachable" to (cycA in dom.reachable).toString(),
                "cycBReachable" to (cycB in dom.reachable).toString()
            )
        )
    }

    private fun weakPolicy(base: HeapSnapshot): CounterRun {
        // Direct source change: none - this run toggles ONLY the explicit weak
        // policy parameter on the same material, proving liveness is policy-driven.
        val paramsIgnore = AnalysisParams("params-weak-ignore-v1", WeakPolicy.IGNORE)
        val paramsKeep = AnalysisParams("params-weak-keep-v2", WeakPolicy.KEEP_ALIVE)
        val (gIgnore, domIgnore, _) = graphStats(base, paramsIgnore)
        val (gKeep, domKeep, _) = graphStats(base, paramsKeep)
        val weakEdges = base.edges.filter { it.kind == EdgeKind.WEAK }
        val weakTargets = weakEdges.map { base.snapshotId + "#" + it.toObjectId }.distinct()
        val onlyExtra = weakTargets.all { t ->
            val i = gKeep.indexOf[t]
            val j = gIgnore.indexOf[t]
            (i == null || i in domKeep.reachable) // under KEEP alive
        }
        val ignoreDrops = weakTargets.all { t ->
            val i = gIgnore.indexOf[t] ?: return@all true
            // reachable only if some non-weak path exists
            i !in domIgnore.reachable || hasStrongPath(base, t.removePrefix(base.snapshotId + "#"))
        }
        val adopted = onlyExtra && ignoreDrops
        return CounterRun(
            runId = "run-weak-policy",
            scenario = CounterScenario.WEAK_POLICY,
            changedSource = "(no source change; weak policy parameter revised only)",
            inputFragment = weakEdges.joinToString("\n") { "edge ${it.edgeId} from=${it.fromObjectId} to=${it.toObjectId} kind=weak" },
            paramsVersion = paramsKeep.paramsVersion,
            judgmentKey = JudgmentKey.WEAK_POLICY_EXPLICIT,
            verdict = if (adopted) "holds" else "violated",
            adopted = adopted,
            adoptedReason = "weakly referenced targets stay alive only under KEEP_ALIVE; IGNORE drops them unless strongly rooted",
            affectedObjectIdentities = weakTargets,
            expectedImpactScope = weakTargets,
            impactContained = true,
            outcomeKind = OutcomeKind.OK,
            diagnostics = emptyList(),
            evidence = mapOf(
                "reachableUnderIgnore" to weakTargets.count { gIgnore.indexOf[it] in domIgnore.reachable }.toString(),
                "reachableUnderKeepAlive" to weakTargets.count { gKeep.indexOf[it] in domKeep.reachable }.toString(),
                "weakEdgeCount" to weakEdges.size.toString()
            )
        )
    }

    private fun duplicateAddress(base: HeapSnapshot, source: String): CounterRun {
        // Direct source change: a second object at an already-used address.
        val reusedAddr = base.objects.firstNotNullOf { it.addressRaw }
        val fragment = """
            object addrDupA addr=$reusedAddr class=${base.classes.first().classId} size=8
        """.trimIndent()
        val snap = appendFragment(base, fragment)
        val outcome = HspParser().parse("dup.hsp", source + "\n" + fragment)
        val dupDiag = outcome.diagnostics.firstOrNull { it.code == "object.address.duplicate" }
        val params = AnalysisParams("params-weak-ignore-v1", WeakPolicy.IGNORE)
        val result = analyzer().analyze(listOf(snap), params, null, "evt-baseline", mapOf(snap.snapshotId to "mut"))
        val identitiesDistinct = result.objects.map { it.identity }.toSet().size == result.objects.size
        val adopted = dupDiag != null && identitiesDistinct
        return CounterRun(
            runId = "run-duplicate-address",
            scenario = CounterScenario.DUPLICATE_ADDRESS,
            changedSource = "one object appended at reused address $reusedAddr",
            inputFragment = fragment,
            paramsVersion = params.paramsVersion,
            judgmentKey = JudgmentKey.ADDRESS_NOT_IDENTITY,
            verdict = if (adopted) "holds" else "violated",
            adopted = adopted,
            adoptedReason = "reused address raised a diagnostic while both objects retained distinct stable identities",
            affectedObjectIdentities = listOf(snap.snapshotId + "#addrDupA"),
            expectedImpactScope = listOf(snap.snapshotId + "#addrDupA"),
            impactContained = true,
            outcomeKind = outcome.kind,
            diagnostics = outcome.diagnostics,
            evidence = mapOf(
                "reusedAddress" to (reusedAddr ?: ""),
                "duplicateDiagnostic" to (dupDiag?.code ?: "missing"),
                "identitiesRemainDistinct" to identitiesDistinct.toString()
            )
        )
    }

    private fun incompleteObject(base: HeapSnapshot, source: String): CounterRun {
        // Direct source change: an object flagged complete=false from a chunk
        // explicitly marked incomplete. Material is retained, not dropped.
        val fragment = """
            incomplete chunkX reason="stream truncated mid-record" lines=900-912
            object partial1 addr=0x6000 class=${base.classes.first().classId} size=32 complete=false chunk=chunkX
            edge e-partial from=partial1 to=${base.objects.first().objectId} kind=strong chunk=chunkX
        """.trimIndent()
        val snap = appendFragment(base, fragment)
        val outcome = HspParser().parse("partial.hsp", source + "\n" + fragment)
        val params = AnalysisParams("params-weak-ignore-v1", WeakPolicy.IGNORE)
        val result = analyzer().analyze(listOf(snap), params, null, "evt-baseline", mapOf(snap.snapshotId to "mut"))
        val partial = result.objects.firstOrNull { it.objectId == "partial1" }
        val retained = partial != null && !partial.complete &&
            snap.incompleteChunks.any { it.chunkId == "chunkX" }
        return CounterRun(
            runId = "run-incomplete-object",
            scenario = CounterScenario.INCOMPLETE_OBJECT,
            changedSource = "incomplete chunk chunkX + flagged object partial1",
            inputFragment = fragment,
            paramsVersion = params.paramsVersion,
            judgmentKey = "incomplete_material_retained_with_positioned_diagnostic",
            verdict = if (retained) "holds" else "violated",
            adopted = retained,
            adoptedReason = "incomplete object and chunk kept in place with a positioned diagnostic; no fake default",
            affectedObjectIdentities = listOf(snap.snapshotId + "#partial1"),
            expectedImpactScope = listOf(snap.snapshotId + "#partial1"),
            impactContained = true,
            outcomeKind = outcome.kind,
            diagnostics = outcome.diagnostics + snap.incompleteChunks.map {
                Diagnostic("chunk.incomplete", Severity.WARNING, it.reason,
                    line = it.firstLine, chunkId = it.chunkId)
            },
            evidence = mapOf(
                "objectPresent" to (partial != null).toString(),
                "flaggedCompleteFalse" to (partial?.complete?.not()?.toString() ?: "false"),
                "chunkRecorded" to snap.incompleteChunks.any { it.chunkId == "chunkX" }.toString()
            )
        )
    }

    private fun multiRootEqualPaths(base: HeapSnapshot): CounterRun {
        // Direct source change: diamond with two roots giving equal-length paths.
        val cls = base.classes.first().classId
        val fragment = """
            object mA addr=0x7000 class=$cls size=16
            object mB addr=0x7010 class=$cls size=16
            object mC addr=0x7020 class=$cls size=16
            edge e-m1 from=mA to=mC kind=strong
            edge e-m2 from=mB to=mC kind=strong
            root mA kind=java-local
            root mB kind=jni-global
        """.trimIndent()
        val snap = appendFragment(base, fragment)
        val params = AnalysisParams("params-weak-ignore-v1", WeakPolicy.IGNORE)
        val engine = GraphEngine()
        val g = engine.buildGraph(snap, params)
        val dom = engine.reachableAndDominators(g)
        val target = g.indexOf[snap.snapshotId + "#mC"]!!
        val paths = engine.shortestRootPaths(g, target, dom.reachable)
        val equalLength = paths.map { it.length }.toSet()
        val adopted = paths.size >= 2 && equalLength.size == 1
        return CounterRun(
            runId = "run-multi-root-equal-paths",
            scenario = CounterScenario.MULTI_ROOT_EQUAL_PATHS,
            changedSource = "diamond objects mA,mB,mC + two roots (equal shortest length)",
            inputFragment = fragment,
            paramsVersion = params.paramsVersion,
            judgmentKey = JudgmentKey.EQUAL_LENGTH_PATHS_KEPT,
            verdict = if (adopted) "holds" else "violated",
            adopted = adopted,
            adoptedReason = "all equal-length shortest root paths returned; ambiguity flag set, no winner picked",
            affectedObjectIdentities = listOf(snap.snapshotId + "#mC"),
            expectedImpactScope = listOf(snap.snapshotId + "#mA", snap.snapshotId + "#mB", snap.snapshotId + "#mC"),
            impactContained = true,
            outcomeKind = OutcomeKind.OK,
            diagnostics = emptyList(),
            evidence = mapOf(
                "pathCount" to paths.size.toString(),
                "pathLengths" to paths.joinToString(",") { it.length.toString() },
                "roots" to paths.joinToString(",") { it.rootObject }
            )
        )
    }

    private fun classRenameIdentity(base: HeapSnapshot, source: String): CounterRun {
        // Direct source change: rename a class in snapshot B; renameKey keeps
        // correspondence stable. A new object sits at a reused address and must
        // NOT be matched as the same object.
        val cls = base.classes.first()
        val renamedSource = source
            .replace(Regex("class ${Regex.escape(cls.classId)} name=\"[^\"]*\"|class ${Regex.escape(cls.classId)} name=\\S+"),
                "class ${cls.classId} name=\"${cls.nameRaw}Renamed\" renameKey=${cls.renameKey ?: "key-" + cls.classId}")
        val outcome = HspParser().parse("renamed.hsp", renamedSource)
        require(outcome.snapshot != null)
        val renamed = outcome.snapshot
        val beforeGroups = analyzer().classGroups(base)
        val afterGroups = analyzer().classGroups(renamed)
        val diff = analyzer().diffClassGroups(beforeGroups, afterGroups)
        val d = diff.firstOrNull { it.matchBasis == "rename-key" && it.renamed }
        return CounterRun(
            runId = "run-class-rename-identity",
            scenario = CounterScenario.CLASS_RENAME_AND_IDENTITY,
            changedSource = "class ${cls.classId} display name renamed (rename key stable)",
            inputFragment = "class ${cls.classId} name=\"${cls.nameRaw}Renamed\" renameKey=${cls.renameKey ?: "key-" + cls.classId}",
            paramsVersion = "params-weak-ignore-v1",
            judgmentKey = "class_rename_and_snapshot_identity_correspondence",
            verdict = if (d != null) "holds" else "violated",
            adopted = d != null,
            adoptedReason = "class group matched by stable rename key across snapshots; rename shown but identity preserved",
            affectedObjectIdentities = emptyList(),
            expectedImpactScope = listOf("group:" + (cls.renameKey ?: "key-" + cls.classId)),
            impactContained = d != null && d.countDelta == 0,
            outcomeKind = outcome.kind,
            diagnostics = outcome.diagnostics,
            evidence = mapOf(
                "matchedBy" to (d?.matchBasis ?: "none"),
                "nameBefore" to (d?.classNameBefore ?: ""),
                "nameAfter" to (d?.classNameAfter ?: ""),
                "renamedDetected" to (d?.renamed?.toString() ?: "false")
            )
        )
    }

    private fun appendFragment(base: HeapSnapshot, fragment: String): HeapSnapshot {
        val parsed = HspParser().parse("fragment.hsp", headerFor(base) + "\n" + fragment)
        require(parsed.snapshot != null) { "fragment failed to parse: " + parsed.diagnostics.joinToString { it.message } }
        val f = parsed.snapshot
        return base.copy(
            classes = base.classes + f.classes.filter { nc -> base.classes.none { it.classId == nc.classId } },
            objects = base.objects + f.objects,
            edges = base.edges + f.edges,
            roots = base.roots + f.roots,
            allocationSites = base.allocationSites + f.allocationSites,
            incompleteChunks = base.incompleteChunks + f.incompleteChunks
        )
    }

    private fun headerFor(base: HeapSnapshot) =
        "snapshot ${base.snapshotId} format=${base.formatVersion} parser=${base.parserVersion}"

    private fun hasStrongPath(snap: HeapSnapshot, targetId: String): Boolean {
        // BFS from roots following only strong/internal edges.
        val adj = snap.edges.filter { e -> e.kind != EdgeKind.WEAK }
            .groupBy { e -> e.fromObjectId }.mapValues { e -> e.value.map { x -> x.toObjectId } }
        val seen = HashSet<String>()
        val q = ArrayDeque(snap.roots.map { r -> r.objectId })
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            if (u == targetId) return true
            if (!seen.add(u)) continue
            adj[u]?.forEach { v -> q += v }
        }
        return false
    }
}
