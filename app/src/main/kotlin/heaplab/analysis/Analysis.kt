package heaplab.analysis

import heaplab.model.*
import kotlinx.serialization.Serializable

/** Three distinct failure outcomes plus success — never fake success with nulls/defaults. */
sealed interface AnalysisOutcome {
    @Serializable
    data class Success(val report: Report) : AnalysisOutcome
    @Serializable
    data class BudgetExhausted(val detail: String, val stepsUsed: Long, val budget: Long) : AnalysisOutcome
    @Serializable
    data class CorruptFormat(val diagnostics: List<Diagnostic>) : AnalysisOutcome
    @Serializable
    data class SemanticUnresolvable(val diagnostics: List<Diagnostic>) : AnalysisOutcome
}

@Serializable
data class ObjectRow(
    val objectId: String,
    val address: String,
    val className: String,
    val shallowBytes: Long?,
    val retainedBytes: Long?,
    val dominatorParent: String?,      // immediate dominator object id, null for roots/reach-entry
    val rootPaths: List<List<String>>, // ALL shortest root->object paths (equal length kept)
    val reachable: Boolean,
    val incomplete: Boolean,
    val allocationSite: String?,
)

@Serializable
data class ClassGroup(val className: String, val count: Int, val shallowBytes: Long)

@Serializable
data class ClassDiff(val onlyInA: List<ClassGroup>, val onlyInB: List<ClassGroup>, val inBoth: List<ClassGroup>)

@Serializable
data class Report(
    val snapshotId: String,
    val params: AnalysisParams,
    val inputDigest: String,
    val rows: List<ObjectRow>,            // stable order: sorted by objectId
    val totalRetainedByRoot: Map<String, Long>,
    val unreachableCount: Int,
    val events: List<AnalysisEvent>,      // machine-readable judgment evidence
    val validFrom: String,                // validity interval of this result
    val validUntil: String,
    val invalidationReasons: List<String>,
)

@Serializable
data class AnalysisEvent(val type: String, val detail: Map<String, String>)

class Budget(val maxSteps: Long) {
    var used: Long = 0; private set
    fun step(n: Int = 1): Boolean {
        used += n
        return used <= maxSteps
    }
}

object Analyzer {

    /** Validate raw input. Hard structural breakage -> CorruptFormat; semantic dead-ends -> SemanticUnresolvable. */
    fun validate(s: HeapSnapshot): Pair<List<Diagnostic>, List<Diagnostic>> {
        val corrupt = mutableListOf<Diagnostic>()
        val semantic = mutableListOf<Diagnostic>()
        val classIds = s.classes.map { it.classId }.toSet()
        val objIds = s.objects.map { it.objectId }
        val dupIds = objIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        dupIds.forEach { corrupt += Diagnostic("objects", "DUPLICATE_OBJECT_ID", "object id '$it' appears more than once") }
        val idSet = objIds.toSet()
        s.objects.forEachIndexed { i, o ->
            if (o.classId !in classIds)
                corrupt += Diagnostic("objects[$i].classId", "UNKNOWN_CLASS", "class '${o.classId}' not declared")
            if (o.size.toBytes() == null)
                semantic += Diagnostic("objects[$i].size", "SIZE_UNPARSEABLE",
                    "size '${o.size.raw}' unit '${o.size.unit}' cannot be normalized to bytes; raw value kept in place", Severity.WARNING)
        }
        s.edges.forEachIndexed { i, e ->
            if (e.fromId !in idSet) corrupt += Diagnostic("edges[$i].fromId", "DANGLING_EDGE", "from '${e.fromId}' not an object")
            if (e.toId !in idSet) corrupt += Diagnostic("edges[$i].toId", "DANGLING_EDGE", "to '${e.toId}' not an object")
        }
        s.roots.forEachIndexed { i, r ->
            if (r.objectId !in idSet) corrupt += Diagnostic("roots[$i].objectId", "DANGLING_ROOT", "root '${r.objectId}' not an object")
        }
        if (s.roots.isEmpty())
            semantic += Diagnostic("roots", "NO_ROOTS", "snapshot has no roots; reachability is undefined", Severity.ERROR)
        return corrupt to semantic
    }

