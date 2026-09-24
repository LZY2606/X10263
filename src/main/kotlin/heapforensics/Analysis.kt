package heapforensics

import java.security.MessageDigest

object Analysis {

    const val VIRTUAL_ROOT = "<root>"

    fun digestOf(s: SnapshotData, p: Params): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(s.rawText.toByteArray())
        md.update("${p.version}|${p.weakPolicy}|${p.budgetNodes}|${p.budgetPaths}".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Dominator tree is built on the REACHABLE graph only. Weak edges keep
     * objects alive only when the explicit policy says so. All equal-length
     * shortest root paths are kept. Address is never used as identity.
     */
    fun compute(analysisId: String, s: SnapshotData, p: Params): Outcome<AnalysisResult> {
        val objIds = s.objects.map { it.objectId }
        val byId = s.objects.associateBy { it.objectId }
        val judgments = mutableListOf<Judgment>()

        // duplicate addresses -> identity warning (address is not a stable identity)
        s.objects.groupBy { it.address }.filter { it.value.size > 1 }.forEach { (addr, objs) ->
            judgments += Judgment(
                "address-not-stable-identity",
                "address $addr shared by ${objs.size} objects; identity stays (snapshot,objectId)",
                objs.map { it.objectId }.sorted())
        }

        // edges that keep alive under the explicit weak policy
        fun EdgeRec.retains(): Boolean = when (kind) {
            EdgeKind.STRONG, EdgeKind.INTERNAL -> true
            EdgeKind.WEAK -> p.weakPolicy == WeakPolicy.RETAIN_ALL
        }
        val liveEdges = s.edges.filter { it.retains() }
        val weakEdges = s.edges.filter { it.kind == EdgeKind.WEAK }
        if (weakEdges.isNotEmpty())
            judgments += Judgment("weak-policy-explicit",
                "weak edges ${if (p.weakPolicy == WeakPolicy.RETAIN_ALL) "retain" else "do not retain"} " +
                    "per params v${p.version} (${p.weakPolicy})",
                weakEdges.map { "${it.fromId}->${it.toId}" }.sorted())

        if (s.roots.isEmpty())
            return Outcome.SemanticUnsolvable(
                "no GC roots defined: dominator tree undefined on empty reachable set",
                objIds.sorted())

        // reachability (multi-source BFS), stable order
        val adj = liveEdges.groupBy({ it.fromId }, { it.toId })
        val reachable = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        s.roots.map { it.objectId }.distinct().sorted().forEach { r ->
            if (byId.containsKey(r) && reachable.add(r)) queue.add(r)
        }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            (adj[n] ?: emptyList()).sorted().forEach { m ->
                if (byId.containsKey(m) && reachable.add(m)) queue.add(m)
            }
        }
        if (reachable.size > p.budgetNodes)
            return Outcome.ResourceExhausted("reachable-nodes", p.budgetNodes)

        val unreachable = (objIds.toSet() - reachable).sorted()
        judgments += Judgment("dominator-on-reachable-only",
            "dominator tree built on ${reachable.size} reachable objects; " +
                "${unreachable.size} unreachable excluded", unreachable)

        // --- dominators on reachable graph (iterative dataflow, cycle-safe) ---
        val roots = s.roots.map { it.objectId }.distinct().filter { reachable.contains(it) }.sorted()
        val preds = mutableMapOf<String, MutableList<String>>()
        liveEdges.forEach { e ->
            if (reachable.contains(e.fromId) && reachable.contains(e.toId))
                preds.getOrPut(e.toId) { mutableListOf() }.add(e.fromId)
        }
        val dom = mutableMapOf<String, MutableSet<String>>()
        roots.forEach { dom[it] = mutableSetOf(it) }          // virtual root implicit
        reachable.filter { it !in roots }.forEach { dom[it] = reachable.toMutableSet() }
        var changed = true
        var rounds = 0
        while (changed) {
            changed = false
            if (++rounds > p.budgetNodes) return Outcome.ResourceExhausted("dominator-rounds", p.budgetNodes)
            for (n in reachable.sorted()) {
                if (n in roots) continue
                val ps = (preds[n] ?: emptyList()).filter { reachable.contains(it) }
                if (ps.isEmpty()) continue
                val inter = ps.map { dom.getValue(it) }.reduce { a, b -> a.intersect(b).toMutableSet() }
                inter.add(n)
                if (inter != dom.getValue(n)) { dom[n] = inter; changed = true }
            }
        }
        // idom: for roots -> VIRTUAL_ROOT; else the strict dominator with max dom-set size
        val idom = mutableMapOf<String, String>()
        roots.forEach { idom[it] = VIRTUAL_ROOT }
        for (n in reachable) {
            if (n in roots) continue
            val strict = dom.getValue(n) - n
            if (strict.isEmpty())
                return Outcome.SemanticUnsolvable("object $n reachable but has no strict dominator", listOf(n))
            idom[n] = strict.maxByOrNull { dom.getValue(it).size }!!
        }

        // --- retained sizes (bytes) over idom tree; post-order, cycle-free by construction ---
        val children = mutableMapOf<String, MutableList<String>>()
        idom.forEach { (n, d) -> if (d != VIRTUAL_ROOT) children.getOrPut(d) { mutableListOf() }.add(n) }
        val retained = mutableMapOf<String, Long>()
        val sizeUnknown = s.objects.filter { it.sizeBytes == null }.map { it.objectId }.sorted()
        fun own(n: String): Long = byId[n]?.sizeBytes ?: 0L
        fun dfs(n: String): Long {
            retained[n]?.let { return it }
            var total = own(n)
            (children[n] ?: mutableListOf()).sorted().forEach { total += dfs(it) }
            retained[n] = total
            return total
        }
        reachable.sorted().forEach { dfs(it) }

        // --- ALL equal-length shortest root paths (multi-source BFS by depth) ---
        val depth = mutableMapOf<String, Int>()
        val paths = mutableMapOf<String, MutableList<RootPath>>()
        val rootTypeOf = s.roots.associate { it.objectId to it.rootType }
        val bfs = ArrayDeque<String>()
        roots.forEach { r ->
            depth[r] = 0
            paths.getOrPut(r) { mutableListOf() }.add(RootPath(rootTypeOf[r] ?: "ROOT", listOf(r)))
            bfs.add(r)
        }
        var pathCount = paths.values.sumOf { it.size }
        while (bfs.isNotEmpty()) {
            val n = bfs.removeFirst()
            val d = depth.getValue(n)
            for (m in (adj[n] ?: emptyList()).sorted()) {
                if (!reachable.contains(m)) continue
                val nd = d + 1
                val existing = depth[m]
                if (existing == null) {
                    depth[m] = nd
                    val list = paths.getOrPut(m) { mutableListOf() }
                    paths.getValue(n).forEach { list.add(RootPath(it.rootType, it.objectIds + m)) }
                    pathCount += list.size
                    bfs.add(m)
                } else if (existing == nd) {
                    val list = paths.getValue(m)
                    paths.getValue(n).forEach { cand ->
                        if (list.none { it.objectIds == cand.objectIds }) {
                            list.add(RootPath(cand.rootType, cand.objectIds + m)); pathCount++
                        }
                    }
                }
                if (pathCount > p.budgetPaths) return Outcome.ResourceExhausted("root-paths", p.budgetPaths)
            }
        }
        val multi = paths.filter { it.value.size > 1 }
        if (multi.isNotEmpty())
            judgments += Judgment("equal-length-root-paths-kept",
                "all ${multi.values.sumOf { it.size }} equal-length shortest paths retained for ${multi.size} objects",
                multi.keys.sorted())

        // --- leak candidates: top retained, all paths kept when not uniquely decidable ---
        val leakCandidates = reachable
            .filter { it !in roots }
            .sortedByDescending { retained.getValue(it) }
            .take(5)
            .map { n ->
                val ps = paths[n] ?: emptyList()
                LeakCandidate(n, retained.getValue(n), ps,
                    if (ps.size > 1) "candidates-kept" else "unique-path")
            }

        return Outcome.Ok(AnalysisResult(
            analysisId, s.snapshotId, p.version, digestOf(s, p),
            reachable.sorted(), unreachable, idom.toSortedMap(), retained.toSortedMap(),
            sizeUnknown, paths.mapValues { (_, v) -> v.sortedBy { it.render() } }.toSortedMap(),
            judgments, leakCandidates))
    }

    /** Class-group diff between two snapshots; classes matched by stable classId, so renames show up. */
    fun diffClasses(a: SnapshotData, b: SnapshotData): List<ClassGroupDiff> {
        val ids = (a.classes.map { it.classId } + b.classes.map { it.classId }).toSortedSet()
        fun stats(s: SnapshotData, cid: String): Pair<Int, Long> {
            val objs = s.objects.filter { it.classId == cid }
            return objs.size to objs.sumOf { it.sizeBytes ?: 0L }
        }
        return ids.map { cid ->
            val ca = a.classes.find { it.classId == cid }
            val cb = b.classes.find { it.classId == cid }
            val (caN, caB) = stats(a, cid); val (cbN, cbB) = stats(b, cid)
            ClassGroupDiff(cid, ca?.name, cb?.name,
                renamed = ca != null && cb != null && ca.name != cb.name,
                caN, cbN, caB, cbB)
        }
    }
}
