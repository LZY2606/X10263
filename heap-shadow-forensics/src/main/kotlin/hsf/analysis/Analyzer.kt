package hsf.analysis

import hsf.model.*
import java.security.MessageDigest

class SemanticUnresolved(val diagnostics: List<Diagnostic>) :
    RuntimeException(diagnostics.joinToString("; ") { it.message })

class Analyzer(private val clock: () -> Long = System::currentTimeMillis) {

    /**
     * Compute a derived result from immutable material + params + explicit
     * parent dependency. Deterministic: equivalent inputs yield an identical
     * result id and stable ordering.
     */
    fun analyze(
        snapshots: List<HeapSnapshot>,
        params: AnalysisParams,
        parentResultId: String?,
        validFromEventId: String,
        materialHashes: Map<String, String>
    ): AnalysisResult {
        val semantic = mutableListOf<Diagnostic>()
        for (s in snapshots) {
            val ids = s.objects.map { it.objectId }
            if (ids.size != ids.toSet().size)
                semantic += Diagnostic("semantic.object-id-collision", Severity.ERROR,
                    "snapshot ${s.snapshotId} has duplicate object ids")
            // Two live objects of the same class cannot share one allocation slot
            // record that claims a unique object - a semantic contradiction we
            // refuse to draw over.
        }
        if (semantic.any { it.severity == Severity.ERROR }) throw SemanticUnresolved(semantic)

        val engine = GraphEngine(
            GraphEngine.NodeEdgeBudget(params.maxNodes, params.maxEdges)
        )
        val perSnap = snapshots.map { s ->
            val graph = try { engine.buildGraph(s, params) }
            catch (e: GraphEngine.BudgetExceeded) { throw e }
            val dom = engine.reachableAndDominators(graph)
            Triple(s, graph, dom)
        }

        val objectsOut = mutableListOf<ObjectView>()
        val edgesOut = mutableListOf<EdgeView>()
        val retainedGlobal = HashMap<String, Long?>()
        val graphBySnap = HashMap<String, GraphEngine.Graph>()
        val domBySnap = HashMap<String, GraphEngine.DomResult>()

        for ((s, graph, domResult) in perSnap) {
            graphBySnap[s.snapshotId] = graph
            domBySnap[s.snapshotId] = domResult
            val prefix = s.snapshotId + "#"
            val classById = s.classes.associateBy { it.classId }
            val shallow = HashMap<String, Long?>()
            for (o in s.objects) shallow[prefix + o.objectId] = o.sizeRaw.toLongOrNull()
            val retained = engine.retainedSizes(graph, domResult.idom, domResult.reachable, shallow)
            retainedGlobal += retained
            val rootKinds = s.roots.groupBy({ it.objectId }, { it.kind })
            val siteById = s.allocationSites.associateBy { it.siteId }

            for (o in s.objects.sortedWith(compareBy({ it.classId }, { it.objectId }))) {
                val ident = prefix + o.objectId
                val idx = graph.indexOf[ident]!!
                val idomNode = domResult.idom[idx]
                objectsOut += ObjectView(
                    identity = ident,
                    snapshotId = s.snapshotId,
                    objectId = o.objectId,
                    address = o.addressRaw,
                    classId = o.classId,
                    className = classById[o.classId]?.name ?: "?" + o.classId,
                    size = ValueWithUnit(o.sizeRaw, o.sizeRaw.toLongOrNull(), o.unit, o.sizeRaw.toLongOrNull() != null),
                    reachable = idx in domResult.reachable,
                    domParent = idomNode?.takeIf { it >= 0 }?.let { graph.identities[it] },
                    retained = ValueWithUnit(
                        retained[ident]?.toString() ?: "unconvertible",
                        retained[ident], params.unit, retained[ident] != null
                    ),
                    shallowSizeUnconvertible = o.sizeRaw.toLongOrNull() == null,
                    allocationSite = o.allocationSiteId?.let { siteById[it]?.location },
                    rootKinds = rootKinds[o.objectId] ?: emptyList(),
                    complete = o.complete
                )
            }
            edgesOut += graph.edges.sortedWith(compareBy({ it.from }, { it.to }, { it.identity }))
        }

        val groups = snapshots.flatMap { snap ->
            classGroupsWithRetained(snap, retainedGlobal.filterKeys { it.startsWith(snap.snapshotId + "#") })
        }
        val leaks = if (snapshots.isNotEmpty()) leakCandidates(
            engine, perSnap.first(), retainedGlobal, params
        ) else emptyList()
        val digests = snapshots.map { s ->
            AnalysisInputDigest(
                snapshotId = s.snapshotId,
                materialHash = materialHashes[s.snapshotId]
                    ?: sha256(s.objects.joinToString(",") { it.objectId }),
                objectCount = s.objects.size, edgeCount = s.edges.size, rootCount = s.roots.size
            )
        }
        val resultId = deterministicId(
            "result", params.paramsVersion, digests.joinToString("|") { it.snapshotId + "@" + it.materialHash }
        )
        return AnalysisResult(
            resultId = resultId,
            params = params,
            inputs = digests,
            parentResultId = parentResultId,
            createdAtEpochMillis = clock(),
            // reachable count excludes the virtual root
            reachableCount = perSnap.sumOf { it.third.reachable.size - 1 },
            objects = objectsOut.sortedBy { it.identity },
            edges = edgesOut,
            groups = groups,
            leaks = leaks,
            timeBasis = snapshots.firstNotNullOfOrNull { it.timeBasisRaw }
                ?: snapshots.firstOrNull { it.capturedAtEpochMillis != null }
                    ?.let { "epoch-millis@${it.capturedAtEpochMillis} (basis: ${it.addressBasis})" },
            validity = Validity(validFromEventId),
            diagnostics = snapshots.flatMap { it.diagnostics }
        )
    }

