package heaplab

import java.security.MessageDigest

object Engine {

    const val PARSER_VERSION = "heaplab-engine/1.0"

    fun digestOf(snapshot: HeapSnapshot): String {
        val md = MessageDigest.getInstance("SHA-256")
        // Canonical digest over raw material: order-independent for collections, stable for equivalent input.
        md.update(snapshot.snapshotId.toByteArray())
        md.update(0); md.update(snapshot.parserVersion.toByteArray())
        md.update(0); md.update(snapshot.capturedAt.toByteArray())
        snapshot.classes.sortedBy { it.id }.forEach { md.update(0); md.update("${it.id}${it.name}".toByteArray()) }
        snapshot.objects.sortedBy { it.id }.forEach {
            md.update(0)
            md.update("${it.id}${it.address}${it.classId}${it.shallowSize.value}${it.shallowSize.unit}${it.allocSite}${it.incomplete}".toByteArray())
        }
        snapshot.edges.sortedWith(compareBy({ it.from }, { it.to }, { it.kind.ordinal }, { it.name ?: "" })).forEach {
            md.update(0); md.update("${it.from}${it.to}${it.kind}${it.name}".toByteArray())
        }
        snapshot.roots.sortedWith(compareBy({ it.objectId }, { it.rootType.ordinal })).forEach {
            md.update(0); md.update("${it.objectId}${it.rootType}".toByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Validate raw snapshot. Returns diagnostics with positions; errors make it CORRUPT_FORMAT. */
    fun validate(snapshot: HeapSnapshot): List<Diagnostic> {
        val diags = mutableListOf<Diagnostic>()
        if (snapshot.snapshotId.isBlank())
            diags += Diagnostic("snapshotId", Severity.ERROR, "snapshot id must not be blank")
        if (snapshot.parserVersion.isBlank())
            diags += Diagnostic("parserVersion", Severity.ERROR, "parser version missing; raw provenance cannot be preserved")
        val classIds = snapshot.classes.map { it.id }.toSet()
        val objIds = snapshot.objects.map { it.id }
        objIds.groupingBy { it }.eachCount().filter { it.value > 1 }.forEach { (id, n) ->
            diags += Diagnostic("objects[id=$id]", Severity.ERROR, "duplicate object id '$id' ($n occurrences); object id is the stable identity")
        }
        val idSet = objIds.toSet()
        snapshot.objects.forEachIndexed { i, o ->
            if (o.classId !in classIds)
                diags += Diagnostic("objects[$i].classId", Severity.ERROR, "object '${o.id}' references unknown class '${o.classId}'")
            if (o.shallowSize.value < 0)
                diags += Diagnostic("objects[$i].shallowSize", Severity.ERROR, "object '${o.id}' has negative size ${o.shallowSize.render()}")
            if (o.shallowSize.unit != "byte")
                diags += Diagnostic("objects[$i].shallowSize", Severity.WARNING,
                    "object '${o.id}' uses unit '${o.shallowSize.unit}'; kept raw at original position, not normalized for graphing")
            if (o.incomplete)
                diags += Diagnostic("objects[$i]", Severity.WARNING, "object '${o.id}' originates from an incomplete block; preserved as-is")
        }
        // duplicate addresses: legal, but address is NOT identity -> warning event
        snapshot.objects.groupBy { it.address }.filter { it.value.size > 1 }.forEach { (addr, objs) ->
            diags += Diagnostic("objects[address=$addr]", Severity.WARNING,
                "address $addr shared by objects ${objs.map { it.id }}; address must not be used as stable identity")
        }
        snapshot.edges.forEachIndexed { i, e ->
            if (e.from !in idSet) diags += Diagnostic("edges[$i].from", Severity.ERROR, "edge from unknown object '${e.from}'")
            if (e.to !in idSet) diags += Diagnostic("edges[$i].to", Severity.ERROR, "edge to unknown object '${e.to}'")
        }
        snapshot.roots.forEachIndexed { i, r ->
            if (r.objectId !in idSet) diags += Diagnostic("roots[$i].objectId", Severity.ERROR, "root references unknown object '${r.objectId}'")
        }
        return diags
    }

    /**
     * Core analysis. Dominator tree is built ONLY on the reachable graph.
     * Weak edges keep objects alive only when the explicit policy says so.
     * All shortest (equal-length) root paths are retained, in stable order.
     */
    fun compute(
        computationId: String,
        snapshot: HeapSnapshot,
        params: ParamSet,
        parentComputationIds: List<String> = emptyList()
    ): OpResult<ComputationResult> {
        val validation = validate(snapshot)
        if (validation.any { it.severity == Severity.ERROR })
            return OpResult.Failed(FailureKind.CORRUPT_FORMAT, validation, "snapshot failed validation")
        if (snapshot.objects.size > params.maxObjects)
            return OpResult.Failed(FailureKind.BUDGET_EXHAUSTED, validation,
                "object count ${snapshot.objects.size} exceeds budget ${params.maxObjects}")
        if (snapshot.roots.isEmpty())
            return OpResult.Failed(FailureKind.SEMANTIC_UNSOLVABLE, validation,
                "no roots: reachability and dominator tree are undefined")

        val byId = snapshot.objects.associateBy { it.id }
        val traverseKinds = when (params.weakPolicy) {
            WeakPolicy.WEAK_KEEP_ALIVE -> setOf(EdgeKind.STRONG, EdgeKind.INTERNAL, EdgeKind.WEAK)
            WeakPolicy.WEAK_NOT_ALIVE -> setOf(EdgeKind.STRONG, EdgeKind.INTERNAL)
        }
        val out = snapshot.edges.filter { it.kind in traverseKinds }
            .groupBy { it.from }.mapValues { (_, es) -> es.map { it.to }.sorted() } // stable order

        // --- cycle detection on the traversed graph (structured, not a log line) ---
        val cycleDetected = run {
            val color = mutableMapOf<String, Int>()
            var found = false
            fun dfs(u: String) {
                if (found) return
                color[u] = 1
                for (v in out[u].orEmpty()) {
                    if (found) return
                    when (color[v] ?: 0) { 0 -> dfs(v); 1 -> { found = true; return } }
                }
                color[u] = 2
            }
            snapshot.roots.map { it.objectId }.distinct().sorted().forEach { if ((color[it] ?: 0) == 0) dfs(it) }
            found
        }

        // --- BFS from all roots: reachable set + ALL shortest root paths (stable order) ---
        val rootsSorted = snapshot.roots.sortedWith(compareBy({ it.objectId }, { it.rootType.ordinal }))
        val depth = mutableMapOf<String, Int>()
        val paths = mutableMapOf<String, MutableList<RootPath>>()
        val queue = ArrayDeque<String>()
        for (r in rootsSorted) {
            val d0 = 0
            if ((depth[r.objectId] ?: Int.MAX_VALUE) > d0) {
                depth[r.objectId] = d0; queue.addLast(r.objectId)
            }
            if (depth[r.objectId] == 0) {
                val list = paths.getOrPut(r.objectId) { mutableListOf() }
                if (list.none { it.rootObjectId == r.objectId && it.rootType == r.rootType })
                    list += RootPath(r.objectId, r.rootType, listOf(r.objectId))
            }
        }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            val du = depth.getValue(u)
            for (v in out[u].orEmpty()) {
                val dv = depth[v]
                if (dv == null) {
                    depth[v] = du + 1; queue.addLast(v)
                    paths.getOrPut(v) { mutableListOf() }.addAll(
                        paths[u].orEmpty().map { RootPath(it.rootObjectId, it.rootType, it.hops + v) })
                } else if (dv == du + 1) {
                    val list = paths.getOrPut(v) { mutableListOf() }
                    for (p in paths[u].orEmpty()) {
                        val np = RootPath(p.rootObjectId, p.rootType, p.hops + v)
                        if (np !in list && list.size < params.maxPathsPerObject) list += np
                    }
                }
            }
        }
        val reachable = depth.keys

        // --- Dominator tree on the REACHABLE subgraph only (Cooper-Harvey-Kennedy) ---
        val rootIds = rootsSorted.map { it.objectId }.distinct().filter { it in reachable }
        // virtual super-root handles multiple real roots
        val preds: Map<String, List<String>> = run {
            val m = mutableMapOf<String, MutableList<String>>()
            for ((u, vs) in out) for (v in vs) if (u in reachable && v in reachable) m.getOrPut(v) { mutableListOf() }.add(u)
            m.mapValues { it.value.sorted() }
        }
        val idom = mutableMapOf<String, String>() // "@" is the virtual super-root
        for (r in rootIds) idom[r] = "@"
        fun intersect(a0: String, b0: String): String {
            val ancestors = mutableSetOf<String>()
            var x: String? = a0
            while (x != null) { ancestors += x; x = idom[x] }
            var y: String? = b0
            while (y != null && y !in ancestors) y = idom[y]
            return y ?: "@"
        }
        var changed = true
        while (changed) {
            changed = false
            for (n in reachable.sorted()) {
                if (n in rootIds) continue
                val ps = preds[n].orEmpty().filter { it in reachable }
                if (ps.isEmpty()) continue
                var newIdom: String? = null
                for (p in ps) {
                    if (idom[p] == null && p !in rootIds) continue
                    newIdom = if (newIdom == null) p else intersect(newIdom, p)
                }
                if (newIdom != null && idom[n] != newIdom) { idom[n] = newIdom; changed = true }
            }
        }

        // --- retained size: subtree sums over the dominator forest ---
        val children = mutableMapOf<String, MutableList<String>>()
        for ((n, p) in idom) children.getOrPut(p) { mutableListOf() }.add(n)
        val retained = mutableMapOf<String, Long>()
        fun subtree(u: String): Long {
            var sum = byId.getValue(u).shallowSize.value
            for (c in children[u].orEmpty()) sum += subtree(c)
            retained[u] = sum
            return sum
        }
        rootIds.forEach { subtree(it) }

        val analyses = snapshot.objects.sortedBy { it.id }.map { o ->
            if (o.id in reachable) ObjectAnalysis(
                objectId = o.id, reachable = true,
                dominatorParent = idom[o.id]?.takeIf { it != "@" },
                retainedSize = Quantity(retained[o.id] ?: o.shallowSize.value, "byte"),
                rootPaths = paths[o.id].orEmpty().sortedWith(compareBy({ it.hops.size }, { it.render() }))
            ) else ObjectAnalysis(objectId = o.id, reachable = false)
        }

        val dupAddr = snapshot.objects.groupBy { it.address }
            .filter { it.value.size > 1 }
            .mapValues { it.value.map { o -> o.id }.sorted() }

        return OpResult.Ok(
            ComputationResult(
                computationId = computationId,
                snapshotId = snapshot.snapshotId,
                paramVersion = params.version,
                weakPolicy = params.weakPolicy,
                inputDigest = digestOf(snapshot),
                parentComputationIds = parentComputationIds,
                analyses = analyses,
                unreachableCount = snapshot.objects.size - reachable.size,
                cycleDetected = cycleDetected,
                duplicateAddresses = dupAddr,
                diagnostics = validation
            )
        )
    }

    /** Class groups diff between two snapshots. Classes match by NAME only; identity stays per-snapshot. */
    fun classDiff(left: HeapSnapshot, right: HeapSnapshot): ClassDiff {
        fun groups(s: HeapSnapshot): Map<String, ClassGroup> {
            val className = s.classes.associateBy { it.id }
            return s.objects.groupBy { it.classId }.map { (cid, objs) ->
                val name = className[cid]?.name ?: "<?>$cid"
                name to ClassGroup(
                    className = name, snapshotId = s.snapshotId, objectCount = objs.size,
                    totalShallow = Quantity(objs.sumOf { it.shallowSize.value }, "byte"),
                    matchedClassId = cid
                )
            }.toMap()
        }
        val l = groups(left); val r = groups(right)
        val commonNames = l.keys intersect r.keys
        val onlyL = (l.keys - commonNames).sorted().map { l.getValue(it) }
        val onlyR = (r.keys - commonNames).sorted().map { r.getValue(it) }
        // rename heuristic: unmatched classes with identical object count + total shallow size
        val renames = mutableListOf<Pair<String, String>>()
        val usedR = mutableSetOf<String>()
        for (a in onlyL) {
            val b = onlyR.firstOrNull { it.className !in usedR && it.objectCount == a.objectCount && it.totalShallow == a.totalShallow }
            if (b != null) { renames += a.className to b.className; usedR += b.className }
        }
        return ClassDiff(
            left.snapshotId, right.snapshotId, onlyL, onlyR,
            commonNames.sorted().map { l.getValue(it) to r.getValue(it) },
            renames
        )
    }
}