    fun analyze(s: HeapSnapshot, params: AnalysisParams, digest: String): AnalysisOutcome {
        val (corrupt, semantic) = validate(s)
        if (corrupt.isNotEmpty()) return AnalysisOutcome.CorruptFormat(corrupt)
        val hardSemantic = semantic.filter { it.severity == Severity.ERROR }
        if (hardSemantic.isNotEmpty()) return AnalysisOutcome.SemanticUnresolvable(semantic)

        val budget = Budget(params.maxSteps)
        val events = mutableListOf<AnalysisEvent>()
        val byId = s.objects.associateBy { it.objectId }
        val className = s.classes.associate { it.classId to it.name }

        // Adjacency: strong+internal always; weak only when the explicit policy retains them.
        val keepWeak = params.weakPolicy == WeakPolicy.WEAK_RETAINED
        val adj = mutableMapOf<String, MutableList<String>>()
        val radj = mutableMapOf<String, MutableList<String>>()
        for (e in s.edges.sortedWith(compareBy({ it.fromId }, { it.toId }, { it.kind.name }))) {
            if (e.kind == EdgeKind.WEAK && !keepWeak) continue
            adj.getOrPut(e.fromId) { mutableListOf() }.add(e.toId)
            radj.getOrPut(e.toId) { mutableListOf() }.add(e.fromId)
        }
        events += AnalysisEvent("weak.policy", mapOf(
            "policy" to params.weakPolicy.name,
            "basis" to "weak edges keep objects alive only under explicit policy WEAK_RETAINED"))

        // Reachability from roots (cycle-safe visited set).
        val reachable = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        s.roots.map { it.objectId }.sorted().forEach { if (reachable.add(it)) stack.addLast(it) }
        while (stack.isNotEmpty()) {
            if (!budget.step()) return AnalysisOutcome.BudgetExhausted("reachability traversal", budget.used, params.maxSteps)
            val cur = stack.removeLast()
            for (nxt in adj[cur].orEmpty()) if (reachable.add(nxt)) stack.addLast(nxt)
        }
        val unreachable = s.objects.filter { it.objectId !in reachable }.map { it.objectId }.sorted()
        events += AnalysisEvent("dominator.reachable_only", mapOf(
            "reachable" to reachable.size.toString(),
            "unreachable" to unreachable.size.toString(),
            "basis" to "dominator tree is built on the reachable subgraph only"))

        // Dominator tree (Cooper-Harvey-Kennedy) on the reachable subgraph only.
        val rootIds = s.roots.map { it.objectId }.filter { it in reachable }.sorted()
        val vroot = "\u0000VROOT" // virtual super-root for multiple real roots
        val domAdj = mutableMapOf<String, List<String>>()
        for (id in reachable) domAdj[id] = (adj[id].orEmpty().filter { it in reachable }).sorted()
        domAdj[vroot] = rootIds
        val order = mutableListOf<String>()
        val seen = mutableSetOf(vroot)
        val st = ArrayDeque<String>(); st.addLast(vroot)
        while (st.isNotEmpty()) {
            if (!budget.step()) return AnalysisOutcome.BudgetExhausted("dominator dfs", budget.used, params.maxSteps)
            val cur = st.removeLast(); order += cur
            for (nxt in domAdj[cur].orEmpty()) if (seen.add(nxt)) st.addLast(nxt)
        }
        val rpo = order.asReversed()
        val rpoIndex = rpo.withIndex().associate { it.value to it.index }
        val idom = mutableMapOf<String, String>()
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
            if (!budget.step(rpo.size)) return AnalysisOutcome.BudgetExhausted("dominator iteration", budget.used, params.maxSteps)
            for (n in rpo) {
                if (n == vroot) continue
                val preds = (if (n in rootIds) listOf(vroot) else emptyList()) +
                    (radj[n].orEmpty().filter { it in reachable })
                val defined = preds.filter { it == vroot || idom.containsKey(it) }
                if (defined.isEmpty()) continue
                var newIdom = defined.first()
                for (p in defined.drop(1)) newIdom = intersect(p, newIdom)
                if (idom[n] != newIdom) { idom[n] = newIdom; changed = true }
            }
        }

        // Retained sizes: subtree sums over the dominator tree.
        val children = mutableMapOf<String, MutableList<String>>()
        idom.forEach { (n, p) -> children.getOrPut(p) { mutableListOf() }.add(n) }
        val shallow = s.objects.associate { it.objectId to it.size.toBytes() }
        val retained = HashMap<String, Long?>()
        fun dfsRet(n: String): Long? {
            if (n in retained) return retained[n]
            var sum: Long? = shallow[n] ?: 0L
            for (c in children[n].orEmpty().sorted()) {
                if (!budget.step()) return null
                val cs = dfsRet(c) ?: run { sum = null; continue }
                sum = sum?.plus(cs)
            }
            retained[n] = sum
            return sum
        }
        for (n in reachable.sorted()) {
            if (!budget.step()) return AnalysisOutcome.BudgetExhausted("retained-size pass", budget.used, params.maxSteps)
            dfsRet(n)
        }

