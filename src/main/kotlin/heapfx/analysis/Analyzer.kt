package heapfx.analysis

import heapfx.db.Database
import heapfx.model.*
import java.security.MessageDigest

private data class RawGraph(
    val objects: List<Pair<String, Long?>>,          // objectKey -> normalized size (null = unconvertible)
    val sizesRaw: Map<String, String>,
    val strongEdges: Map<String, List<String>>,      // traversal edges per policy
    val roots: List<Pair<String, String>>,           // objectKey -> rootType
    val diagnostics: List<Diagnostic>,
)

class Analyzer(val db: Database) {

    /** Parses a raw size like "64 B" or "1.5 KiB" into bytes. Returns null when unconvertible. */
    fun parseSize(raw: String): Long? {
        val m = Regex("""^\s*([0-9]+(?:\.[0-9]+)?)\s*([A-Za-z]*)\s*$""").matchEntire(raw) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].ifEmpty { "B" }) {
            "B" -> 1.0; "KiB" -> 1024.0; "MiB" -> 1024.0 * 1024; "GiB" -> 1024.0 * 1024 * 1024
            "KB" -> 1000.0; "MB" -> 1000.0 * 1000; else -> return null
        }
        return (value * mult).toLong()
    }

    private fun loadGraph(snapshotId: String, policy: Policy): RawGraph {
        val diagnostics = mutableListOf<Diagnostic>()
        val objects = mutableListOf<Pair<String, Long?>>()
        val sizesRaw = mutableMapOf<String, String>()
        db.conn.prepareStatement("SELECT object_key,size_raw FROM raw_objects WHERE snapshot_id=? ORDER BY seq").use { ps ->
            ps.setString(1, snapshotId)
            val rs = ps.executeQuery()
            while (rs.next()) {
                val key = rs.getString(1); val raw = rs.getString(2)
                val bytes = parseSize(raw)
                if (bytes == null) diagnostics += Diagnostic("WARNING", "raw_objects[$key].size",
                    "size '$raw' has no known unit; value kept raw, excluded from byte totals")
                objects += key to bytes
                sizesRaw[key] = raw
            }
        }
        val edges = mutableMapOf<String, MutableList<String>>()
        db.conn.prepareStatement("SELECT from_key,to_key,kind,name,seq FROM raw_edges WHERE snapshot_id=? ORDER BY seq").use { ps ->
            ps.setString(1, snapshotId)
            val rs = ps.executeQuery()
            while (rs.next()) {
                val kind = runCatching { EdgeKind.valueOf(rs.getString(3)) }.getOrNull() ?: return@use
                val name = rs.getString(4)
                val traverse = when (kind) {
                    EdgeKind.STRONG -> true
                    EdgeKind.INTERNAL -> policy.treatInternalAsStrong
                    // Weak references keep their target alive only under an explicit policy entry.
                    EdgeKind.WEAK -> name in policy.weakKeepAlive
                }
                if (traverse) edges.getOrPut(rs.getString(1)) { mutableListOf() }.add(rs.getString(2))
            }
        }
        val roots = mutableListOf<Pair<String, String>>()
        db.conn.prepareStatement("SELECT object_key,root_type FROM raw_roots WHERE snapshot_id=? ORDER BY seq").use { ps ->
            ps.setString(1, snapshotId)
            val rs = ps.executeQuery()
            while (rs.next()) roots += rs.getString(1) to rs.getString(2)
        }
        return RawGraph(objects, sizesRaw, edges, roots, diagnostics)
    }

    fun compute(snapshotId: String, policy: Policy, computeId: String? = null): ComputeResult {
        val graph = loadGraph(snapshotId, policy)
        val diagnostics = graph.diagnostics.toMutableList()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((snapshotId + policy.toString() + graph.objects.map { it.first }.joinToString()).toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val id = computeId ?: "cmp-" + db.nextSeq("computations", "created_seq").toString().padStart(6, '0')

        fun finish(status: ComputeStatus, objects: List<DerivedObject> = emptyList(),
                   paths: List<RootPath> = emptyList(), leaks: List<String> = emptyList()): ComputeResult {
            val result = ComputeResult(id, snapshotId, policy.policyVersion, digest, status, objects, paths, leaks, diagnostics)
            persist(result)
            return result
        }

        val objectKeys = graph.objects.map { it.first }.toSet()
        val validRoots = graph.roots.filter { it.first in objectKeys }
        if (graph.roots.isNotEmpty() && validRoots.isEmpty()) {
            diagnostics += Diagnostic("ERROR", "raw_roots", "every root references a missing object; graph is semantically unsolvable")
            return finish(ComputeStatus.SEMANTIC_UNSOLVABLE)
        }
        if (graph.roots.isEmpty()) {
            diagnostics += Diagnostic("ERROR", "raw_roots", "no GC roots defined; retention cannot be computed")
            return finish(ComputeStatus.SEMANTIC_UNSOLVABLE)
        }

        // Reachability with an explicit step budget. Cycles terminate via the visited set.
        val reachable = linkedSetOf<String>()
        val depth = mutableMapOf<String, Int>()
        val queue = ArrayDeque<String>()
        var steps = 0
        for ((root, _) in validRoots) if (reachable.add(root)) { depth[root] = 0; queue.add(root) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (reachable.size > policy.maxNodes || ++steps > policy.maxSteps) {
                diagnostics += Diagnostic("ERROR", "policy.budget",
                    "resource budget exhausted after $steps steps / ${reachable.size} nodes")
                return finish(ComputeStatus.BUDGET_EXHAUSTED)
            }
            for (next in graph.strongEdges[cur].orEmpty()) {
                if (next !in objectKeys) continue
                if (reachable.add(next)) { depth[next] = depth.getValue(cur) + 1; queue.add(next) }
            }
        }

        // Dominator tree on the reachable subgraph only (Cooper-Harvey-Kennedy).
        val rpo = reachable.toList() // deterministic: BFS discovery order, ties by insertion
        val index = rpo.withIndex().associate { it.value to it.index }
        val preds = mutableMapOf<String, MutableList<String>>()
        for ((from, tos) in graph.strongEdges) for (to in tos) {
            if (from in reachable && to in reachable) preds.getOrPut(to) { mutableListOf() }.add(from)
        }
        val rootKeys = validRoots.map { it.first }.distinct()
        val idom = mutableMapOf<String, String>()
        fun intersect(a0: String, b0: String): String {
            var a = a0; var b = b0
            while (a != b) {
                while (index.getValue(a) > index.getValue(b)) a = idom.getValue(a)
                while (index.getValue(b) > index.getValue(a)) b = idom.getValue(b)
            }
            return a
        }
        // virtual super-root: each root's idom is itself
        for (r in rootKeys) idom[r] = r
        var changed = true
        while (changed) {
            changed = false
            for (node in rpo) {
                if (node in rootKeys) continue
                val ps = preds[node].orEmpty().filter { it in idom }
                if (ps.isEmpty()) continue
                var newIdom = ps.first()
                for (p in ps.drop(1)) newIdom = intersect(newIdom, p)
                if (idom[node] != newIdom) { idom[node] = newIdom; changed = true }
            }
        }
        // Roots are dominated by the virtual super-root; report parent as null for them.
        val domParent = idom.mapValues { (k, v) -> if (k == v) null else v }

        // Retained sizes: subtree sums over the dominator tree. Unconvertible sizes count as 0
        // in totals but stay raw in the raw layer (see diagnostics).
        val sizeOf = graph.objects.toMap()
        val children = mutableMapOf<String, MutableList<String>>()
        for ((node, parent) in domParent) if (parent != null) children.getOrPut(parent) { mutableListOf() }.add(node)
        val retained = mutableMapOf<String, Long>()
        fun retainedOf(node: String): Long {
            retained[node]?.let { return it }
            var total = sizeOf[node] ?: 0L
            for (c in children[node].orEmpty()) total += retainedOf(c)
            retained[node] = total
            return total
        }
        for (node in reachable) retainedOf(node)

        // All shortest root paths: BFS by depth, keep every equal-length shortest path.
        val paths = mutableListOf<RootPath>()
        val rootTypeOf = validRoots.toMap()
        val byDepth = reachable.groupBy { depth.getValue(it) }.toSortedMap()
        val pathsTo = mutableMapOf<String, MutableList<List<String>>>()
        for (r in rootKeys.sorted()) pathsTo[r] = mutableListOf(listOf(r))
        for ((_, layer) in byDepth) {
            for (node in layer.sorted()) {
                if (node in rootKeys) continue
                val best = mutableListOf<List<String>>()
                var bestLen = Int.MAX_VALUE
                for ((from, tos) in graph.strongEdges) {
                    if (node !in tos) continue
                    for (p in pathsTo[from].orEmpty()) {
                        if (p.size < bestLen) { bestLen = p.size; best.clear() }
                        if (p.size == bestLen && best.size < policy.maxRootPathsPerObject) best.add(p + node)
                    }
                }
                best.sortBy { it.joinToString("") } // stable order for equivalent inputs
                pathsTo[node] = best
            }
        }
        for (node in reachable.sorted()) {
            pathsTo[node].orEmpty().forEachIndexed { i, p ->
                paths += RootPath(node, i, p, rootTypeOf[p.first()] ?: "UNKNOWN")
            }
        }

        val derived = graph.objects.map { (key, size) ->
            DerivedObject(key, key in reachable, size, retained[key] ?: 0L, domParent[key], depth[key] ?: -1)
        }
        val leaks = derived
            .filter { it.reachable && it.retainedBytes >= policy.leakThresholdBytes }
            .sortedByDescending { it.retainedBytes }
            .map { it.objectKey }

        return finish(ComputeStatus.OK, derived, paths, leaks)
    }

    private fun persist(result: ComputeResult) {
        db.tx {
            db.conn.prepareStatement("DELETE FROM derived_objects WHERE compute_id=?").use { it.setString(1, result.computeId); it.executeUpdate() }
            db.conn.prepareStatement("DELETE FROM root_paths WHERE compute_id=?").use { it.setString(1, result.computeId); it.executeUpdate() }
            db.conn.prepareStatement("DELETE FROM computations WHERE compute_id=?").use { it.setString(1, result.computeId); it.executeUpdate() }
            db.conn.prepareStatement("INSERT INTO computations(compute_id,snapshot_id,policy_version,input_digest,status,created_event,created_seq) VALUES(?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, result.computeId); ps.setString(2, result.snapshotId)
                ps.setString(3, result.policyVersion); ps.setString(4, result.inputDigest)
                ps.setString(5, result.status.name); ps.setString(6, null)
                ps.setLong(7, db.nextSeq("computations", "created_seq")); ps.executeUpdate()
            }
            for (o in result.objects) {
                db.conn.prepareStatement("INSERT INTO derived_objects(compute_id,object_key,reachable,size_bytes,retained_bytes,dominator_parent,depth) VALUES(?,?,?,?,?,?,?)").use { ps ->
                    ps.setString(1, result.computeId); ps.setString(2, o.objectKey); ps.setInt(3, if (o.reachable) 1 else 0)
                    if (o.sizeBytes != null) ps.setLong(4, o.sizeBytes) else ps.setNull(4, java.sql.Types.INTEGER)
                    ps.setLong(5, o.retainedBytes); ps.setString(6, o.dominatorParent); ps.setInt(7, o.depth); ps.executeUpdate()
                }
            }
            for (p in result.rootPaths) {
                db.conn.prepareStatement("INSERT INTO root_paths(compute_id,object_key,path_index,nodes_json,root_type) VALUES(?,?,?,?,?)").use { ps ->
                    ps.setString(1, result.computeId); ps.setString(2, p.objectKey); ps.setInt(3, p.pathIndex)
                    ps.setString(4, db.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()), p.nodes))
                    ps.setString(5, p.rootType); ps.executeUpdate()
                }
            }
            result.diagnostics.forEachIndexed { i, d -> db.addDiagnostic("compute", result.computeId, d, i) }
        }
    }

    /** Class-group diff across two snapshots. Identity is classId; a name change is a rename, not a new class. */
    fun diffClasses(snapshotA: String, snapshotB: String): List<ClassGroupDiff> {
        fun load(snap: String): Map<String, Triple<String, Int, Long>> {
            val counts = mutableMapOf<String, Pair<Int, Long>>()
            db.conn.prepareStatement("SELECT class_id, COUNT(*), COALESCE(SUM(CASE WHEN size_raw GLOB '[0-9]*' THEN CAST(size_raw AS INTEGER) ELSE 0 END),0) FROM raw_objects WHERE snapshot_id=? GROUP BY class_id").use { ps ->
                ps.setString(1, snap)
                val rs = ps.executeQuery()
                while (rs.next()) counts[rs.getString(1)] = rs.getInt(2) to rs.getLong(3)
            }
            val names = mutableMapOf<String, String>()
            db.conn.prepareStatement("SELECT class_id,name FROM classes WHERE snapshot_id=?").use { ps ->
                ps.setString(1, snap)
                val rs = ps.executeQuery()
                while (rs.next()) names[rs.getString(1)] = rs.getString(2)
            }
            return counts.map { (cid, cb) -> cid to Triple(names[cid] ?: cid, cb.first, cb.second) }.toMap()
        }
        val a = load(snapshotA); val b = load(snapshotB)
        val ids = (a.keys + b.keys).sorted()
        return ids.map { cid ->
            val ca = a[cid]; val cb = b[cid]
            val relation = when {
                ca == null -> "ONLY_IN_B"
                cb == null -> "ONLY_IN_A"
                ca.first != cb.first -> "RENAMED"
                else -> "MATCHED"
            }
            ClassGroupDiff(cid, ca?.first, cb?.first, ca?.second ?: 0, cb?.second ?: 0,
                ca?.third ?: 0, cb?.third ?: 0, relation)
        }
    }
}
