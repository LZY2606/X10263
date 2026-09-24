package heapsleuth.analysis

import heapsleuth.model.*

/** One root path: ordered object ids from a root to the target. */
data class RootPath(val rootObjectId: String, val rootType: RootType, val objectIds: List<String>)

data class AnalysisResult(
    val reachable: Set<String>,
    val unreachable: Set<String>,
    val idom: Map<String, String>,          // object -> immediate dominator object id (virtual root = "@root")
    val retainedBytes: Map<String, Long>,   // object -> retained size in bytes (unconvertible sizes count as 0, flagged)
    val rootPaths: Map<String, List<RootPath>>, // object -> ALL shortest root paths (equal length kept)
    val pathsTruncated: Set<String>,
    val judgments: List<JudgmentCode>
)

object GraphEngine {

    /** Deterministic adjacency: edges sorted by (from, kind, name, to). */
    private fun adjacency(snapshot: HeapSnapshot, params: AnalysisParams): Map<String, List<HeapEdge>> {
        val keepWeak = params.weakKeepAlive
        return snapshot.edges
            .filter { e ->
                when (e.kind) {
                    EdgeKind.STRONG -> true
                    EdgeKind.INTERNAL -> params.internalEdgesStrong
                    EdgeKind.WEAK -> keepWeak
                }
            }
            .sortedWith(compareBy({ it.from }, { it.kind.ordinal }, { it.name ?: "" }, { it.to }))
            .groupBy { it.from }
    }

    fun analyze(snapshot: HeapSnapshot, params: AnalysisParams): AnalysisResult {
        val judgments = mutableListOf<JudgmentCode>()
        judgments += JudgmentCode.WEAK_POLICY_EXPLICIT
        judgments += JudgmentCode.DOMINATOR_REACHABLE_ONLY
        val objIds = snapshot.objects.map { it.id }.toSet()
        val adj = adjacency(snapshot, params)
        val liveRoots = snapshot.roots.filter { it.objectId in objIds }

        // --- Reachability (BFS from all roots, deterministic order) ---
        val dist = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        for (r in liveRoots.sortedWith(compareBy({ it.objectId }, { it.rootType.ordinal }))) {
            if (dist.putIfAbsent(r.objectId, 0) == null) queue.addLast(r.objectId)
        }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val d = dist.getValue(n)
            for (e in adj[n].orEmpty()) {
                if (e.to in objIds && dist.putIfAbsent(e.to, d + 1) == null) queue.addLast(e.to)
            }
        }
        val reachable = dist.keys.toSet()
        val unreachable = objIds - reachable

        // --- All shortest root paths (equal-length paths are ALL kept) ---
        judgments += JudgmentCode.EQUAL_LENGTH_PATHS_KEPT
        val rootByObject = liveRoots.groupBy { it.objectId }
        val rootPaths = HashMap<String, MutableList<RootPath>>()
        val truncated = mutableSetOf<String>()
        // predecessors at distance d-1, computed from BFS layering
        val preds = HashMap<String, MutableList<String>>()
        for ((n, d) in dist) {
            if (d == 0) continue
            for ((from, edges) in adj) {
                if (dist[from] == d - 1 && edges.any { it.to == n }) {
                    preds.getOrPut(n) { mutableListOf() }.add(from)
                }
            }
        }
        preds.values.forEach { it.sort() }
        fun enumerate(node: String, acc: MutableList<String>, out: MutableList<RootPath>, budget: IntArray) {
            if (budget[0] <= 0) { truncated += node; return }
            acc.add(node)
            val d = dist.getValue(node)
            if (d == 0) {
                for (r in rootByObject[node].orEmpty().sortedBy { it.rootType.ordinal }) {
                    if (budget[0] <= 0) { truncated += node; break }
                    budget[0]--
                    out += RootPath(node, r.rootType, acc.reversed())
                }
            } else {
                for (p in preds[node].orEmpty()) enumerate(p, acc, out, budget)
            }
            acc.removeAt(acc.size - 1)
        }
        for (n in reachable.sorted()) {
            val budget = intArrayOf(params.maxRootPathsPerObject)
            val out = mutableListOf<RootPath>()
            enumerate(n, mutableListOf(), out, budget)
            rootPaths[n] = out
        }

        // --- Dominator tree on the REACHABLE graph only (virtual super-root) ---
        val vroot = "@root"
        val nodes = (reachable + vroot)
        val succ = HashMap<String, List<String>>()
        succ[vroot] = liveRoots.map { it.objectId }.distinct().sorted().filter { it in reachable }
        for (n in reachable) succ[n] = adj[n].orEmpty().map { it.to }.filter { it in reachable }.distinct().sorted()
        // reverse postorder from vroot
        val rpo = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        fun dfs(n: String) {
            if (!visited.add(n)) return
            for (s in succ[n].orEmpty()) dfs(s)
            rpo += n
        }
        dfs(vroot)
        rpo.reverse()
        val rpoIndex = rpo.withIndex().associate { it.value to it.index }
        val predOf = HashMap<String, MutableList<String>>()
        for ((n, ss) in succ) for (s in ss) predOf.getOrPut(s) { mutableListOf() }.add(n)
        predOf.values.forEach { it.sort() }
        val idom = HashMap<String, String>()
        idom[vroot] = vroot
        fun intersect(a0: String, b0: String): String {
            var a = a0; var b = b0
            while (a != b) {
                while (rpoIndex.getValue(a) > rpoIndex.getValue(b)) a = idom.getValue(a)
                while (rpoIndex.getValue(b) > rpoIndex.getValue(a)) b = idom.getValue(b)
            }
            return a
        }
        var changed = true
        while (changed) {
            changed = false
            for (n in rpo) {
                if (n == vroot) continue
                val ps = predOf[n].orEmpty().filter { it == vroot || idom.containsKey(it) }
                if (ps.isEmpty()) continue
                var newIdom = ps.first()
                for (p in ps.drop(1)) newIdom = intersect(p, newIdom)
                if (idom[n] != newIdom) { idom[n] = newIdom; changed = true }
            }
        }
        idom.remove(vroot)
        val idomOut = reachable.associateWith { idom[it] ?: vroot }