        // Root paths: BFS by layers, keeping ALL equal-length shortest paths (cycle-safe).
        val rootPaths = mutableMapOf<String, List<List<String>>>()
        var frontier: List<List<String>> = rootIds.map { listOf(it) }
        rootIds.forEach { rootPaths[it] = listOf(listOf(it)) }
        var depth = 0
        while (frontier.isNotEmpty() && depth < reachable.size + 1) {
            if (!budget.step(frontier.size)) return AnalysisOutcome.BudgetExhausted("root-path bfs", budget.used, params.maxSteps)
            val next = mutableListOf<List<String>>()
            val perTarget = mutableMapOf<String, MutableList<List<String>>>()
            for (path in frontier) {
                val last = path.last()
                for (nxt in domAdj[last].orEmpty()) {
                    if (nxt in path) continue // cycle guard
                    if (rootPaths.containsKey(nxt)) continue // already reached at shorter depth
                    val np = path + nxt
                    perTarget.getOrPut(nxt) { mutableListOf() }.add(np)
                }
            }
            for ((t, paths) in perTarget) {
                val capped = paths.take(params.maxRootPathsPerObject)
                rootPaths[t] = capped
                next += capped
            }
            frontier = next
            depth++
        }
        val multi = rootPaths.filter { it.value.size > 1 }.keys.sorted()
        if (multi.isNotEmpty()) events += AnalysisEvent("rootpath.multi_equal", mapOf(
            "objects" to multi.joinToString(","),
            "basis" to "all equal-length shortest root paths are retained; no unique winner is forced"))

        // Identity: address is never a stable identity — detect duplicate addresses as evidence.
        val dupAddr = s.objects.groupBy { it.address }.filter { it.value.size > 1 }
        if (dupAddr.isNotEmpty()) events += AnalysisEvent("identity.address_not_stable", mapOf(
            "addresses" to dupAddr.keys.sorted().joinToString(","),
            "objects" to dupAddr.flatMap { it.value.map { o -> o.objectId } }.sorted().joinToString(","),
            "basis" to "address is not used as stable identity; objectId is the identity"))
        val cyclic = reachable.any { id -> domAdj[id].orEmpty().any { it == id } } ||
            s.edges.any { it.fromId != it.toId && reachable.contains(it.fromId) && reachable.contains(it.toId) &&
                reaches(domAdj, it.toId, it.fromId, reachable) }
        if (cyclic) events += AnalysisEvent("graph.cycle", mapOf(
            "basis" to "cycles in the reachable graph do not affect dominator or root-path termination"))

        val rows = s.objects.sortedBy { it.objectId }.map { o ->
            ObjectRow(
                objectId = o.objectId,
                address = o.address,
                className = className[o.classId] ?: "<unknown>",
                shallowBytes = shallow[o.objectId],
                retainedBytes = if (o.objectId in reachable) retained[o.objectId] else null,
                dominatorParent = idom[o.objectId]?.takeIf { it != vroot },
                rootPaths = rootPaths[o.objectId] ?: emptyList(),
                reachable = o.objectId in reachable,
                incomplete = o.incomplete,
                allocationSite = o.allocationSite,
            )
        }
        val rootRetained = rootIds.associateWith { retained[it] ?: 0L }.toSortedMap()
        return AnalysisOutcome.Success(Report(
            snapshotId = s.snapshotId,
            params = params,
            inputDigest = digest,
            rows = rows,
            totalRetainedByRoot = rootRetained,
            unreachableCount = unreachable.size,
            events = events,
            validFrom = s.capturedAt,
            validUntil = "superseded-by-next-analysis",
            invalidationReasons = listOf(
                "params version changed",
                "source snapshot amended",
                "human decision conflicted",
            ),
        ))
    }

    private fun reaches(adj: Map<String, List<String>>, from: String, to: String, scope: Set<String>): Boolean {
        val seen = mutableSetOf<String>()
        val st = ArrayDeque<String>(); st.addLast(from)
        while (st.isNotEmpty()) {
            val c = st.removeLast()
            if (c == to) return true
            if (!seen.add(c)) continue
            for (n in adj[c].orEmpty()) if (n in scope) st.addLast(n)
        }
        return false
    }

    /** Class-group diff between two snapshots, keyed by class name (rename shows as remove+add). */
    fun classDiff(a: HeapSnapshot, b: HeapSnapshot): ClassDiff {
        fun groups(s: HeapSnapshot) = s.objects.groupBy { o -> s.classes.firstOrNull { it.classId == o.classId }?.name ?: "<unknown>" }
            .map { (n, os) -> ClassGroup(n, os.size, os.sumOf { it.size.toBytes() ?: 0L }) }
            .associateBy { it.className }
        val ga = groups(a); val gb = groups(b)
        return ClassDiff(
            onlyInA = (ga.keys - gb.keys).sorted().map { ga.getValue(it) },
            onlyInB = (gb.keys - ga.keys).sorted().map { gb.getValue(it) },
            inBoth = (ga.keys intersect gb.keys).sorted().map { gb.getValue(it) },
        )
    }
}
