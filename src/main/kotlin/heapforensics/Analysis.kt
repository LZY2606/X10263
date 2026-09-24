package heapforensics

import java.security.MessageDigest

sealed interface AnalysisStatus {
    data object Ok : AnalysisStatus
    data class BudgetExhausted(val needed: Int, val limit: Int, val stage: String) : AnalysisStatus
    data class SemanticUnsolvable(val reasons: List<String>) : AnalysisStatus
}

data class AnalysisResult(
    val status: AnalysisStatus,
    val reachable: Set<String> = emptySet(),
    val idom: Map<String, String> = emptyMap(), // child -> parent; "@root" is the virtual super-root
    val retainedBytes: Map<String, Long> = emptyMap(),
    val rootPaths: Map<String, List<List<String>>> = emptyMap(),
    val events: List<DomainEvent> = emptyList(),
    val diagnostics: List<Diagnostic> = emptyList()
)

object Analysis {
    const val SUPER_ROOT = "@root"

    fun sha(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun inputDigest(snapshot: HeapSnapshot, params: AnalysisParams): String =
        sha(snapshot.snapshotId + "|" + snapshot.parserVersion + "|" + params.version + "|" +
            snapshot.objects.joinToString(",") { "${it.objectId}:${it.address}:${it.classId}:${it.sizeBytes}" } + "|" +
            snapshot.edges.joinToString(",") { "${it.fromId}->${it.toId}:${it.kind}" })

    fun analyze(snapshot: HeapSnapshot, params: AnalysisParams, now: String): AnalysisResult {
        if (snapshot.objects.size > params.nodeBudget) {
            return AnalysisResult(AnalysisStatus.BudgetExhausted(snapshot.objects.size, params.nodeBudget, "reachability"))
        }
        val byId = snapshot.objects.associateBy { it.objectId }
        val unknownClass = snapshot.objects.filter { o -> snapshot.classes.none { it.classId == o.classId } }
        if (unknownClass.isNotEmpty()) {
            return AnalysisResult(AnalysisStatus.SemanticUnsolvable(
                unknownClass.map { "object ${it.objectId} references unknown class ${it.classId}" }))
        }

        // Graph edges: strong + internal always; weak only when the explicit policy keeps them alive.
        val liveEdges = snapshot.edges.filter { it.fromId in byId && it.toId in byId }
        val traversalEdges = liveEdges.filter { it.kind != EdgeKind.WEAK || params.weakKeepAlive }
        val adjacency = traversalEdges.groupBy({ it.fromId }, { it.toId })

        // Reachability from roots (deterministic order: sorted).
        val rootIds = snapshot.roots.map { it.objectId }.filter { it in byId }.sorted()
        val reachable = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        rootIds.forEach { if (reachable.add(it)) queue.add(it) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            (adjacency[n] ?: emptyList()).sorted().forEach { m -> if (reachable.add(m)) queue.add(m) }
        }

        val events = mutableListOf<DomainEvent>()
        val diagnostics = mutableListOf<Diagnostic>()
        fun evt(type: String, summary: String, affected: List<String>, attrs: Map<String, String> = emptyMap()) {
            val id = "evt-" + sha(snapshot.snapshotId + "|" + type + "|" + affected.joinToString(",") + "|" + attrs.toSortedMap()).take(12)
            events += DomainEvent(id, 0, type, snapshot.snapshotId, params.version,
                summary, affected.sorted(), attrs.toSortedMap(), now)
        }

        // Weak-reference policy accounting (explicit, machine-readable).
        val weakTargets = liveEdges.filter { it.kind == EdgeKind.WEAK }.map { it.toId }.toSet()
        if (params.weakKeepAlive) {
            if (weakTargets.isNotEmpty()) evt("WEAK_KEPT_ALIVE",
                "weak edges keep targets alive by explicit policy v${params.version}",
                weakTargets.toList(), mapOf("policy" to "KEEP_ALIVE", "paramsVersion" to params.version.toString()))
        } else {
            val collected = weakTargets.filter { it !in reachable }
            if (collected.isNotEmpty()) evt("WEAK_NOT_KEPT",
                "weak-only targets not kept alive by explicit policy v${params.version}",
                collected, mapOf("policy" to "COLLECT", "paramsVersion" to params.version.toString()))
        }

        // Cycles (SCCs of size > 1 or self-loops) within the reachable graph.
        val cycles = scc(reachable, adjacency).filter { it.size > 1 || (it.size == 1 && (adjacency[it.first()] ?: emptyList()).contains(it.first())) }
        cycles.forEach { members ->
            evt("CYCLE_DETECTED", "reference cycle of ${members.size} objects in reachable graph", members)
        }

        // Duplicate addresses: address is NOT a stable identity; objects keep their ids.
        snapshot.objects.groupBy { it.address }.filter { it.value.size > 1 }.forEach { (addr, objs) ->
            evt("DUPLICATE_ADDRESS", "address $addr shared by ${objs.size} objects; identity stays on object ids",
                objs.map { it.objectId }, mapOf("address" to addr))
        }

        // Incomplete objects retained with diagnostics.
        snapshot.objects.filter { it.incomplete }.forEach { o ->
            diagnostics += Diagnostic("objects/${o.objectId}", "INCOMPLETE_OBJECT",
                "partial block at ${o.address}; raw representation retained")
            evt("INCOMPLETE_OBJECT", "object ${o.objectId} is a partial block", listOf(o.objectId),
                mapOf("address" to o.address))
        }

        // Dominator tree on the REACHABLE graph only (Cooper-Harvey-Kennedy).
        val idom = dominators(reachable, adjacency, rootIds)

        // Retained sizes (bytes) over the dominator tree.
        val retained = mutableMapOf<String, Long>()
        val children = idom.entries.groupBy({ it.value }, { it.key })
        fun retainedOf(n: String): Long = retained.getOrPut(n) {
            (byId[n]?.sizeBytes ?: 0) + (children[n] ?: emptyList()).sumOf { retainedOf(it) }
        }
        reachable.forEach { retainedOf(it) }

        // All shortest root paths (equal-length candidates all retained).
        val rootPaths = allShortestRootPaths(reachable, traversalEdges, rootIds, params.maxPathCandidates)
        rootPaths.forEach { (obj, paths) ->
            if (paths.size > 1) evt("MULTI_EQUAL_ROOT_PATHS",
                "object $obj has ${paths.size} equal-length root paths; all candidates retained",
                listOf(obj), mapOf("pathCount" to paths.size.toString(),
                    "candidates" to paths.joinToString(" | ") { it.joinToString(">") }))
        }

        return AnalysisResult(AnalysisStatus.Ok, reachable, idom, retained, rootPaths,
            events.sortedWith(compareBy({ it.type }, { it.affected.firstOrNull() ?: "" }, { it.eventId })),
            diagnostics)
    }

    private fun scc(nodes: Set<String>, adjacency: Map<String, List<String>>): List<List<String>> {
        val index = mutableMapOf<String, Int>(); val low = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>(); val stack = ArrayDeque<String>()
        var counter = 0; val out = mutableListOf<List<String>>()
        fun strongconnect(v: String) {
            index[v] = counter; low[v] = counter; counter++
            stack.addLast(v); onStack.add(v)
            for (w in (adjacency[v] ?: emptyList()).filter { it in nodes }.sorted()) {
                if (w !in index) { strongconnect(w); low[v] = minOf(low[v]!!, low[w]!!) }
                else if (w in onStack) low[v] = minOf(low[v]!!, index[w]!!)
            }
            if (low[v] == index[v]) {
                val comp = mutableListOf<String>()
                while (true) { val w = stack.removeLast(); onStack.remove(w); comp.add(w); if (w == v) break }
                out.add(comp.sorted())
            }
        }
        nodes.sorted().forEach { if (it !in index) strongconnect(it) }
        return out
    }

    private fun dominators(reachable: Set<String>, adjacency: Map<String, List<String>>, roots: List<String>): Map<String, String> {
        // Virtual super-root -> each real root. Postorder numbering from super-root.
        val preds = mutableMapOf<String, MutableList<String>>()
        reachable.forEach { preds[it] = mutableListOf() }
        roots.forEach { preds[it]!!.add(SUPER_ROOT) }
        reachable.forEach { n -> (adjacency[n] ?: emptyList()).forEach { m -> if (m in reachable) preds[m]!!.add(n) } }

        val postorder = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        fun dfs(n: String) {
            if (!visited.add(n)) return
            val succs = if (n == SUPER_ROOT) roots else (adjacency[n] ?: emptyList()).filter { it in reachable }
            succs.sorted().forEach { dfs(it) }
            postorder.add(n)
        }
        dfs(SUPER_ROOT)
        val poIndex = postorder.withIndex().associate { it.value to it.index }
        val idom = mutableMapOf<String, String>()
        idom[SUPER_ROOT] = SUPER_ROOT
        fun intersect(a0: String, b0: String): String {
            var a = a0; var b = b0
            while (a != b) {
                while (poIndex[a]!! < poIndex[b]!!) a = idom[a]!!
                while (poIndex[b]!! < poIndex[a]!!) b = idom[b]!!
            }
            return a
        }
        var changed = true
        val reversePost = postorder.asReversed().filter { it != SUPER_ROOT }
        while (changed) {
            changed = false
            for (n in reversePost) {
                val ps = preds[n]!!.filter { it == SUPER_ROOT || it in idom }
                if (ps.isEmpty()) continue
                var newIdom = ps.first()
                for (p in ps.drop(1)) newIdom = intersect(p, newIdom)
                if (idom[n] != newIdom) { idom[n] = newIdom; changed = true }
            }
        }
        return reachable.associateWith { idom[it] ?: SUPER_ROOT }
    }

    private fun allShortestRootPaths(
        reachable: Set<String>, edges: List<HeapEdge>, roots: List<String>, cap: Int
    ): Map<String, List<List<String>>> {
        val preds = mutableMapOf<String, MutableList<String>>()
        edges.forEach { if (it.fromId in reachable && it.toId in reachable) preds.getOrPut(it.toId) { mutableListOf() }.add(it.fromId) }
        val depth = mutableMapOf<String, Int>()
        val queue = ArrayDeque<String>()
        roots.forEach { depth[it] = 0; queue.add(it) }
        val adjacency = edges.groupBy({ it.fromId }, { it.toId })
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            (adjacency[n] ?: emptyList()).sorted().forEach { m ->
                if (m in reachable && m !in depth) { depth[m] = depth[n]!! + 1; queue.add(m) }
            }
        }
        val result = mutableMapOf<String, List<List<String>>>()
        fun pathsTo(n: String, seen: Set<String>): List<List<String>> {
            if (n in roots) return listOf(listOf(n))
            if (n in seen) return emptyList()
            val d = depth[n] ?: return emptyList()
            val best = (preds[n] ?: emptyList()).filter { depth[it] == d - 1 }.sorted()
            val out = mutableListOf<List<String>>()
            for (p in best) {
                for (sub in pathsTo(p, seen + n)) {
                    out.add(sub + n)
                    if (out.size >= cap) return out
                }
            }
            return out
        }
        reachable.sorted().forEach { n ->
            val paths = pathsTo(n, emptySet())
            if (paths.isNotEmpty()) result[n] = paths
        }
        return result
    }

    /** Class-group diff keyed by stable classId, never by name. */
    fun classGroupDiff(a: HeapSnapshot, b: HeapSnapshot): List<ClassGroupDiff> {
        fun aggregate(s: HeapSnapshot) = s.objects.groupBy { it.classId }
            .mapValues { (_, os) -> os.size.toLong() to os.sumOf { it.sizeBytes } }
        val ga = aggregate(a); val gb = aggregate(b)
        val namesA = a.classes.associate { it.classId to it.name }
        val namesB = b.classes.associate { it.classId to it.name }
        val allIds = (ga.keys + gb.keys).sorted()
        return allIds.map { id ->
            val (ca, ba) = ga[id] ?: (0L to 0L)
            val (cb, bb) = gb[id] ?: (0L to 0L)
            val na = namesA[id]; val nb = namesB[id]
            ClassGroupDiff(id, na, nb, ca, cb, ba, bb,
                renamed = na != null && nb != null && na != nb,
                added = ca == 0L && cb > 0L, removed = ca > 0L && cb == 0L)
        }
    }
}
