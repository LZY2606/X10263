package heapforensics

import kotlinx.serialization.Serializable
import java.security.MessageDigest

const val ANALYSIS_VERSION = "analysis/1.1.0"

@Serializable
data class AnalysisParams(
    val weakPolicy: String = WeakPolicy.WEAK_IGNORE.id,
    val maxPathsPerObject: Int = 8,
    val maxVisited: Int = 100_000,
    val leakThresholdBytes: Long = 1,
    val version: String = ANALYSIS_VERSION
)

@Serializable
data class RootPath(val rootObjectId: String, val rootType: String, val hops: List<String>)

@Serializable
data class ObjectAnalysis(
    val objectId: String,
    val address: String,
    val classId: String?,
    val className: String?,
    val shallowBytes: Long?,
    val retainedBytes: Long,
    val dominatorParent: String?,
    val rootPaths: List<RootPath>,
    val reachableViaWeakOnly: Boolean
)

@Serializable
data class LeakCandidate(
    val objectId: String,
    val retainedBytes: Long,
    val reason: String,
    val rootPaths: List<RootPath>
)

@Serializable
data class AnalysisResult(
    val snapshotId: String,
    val params: AnalysisParams,
    val inputDigest: String,
    val reachable: List<String>,
    val unreachable: List<String>,
    val objects: List<ObjectAnalysis>,
    val dominatorChildren: Map<String, List<String>>,
    val leakCandidates: List<LeakCandidate>,
    val diagnostics: List<Diagnostic>
)

/** Three distinct failure/result categories. */
sealed class AnalysisOutcome {
    data class Ok(val result: AnalysisResult) : AnalysisOutcome()
    data class BudgetExhausted(val detail: String) : AnalysisOutcome()
    data class SemanticallyUnsolvable(val diagnostics: List<Diagnostic>) : AnalysisOutcome()
}

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun canonicalDigest(raw: RawSnapshot): String = sha256(snapshotJson.encodeToString(RawSnapshot.serializer(), raw))