    fun classGroups(s: HeapSnapshot): List<ClassGroup> {
        val prefix = s.snapshotId + "#"
        val byClass = s.objects.groupBy { it.classId }
        return s.classes.sortedBy { it.name }.map { c ->
            val objs = byClass[c.classId] ?: emptyList()
            val shallowSum = objs.sumOfOrNull { it.sizeRaw.toLongOrNull() }
            ClassGroup(
                classId = prefix + c.classId,
                className = c.name,
                renameKey = c.renameKey,
                objectCount = objs.size,
                shallow = ValueWithUnit(
                    shallowSum?.toString() ?: "unconvertible", shallowSum, "byte", shallowSum != null
                ),
                retained = ValueWithUnit("pending", null, "byte", false)
            )
        }
    }

    /** Retained per class needs the computed retained map; recompute cleanly. */
    fun classGroupsWithRetained(s: HeapSnapshot, retained: Map<String, Long?>): List<ClassGroup> {
        val prefix = s.snapshotId + "#"
        return classGroups(s).map { g ->
            val cid = g.classId.removePrefix(prefix)
            val objs = s.objects.filter { it.classId == cid }
            val retainVals = objs.map { retained[prefix + it.objectId] }
            // Sum only when every member is numeric; overlapping dominated
            // subtrees across members still sum here because dominated subtrees
            // of siblings are disjoint.
            val sum = if (retainVals.all { it != null }) retainVals.sumOf { it!! } else null
            g.copy(retained = ValueWithUnit(sum?.toString() ?: "unconvertible", sum, "byte", sum != null))
        }
    }

    private fun leakCandidates(
        engine: GraphEngine,
        first: Triple<HeapSnapshot, GraphEngine.Graph, GraphEngine.DomResult>,
        retained: Map<String, Long?>,
        params: AnalysisParams
    ): List<LeakCandidate> {
        val (s, graph, domResult) = first
        val out = mutableListOf<LeakCandidate>()
        for (o in s.objects) {
            val ident = s.snapshotId + "#" + o.objectId
            val idx = graph.indexOf[ident] ?: continue
            if (idx !in domResult.reachable) continue
            val ret = retained[ident] ?: continue
            val threshold = params.leakMinRetained ?: 0L
            if (ret < threshold) continue
            val paths = engine.shortestRootPaths(graph, idx, domResult.reachable)
            // Candidate heuristic: object survives only under non-GC roots and
            // retains something, or is itself unclassified-rooted.
            val kinds = s.roots.filter { it.objectId == o.objectId }.map { it.kind }.toSet()
            val rootReason = when {
                RootKind.JAVA_LOCAL in kinds || RootKind.NATIVE_STACK in kinds || RootKind.JNI_LOCAL in kinds ->
                    "local/native rooted"
                RootKind.STICKY_CLASS in kinds -> "sticky class"
                RootKind.UNCLASSIFIED in kinds -> "unclassified root"
                else -> "retained via dominator chain"
            }
            out += LeakCandidate(ident, ValueWithUnit(ret.toString(), ret, params.unit, true), paths, rootReason)
        }
        return out.sortedWith(compareByDescending<LeakCandidate> { it.retained.value ?: 0L }.thenBy { it.target })
    }

    /** Class-group diff of two snapshots, surviving class renames. */
    fun diffClassGroups(before: List<ClassGroup>, after: List<ClassGroup>): List<ClassGroupDiff> {
        val keys = LinkedHashSet<String>()
        val byKeyBefore = LinkedHashMap<String, ClassGroup>()
        val byKeyAfter = LinkedHashMap<String, ClassGroup>()
        for (g in before) {
            val k = g.renameKey?.let { "rk:$it" } ?: "id:${g.classId.substringAfter('#')}"
            keys += k; byKeyBefore[k] = g
        }
        for (g in after) {
            val k = g.renameKey?.let { "rk:$it" } ?: "id:${g.classId.substringAfter('#')}"
            keys += k; byKeyAfter[k] = g
        }
        return keys.sorted().mapNotNull { k ->
            val b = byKeyBefore[k]
            val a = byKeyAfter[k]
            val basis = if (k.startsWith("rk:")) "rename-key" else "class-id"
            ClassGroupDiff(
                groupKey = k,
                classNameBefore = b?.className,
                classNameAfter = a?.className,
                renamed = b != null && a != null && b.className != a.className,
                countDelta = when { b == null || a == null -> null else -> a.objectCount - b.objectCount },
                shallowDelta = if (b == null || a == null) null
                    else deltaOrNull(b.shallow.value, a.shallow.value),
                retainedDelta = if (b == null || a == null) null
                    else deltaOrNull(b.retained.value, a.retained.value),
                onlyInBefore = a == null,
                onlyInAfter = b == null,
                matchBasis = basis
            )
        }
    }

    private fun deltaOrNull(a: Long?, b: Long?): Long? = if (a == null || b == null) null else b - a

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun deterministicId(prefix: String, vararg parts: String): String {
        val h = sha256(parts.joinToString("|"))
        return "$prefix-${h.substring(0, 12)}"
    }
}

private fun <T> List<T>.sumOfOrNull(selector: (T) -> Long?): Long? {
    var sum = 0L
    for (e in this) { val v = selector(e) ?: return null; sum += v }
    return sum
}
