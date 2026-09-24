package heapforensics

import java.security.MessageDigest

/**
 * Derived layer. All business rules live here on the server side and are
 * directly callable from tests:
 *  - dominator tree is built ONLY on the reachable graph
 *  - weak references keep targets alive iff params.weakPolicy == KEEP_ALIVE
 *  - ALL equal-length shortest root paths are retained
 *  - address is never used as identity; objectId is the stable identity
 *  - equivalent inputs produce a stable, sorted output order
 */
object Analyzer {

    fun digest(snapshot: HeapSnapshot): String {
        val md = MessageDigest.getInstance("SHA-256")
        val sb = StringBuilder()
        sb.append(snapshot.snapshotId).append('|').append(snapshot.parser).append('|')
        snapshot.classes.sortedBy { it.classId }.forEach { sb.append(it).append(';') }
        snapshot.objects.sortedBy { it.objectId }.forEach { sb.append(it).append(';') }
        snapshot.edges.sortedWith(compareBy({ it.fromId }, { it.toId }, { it.kind }, { it.label })).forEach { sb.append(it).append(';') }
        snapshot.roots.sortedWith(compareBy({ it.objectId }, { it.kind })).forEach { sb.append(it).append(';') }
        snapshot.incompleteBlocks.sortedBy { it.offset }.forEach { sb.append(it).append(';') }
        md.update(sb.toString().toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun analyze(
        snapshot: HeapSnapshot,
        params: ParamSet,
        parentEventIds: List<Long> = emptyList(),
    ): AnalysisOutcome<AnalysisResult> {
        if (snapshot.objects.size > params.nodeBudget) {
            return AnalysisOutcome.Failed(
                FailureKind.BUDGET_EXHAUSTED,
                listOf(Diagnostic("$", "node budget ${params.nodeBudget} exhausted by ${snapshot.objects.size} objects"))
            )
        }
        val diags = mutableListOf<Diagnostic>()
        val ids = snapshot.objects.map { it.objectId }.sorted()
        val index = ids.withIndex().associate { it.value to it.index }
        val n = ids.size

        // Adjacency: strong + internal always; weak only under KEEP_ALIVE policy.
        val adj = Array(n) { mutableListOf<Int>() }
        val radj = Array(n) { mutableListOf<Int>() }
        for (e in snapshot.edges) {
            val keep = when (e.kind) {
                EdgeKind.STRONG, EdgeKind.INTERNAL -> true
                EdgeKind.WEAK -> params.weakPolicy == WeakPolicy.KEEP_ALIVE
            }
            if (!keep) continue
            val a = index[e.fromId] ?: continue
            val b = index[e.toId] ?: continue
            adj[a].add(b); radj[b].add(a)
        }
        adj.forEach { it.sort() }; radj.forEach { it.sort() }

        // Reachability from roots (sorted for determinism).
        val reachable = BooleanArray(n)
        val rootIdx = snapshot.roots.mapNotNull { index[it.objectId] }.sorted()
        val stack = ArrayDeque<Int>()
        rootIdx.forEach { if (!reachable[it]) { reachable[it] = true; stack.add(it) } }
        while (stack.isNotEmpty()) {
            val u = stack.removeLast()
            for (v in adj[u]) if (!reachable[v]) { reachable[v] = true; stack.add(v) }
        }

        // BFS depths from the virtual super-root over the reachable graph.
        val depth = IntArray(n) { -1 }
        val bfs = ArrayDeque<Int>()
        rootIdx.forEach { depth[it] = 0; bfs.add(it) }
        while (bfs.isNotEmpty()) {
            val u = bfs.removeFirst()
            for (v in adj[u]) if (reachable[v] && depth[v] < 0) { depth[v] = depth[u] + 1; bfs.add(v) }
        }

        // Dominator tree on the reachable subgraph only (Cooper-Harvey-Kennedy).
        val idom = IntArray(n) { -1 }
        // immediate dominator of a root is itself
        rootIdx.forEach { idom[it] = it }
        var changed = true
        while (changed) {
            changed = false
            // process in BFS order for fast convergence; roots first
            for (u in 0 until n) {
                if (!reachable[u] || depth[u] <= 0) continue
                var newIdom = -1
                for (p in radj[u]) {
                    if (!reachable[p] || idom[p] < 0) continue
                    newIdom = if (newIdom < 0) p else intersect(idom, depth, p, newIdom)
                }
                if (newIdom >= 0 && idom[u] != newIdom) { idom[u] = newIdom; changed = true }
            }
        }

        // Retained sizes: shallow size + everything dominated. Unconvertible
        // sizes stay raw and contribute 0 with a diagnostic already attached.
        val shallow = LongArray(n) { i ->
            snapshot.objects.first { it.objectId == ids[i] }.shallowSize.normalized?.toLong() ?: 0L
        }
        val domChildren = Array(n) { mutableListOf<Int>() }
        for (u in 0 until n) if (reachable[u] && idom[u] != u && idom[u] >= 0) domChildren[idom[u]].add(u)
        val retained = LongArray(n)
        fun dfsRetained(u: Int): Long {
            var sum = shallow[u]
            for (c in domChildren[u]) sum += dfsRetained(c)
            retained[u] = sum
            return sum
        }
        rootIdx.forEach { dfsRetained(it) }

        // ALL shortest equal-length root paths, budget-capped. Cycles are cut
        // by shortest-path layering (a shortest path never revisits a node).
        val rootByObj = snapshot.roots.groupBy { it.objectId }
        val rootPaths = HashMap<Int, MutableList<RootPath>>()
        var pathCount = 0
        var budgetHit = false
        // parents at previous BFS layer: predecessor p of u with depth[p]==depth[u]-1
        val prevLayer = Array(n) { mutableListOf<Int>() }
        for (u in 0 until n) if (reachable[u] && depth[u] > 0)
            for (p in radj[u]) if (reachable[p] && depth[p] == depth[u] - 1) prevLayer[u].add(p)
        for (u in 0 until n) {
            if (!reachable[u]) continue
            val paths = mutableListOf<List<Int>>()
            if (depth[u] == 0) {
                paths.add(listOf(u))
            } else {
                val frontier = ArrayDeque<Pair<Int, List<Int>>>()
                frontier.add(u to listOf(u))
                while (frontier.isNotEmpty()) {
                    val (cur, suffix) = frontier.removeFirst()
                    if (depth[cur] == 0) { paths.add(suffix); continue }
                    for (p in prevLayer[cur]) frontier.add(p to suffix + p)
                    if (paths.size + frontier.size > params.pathBudget) { budgetHit = true; break }
                }
            }
            pathCount += paths.size
            if (pathCount > params.pathBudget) budgetHit = true
            val out = paths.map { hops ->
                val rootId = ids[hops.last()]
                val root = rootByObj[rootId]?.firstOrNull()
                RootPath(
                    rootDescription = root?.description ?: rootId,
                    rootKind = root?.kind ?: RootKind.OTHER,
                    hops = hops.reversed().map { ids[it] },
                )
            }.sortedBy { it.hops.joinToString("/") }
            if (out.isNotEmpty()) rootPaths[u] = out.toMutableList()
        }
        if (budgetHit) {
            return AnalysisOutcome.Failed(
                FailureKind.BUDGET_EXHAUSTED,
                listOf(Diagnostic("$.rootPaths", "path budget ${params.pathBudget} exhausted while enumerating equal-length root paths"))
            )
        }

        val objAnalyses = ids.mapIndexed { i, id ->
            ObjectAnalysis(
                objectId = id,
                reachable = reachable[i],
                dominatorParent = if (reachable[i] && depth[i] > 0 && idom[i] >= 0) ids[idom[i]] else null,
                retainedBytes = if (reachable[i]) retained[i] else 0L,
                rootPaths = rootPaths[i]?.toList() ?: emptyList(),
            )
        }

        val classById = snapshot.classes.associateBy { it.classId }
        val objById = snapshot.objects.associateBy { it.objectId }
        val classGroups = snapshot.objects.groupBy { it.classId }.map { (cid, objs) ->
            ClassGroup(
                classId = cid,
                name = classById[cid]?.name ?: cid,
                instanceCount = objs.size,
                shallowBytes = objs.sumOf { it.shallowSize.normalized?.toLong() ?: 0L },
                retainedBytes = objs.sumOf { o -> objAnalyses.first { it.objectId == o.objectId }.retainedBytes },
            )
        }.sortedBy { it.classId }

        return AnalysisOutcome.Ok(
            AnalysisResult(
                snapshotId = snapshot.snapshotId,
                paramVersion = params.version,
                weakPolicy = params.weakPolicy,
                inputDigest = digest(snapshot),
                parentEventIds = parentEventIds,
                objects = objAnalyses,
                classGroups = classGroups,
                diagnostics = diags + snapshot.diagnostics,
            )
        )
    }

    private fun intersect(idom: IntArray, depth: IntArray, a0: Int, b0: Int): Int {
        var a = a0; var b = b0
        while (a != b) {
            while (depth[a] > depth[b]) a = idom[a]
            while (depth[b] > depth[a]) b = idom[b]
            if (a == b) break
            if (a == idom[a] || b == idom[b]) break
            a = idom[a]; b = idom[b]
        }
        return a
    }

    /** Class-group diff between two snapshots. Class identity is classId, so a
     *  rename shows up as nameA != nameB with renamed=true rather than as an
     *  add+remove pair. */
    fun diffClassGroups(a: AnalysisResult, b: AnalysisResult): List<ClassGroupDiff> {
        val ga = a.classGroups.associateBy { it.classId }
        val gb = b.classGroups.associateBy { it.classId }
        return (ga.keys + gb.keys).sorted().map { cid ->
            val x = ga[cid]; val y = gb[cid]
            ClassGroupDiff(
                classId = cid,
                nameA = x?.name, nameB = y?.name,
                renamed = x != null && y != null && x.name != y.name,
                countA = x?.instanceCount ?: 0, countB = y?.instanceCount ?: 0,
                retainedA = x?.retainedBytes ?: 0, retainedB = y?.retainedBytes ?: 0,
            )
        }
    }
}