fun analyze(raw: RawSnapshot, params: AnalysisParams): AnalysisOutcome {
    val derived = derive(raw)
    val errors = derived.diagnostics.filter { it.severity == "ERROR" }
    if (errors.isNotEmpty()) return AnalysisOutcome.SemanticallyUnsolvable(errors)

    val policy = WeakPolicy.byId(params.weakPolicy)
    val weakPolicyOn = policy == WeakPolicy.WEAK_KEEP_ALIVE

    // Adjacency in deterministic (document) order. Weak edges count only under explicit policy.
    val strongAdj = LinkedHashMap<String, MutableList<Pair<String, Boolean>>>() // to -> isWeak
    for ((e, kind) in derived.edges) {
        if (kind == null) continue
        val counts = when (kind) {
            EdgeKind.STRONG, EdgeKind.INTERNAL -> true
            EdgeKind.WEAK -> weakPolicyOn
        }
        if (counts) strongAdj.getOrPut(e.from) { mutableListOf() }.add(e.to to (kind == EdgeKind.WEAK))
    }

    val rootIds = raw.roots.map { it.objectId }.distinct()
    val rootTypeOf = raw.roots.associate { it.objectId to it.rootType }

    // Strong-only reachability (STRONG + INTERNAL), independent of weak policy.
    val strongOnlyAdj = LinkedHashMap<String, MutableList<String>>()
    for ((e, kind) in derived.edges) {
        if (kind == EdgeKind.STRONG || kind == EdgeKind.INTERNAL)
            strongOnlyAdj.getOrPut(e.from) { mutableListOf() }.add(e.to)
    }
    val strongReach = HashSet<String>()
    val stack = ArrayDeque(rootIds.filter { derived.objects[it] != null })
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        if (strongReach.add(n)) strongOnlyAdj[n].orEmpty().forEach { stack.addLast(it) }
    }

    // --- Reachability + all equal-length shortest root paths (BFS by levels) ---
    val depth = LinkedHashMap<String, Int>()
    val paths = LinkedHashMap<String, MutableList<RootPath>>()
    var visited = 0
    var budgetHit: String? = null

    for (r in rootIds) {
        if (derived.objects[r] == null) continue
        if (depth[r] == null) depth[r] = 0
        paths.getOrPut(r) { mutableListOf() }.add(RootPath(r, rootTypeOf[r] ?: "UNKNOWN", listOf(r)))
    }
    var frontier = depth.keys.toList()
    while (frontier.isNotEmpty() && budgetHit == null) {
        val next = LinkedHashMap<String, MutableList<Pair<List<String>, Boolean>>>()
        for (node in frontier) {
            for ((to, isWeak) in strongAdj[node].orEmpty()) {
                if (++visited > params.maxVisited) {
                    budgetHit = "visited-edge budget ${params.maxVisited} exhausted at '$node'"
                    break
                }
                val basePaths = paths[node].orEmpty()
                val newHops = basePaths.map { it.hops + to }
                val existing = depth[to]
                val nd = depth.getValue(node) + 1
                if (existing == null) {
                    depth[to] = nd
                    val list = newHops.take(params.maxPathsPerObject)
                    paths[to] = list.map { RootPath(it.first(), rootTypeOf[it.first()] ?: "UNKNOWN", it) }.toMutableList()
                    next.getOrPut(to) { mutableListOf() }
                } else if (existing == nd) {
                    // equal-length alternative path: keep all legal candidates up to budget
                    val cur = paths.getOrPut(to) { mutableListOf() }
                    for (h in newHops) {
                        if (cur.size >= params.maxPathsPerObject) break
                        if (cur.none { it.hops == h }) cur.add(RootPath(h.first(), rootTypeOf[h.first()] ?: "UNKNOWN", h))
                    }
                }
            }
        }
        frontier = next.keys.toList()
    }
    if (budgetHit != null) return AnalysisOutcome.BudgetExhausted(budgetHit)

    val reachable = depth.keys.sorted()
    val unreachable = (derived.objects.keys - depth.keys.toSet()).sorted()

    // --- Dominator tree on the reachable subgraph only ---
    val rset = depth.keys.toSet()
    val preds = HashMap<String, List<String>>()
    for (n in rset) {
        preds[n] = strongAdj.entries
            .filter { it.key in rset }
            .filter { (from, _) -> strongAdj[from]!!.any { (to, _) -> to == n } }
            .map { it.key }
    }
    val rootSet = rootIds.filter { it in rset }.toSet()
    val doms = HashMap<String, MutableSet<String>>()
    for (n in rset) doms[n] = if (n in rootSet) mutableSetOf(n) else rset.toMutableSet()
    var changed = true
    while (changed) {
        changed = false
        for (n in rset.sorted()) {
            if (n in rootSet) continue
            val ps = preds[n].orEmpty().filter { it in rset }
            val newDom = if (ps.isEmpty()) mutableSetOf(n)
            else (ps.map { doms.getValue(it) }.reduce { a, b -> a.intersect(b).toMutableSet() } + n).toMutableSet()
            if (newDom != doms[n]) { doms[n] = newDom; changed = true }
        }
    }
    // Immediate dominator: strict dominator not dominated by any other strict dominator.
    val idom = HashMap<String, String?>()
    for (n in rset.sorted()) {
        if (n in rootSet) { idom[n] = null; continue }
        val strict = doms.getValue(n) - n
        idom[n] = strict.sorted().firstOrNull { c -> strict.all { it == c || c !in doms.getValue(it) } }
    }
    val domChildren = LinkedHashMap<String, MutableList<String>>()
    for ((n, p) in idom) if (p != null) domChildren.getOrPut(p) { mutableListOf() }.add(n)
    domChildren.values.forEach { it.sort() }

    // --- Retained sizes (post-order over dom tree; cycles impossible in a tree) ---
    val retained = HashMap<String, Long>()
    fun retainedOf(n: String): Long = retained.getOrPut(n) {
        val own = derived.objects[n]?.sizeBytes ?: 0L
        (own + (domChildren[n].orEmpty().sumOf { retainedOf(it) })).also { retained[n] = it }
    }
    for (n in rset) retainedOf(n)

    val classNameOf = raw.classes.associate { it.classId to it.name }
    val objects = reachable.map { id ->
        val d = derived.objects.getValue(id)
        ObjectAnalysis(
            objectId = id,
            address = d.raw.address,
            classId = d.raw.classId,
            className = d.raw.classId?.let { classNameOf[it] },
            shallowBytes = d.sizeBytes,
            retainedBytes = retainedOf(id),
            dominatorParent = idom[id],
            rootPaths = paths[id].orEmpty(),
            reachableViaWeakOnly = id !in strongReach
        )
    }

    val leaks = objects
        .filter { it.retainedBytes >= params.leakThresholdBytes && (domChildren[it.objectId]?.isNotEmpty() == true || it.dominatorParent == null) }
        .sortedWith(compareByDescending<ObjectAnalysis> { it.retainedBytes }.thenBy { it.objectId })
        .map { LeakCandidate(it.objectId, it.retainedBytes,
            "retained ${it.retainedBytes} B dominates ${domChildren[it.objectId]?.size ?: 0} reachable object(s)", it.rootPaths) }

    return AnalysisOutcome.Ok(AnalysisResult(
        snapshotId = raw.snapshotId,
        params = params,
        inputDigest = canonicalDigest(raw),
        reachable = reachable,
        unreachable = unreachable,
        objects = objects,
        dominatorChildren = domChildren.mapValues { it.value.toList() },
        leakCandidates = leaks,
        diagnostics = derived.diagnostics
    ))
}