        // --- Retained sizes (bytes) via dominator tree post-order ---
        val sizeOf = snapshot.objects.associate { it.id to (it.sizeBytes ?: 0L) }
        if (snapshot.objects.any { it.sizeBytes == null }) judgments += JudgmentCode.UNCONVERTIBLE_VALUE_KEPT
        val children = HashMap<String, MutableList<String>>()
        for ((n, p) in idomOut) children.getOrPut(p) { mutableListOf() }.add(n)
        children.values.forEach { it.sort() }
        val retained = HashMap<String, Long>()
        fun accumulate(n: String): Long {
            var total = sizeOf[n] ?: 0L
            for (c in children[n].orEmpty()) total += accumulate(c)
            retained[n] = total
            return total
        }
        for (c in children[vroot].orEmpty()) accumulate(c)

        // --- Judgments derived from graph shape ---
        if (snapshot.objects.groupBy { it.address }.any { it.value.size > 1 }) {
            judgments += JudgmentCode.DUPLICATE_ADDRESS
            judgments += JudgmentCode.ADDRESS_NOT_IDENTITY
        }
        if (snapshot.objects.any { it.incomplete }) judgments += JudgmentCode.INCOMPLETE_OBJECT_KEPT
        if (hasCycle(snapshot, reachable)) judgments += JudgmentCode.CYCLE_HANDLED

        return AnalysisResult(
            reachable = reachable,
            unreachable = unreachable,
            idom = idomOut,
            retainedBytes = retained,
            rootPaths = rootPaths,
            pathsTruncated = truncated,
            judgments = judgments.distinct()
        )
    }

    private fun hasCycle(snapshot: HeapSnapshot, reachable: Set<String>): Boolean {
        val adj = snapshot.edges.filter { it.from in reachable && it.to in reachable }
            .groupBy({ it.from }, { it.to })
        val color = HashMap<String, Int>() // 0=unvisited 1=in-stack 2=done
        fun visit(n: String): Boolean {
            when (color[n]) {
                1 -> return true
                2 -> return false
            }
            color[n] = 1
            for (m in adj[n].orEmpty()) if (visit(m)) return true
            color[n] = 2
            return false
        }
        return reachable.any { color[it] == null && visit(it) }
    }
}

/** Class-group diff between two snapshots. Identity across snapshots = classKey, so a
 *  rename is reported as RENAMED (same identity), not as add+remove. */
data class ClassGroup(
    val classKey: String,
    val nameA: String?,
    val nameB: String?,
    val countA: Long,
    val countB: Long,
    val bytesA: Long,
    val bytesB: Long,
    val status: String // UNCHANGED | GROWN | SHRUNK | ADDED | REMOVED | RENAMED
)

object ClassDiff {
    fun diff(a: HeapSnapshot, b: HeapSnapshot): Pair<List<ClassGroup>, List<JudgmentCode>> {
        val judgments = mutableListOf<JudgmentCode>()
        fun group(s: HeapSnapshot): Map<String, Triple<String, Long, Long>> {
            val classById = s.classes.associateBy { it.id }
            val acc = LinkedHashMap<String, Triple<String, Long, Long>>()
            for (o in s.objects) {
                val meta = o.classId?.let { classById[it] }
                val key = meta?.classKey ?: ("<unknown:" + (o.classId ?: "none") + ">")
                val name = meta?.name ?: "<unknown>"
                val prev = acc[key]
                acc[key] = Triple(name, (prev?.second ?: 0) + 1, (prev?.third ?: 0) + (o.sizeBytes ?: 0))
            }
            return acc
        }
        val ga = group(a); val gb = group(b)
        val keys = (ga.keys + gb.keys).toSortedSet()
        val out = mutableListOf<ClassGroup>()
        for (k in keys) {
            val x = ga[k]; val y = gb[k]
            val status = when {
                x == null -> "ADDED"
                y == null -> "REMOVED"
                x.first != y.first -> { judgments += JudgmentCode.CLASS_RENAME_MATCHED; "RENAMED" }
                y.third > x.third -> "GROWN"
                y.third < x.third -> "SHRUNK"
                else -> "UNCHANGED"
            }
            out += ClassGroup(k, x?.first, y?.first, x?.second ?: 0, y?.second ?: 0,
                x?.third ?: 0, y?.third ?: 0, status)
        }
        return out to judgments.distinct()
    }
}
