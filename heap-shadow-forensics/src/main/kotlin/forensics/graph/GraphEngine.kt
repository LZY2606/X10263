package forensics.graph

import forensics.model.*
import kotlinx.serialization.Serializable

/**
 * Whether WEAK edges keep an object alive is an explicit policy decision, never implicit.
 * INTERNAL edges always keep alive (they are VM-internal strong links).
 */
@Serializable
enum class WeakPolicy(val version: String, val keepWeakAlive: Boolean) {
    WEAK_DOES_NOT_KEEP_ALIVE("weak-v1", false),
    WEAK_KEEPS_ALIVE("weak-v2", true),
}

@Serializable
data class MaybeSize(val bytes: Long?, val unresolvedObjectIds: List<String> = emptyList()) {
    val exact: Boolean get() = bytes != null && unresolvedObjectIds.isEmpty()
    val invalidReason: String? get() = when {
        unresolvedObjectIds.isNotEmpty() -> "shallow size unconvertible for: ${unresolvedObjectIds.joinToString(",")}"
        bytes == null -> "no object in subtree had a size"
        else -> null
    }
}

@Serializable
data class RootPath(
    val rootObjectId: String,
    val rootCategory: String,
    val objectIds: List<String>,
    val length: Int,
    val viaWeak: List<Int>,
)

@Serializable
data class ObjectVerdict(
    val stableId: String,
    val objectId: String,
    val reachable: Boolean,
    val keptAlive: Boolean,
    val reachableOnlyViaWeak: Boolean,
    val dominatorStableId: String?,
    val depth: Int,
    val shallowBytes: Long?,
    val retained: MaybeSize,
    val rootPaths: List<RootPath>,
    val leakCandidate: Boolean,
    val leakReason: String?,
)

@Serializable
data class GraphAnalysis(
    val policyVersion: String,
    val superRootStableId: String,
    val orderedStableIds: List<String>,
    val keepAliveAdjacency: Map<String, List<String>>,
    val weakAdjacency: Map<String, List<String>>,
    val objects: List<ObjectVerdict>,
    val duplicateAddresses: Map<String, List<String>>,
    val notes: List<String>,
) {
    fun byStableId(id: String) = objects.firstOrNull { it.stableId == id }
}

class GraphEngine(private val snapshot: HeapSnapshot, private val policy: WeakPolicy) {

    private val byId: Map<String, HeapObject> =
        snapshot.objects.associateBy { it.objectId }

    fun analyze(): GraphAnalysis {
        // Stable order: equivalent inputs must produce equivalent output order.
        val objects = snapshot.objects.sortedWith(
            compareBy({ it.objectId }, { it.stableId })
        )
        val ids = objects.map { it.objectId }

        fun keepAlive(e: HeapEdge) =
            e.strength != EdgeStrength.WEAK || policy.keepWeakAlive

        val ka = linkedMapOf<String, MutableList<String>>()
        val wk = linkedMapOf<String, MutableList<String>>()
        snapshot.edges.sortedBy { it.stableId }.forEach { e ->
            if (keepAlive(e)) ka.getOrPut(e.fromObjectId) { mutableListOf() }.add(e.toObjectId)
            if (e.strength == EdgeStrength.WEAK) wk.getOrPut(e.fromObjectId) { mutableListOf() }.add(e.toObjectId)
        }
        ka.values.forEach { it.sort() }
        wk.values.forEach { it.sort() }

        // Reachability over keep-alive edges from all roots.
        val rootIds = snapshot.roots.map { it.objectId }.distinct().sorted()
        val kept = bfs(rootIds, ka)
        // Reachability over every edge (weak edges included) — survival without GC keep-alive.
        val all = ka.mapValues { it.value.toMutableList() }
        wk.forEach { (k, vs) -> vs.forEach { all.getOrPut(k) { mutableListOf() }.add(it) } }
        all.values.forEach { it.sort() }
        val surviving = bfs(rootIds, all)

        // Super root -> each GC root, dominators computed ONLY on the reachable graph.
        val SUPER = "super-root"
        val succ = HashMap<String, MutableList<String>>()
        succ[SUPER] = rootIds.toMutableList()
        kept.toList().sorted().forEach { node ->
            succ.getOrPut(node) { mutableListOf() }.addAll((ka[node] ?: emptyList()).filter { it in kept })
        }
        val idom = LengauerTarjan(succ, SUPER).dominators()
        val depth = HashMap<String, Int>()
        depth[SUPER] = 0
        // depth via shortest keep-alive path (computed during BFS below)
        val (dist, parentsAtDepth, weakHopAt) = bfsParents(rootIds, ka)
        kept.forEach { depth[it] = (dist[it] ?: 0) }

        // Dominated subtree -> retained size.
        val children = HashMap<String, MutableList<String>>()
        idom.forEach { (node, dom) -> if (dom != null) children.getOrPut(dom) { mutableListOf() }.add(node) }
        children.values.forEach { it.sort() }
        val retainedBytes = HashMap<String, Long?>()
        val unresolved = HashMap<String, MutableList<String>>()
        fun compute(node: String): Pair<Long?, List<String>> {
            retainedBytes[node]?.let { return it to (unresolved[node] ?: emptyList()) }
            var sum = 0L
            val bad = mutableListOf<String>()
            (children[node] ?: emptyList()).forEach { c ->
                val (s, b) = compute(c)
                if (s != null) sum += s
                bad += b
            }
            if (node != SUPER) {
                val self = byId[node]?.shallowSize?.normalizedBytes
                if (self != null) sum += self else bad += node
            }
            retainedBytes[node] = if (bad.isEmpty()) sum else null
            unresolved[node] = bad
            return retainedBytes[node]!! to bad
        }
        (children[SUPER] ?: emptyList()).forEach { compute(it) }

        val rootCat = snapshot.roots
            .sortedWith(compareBy({ it.objectId }, { it.stableId }))
            .associate { it.objectId to it.category }

        val weakParentSet = HashMap<String, List<Int>>()
        val verdicts = ids.map { oid ->
            val o = byId.getValue(oid)
            val isKept = oid in kept
            val isSurviving = oid in surviving
            val onlyWeak = isSurviving && !isKept
            val dom = idom[oid]
            val paths = if (oid in kept || oid in surviving)
                enumeratePaths(oid, parentsAtDepth, dist, rootIds, weakHopAt, snapshot.edges) else emptyList()
            val ret = if (isKept)
                MaybeSize(retainedBytes[oid], unresolved[oid]?.sorted() ?: emptyList())
            else MaybeSize(null, emptyList())
            val leak = onlyWeak || (isKept && run {
                val cat = rootCat[paths.firstOrNull()?.rootObjectId]
                (cat != null && cat.uppercase() in SUSPICIOUS_ROOT_CATEGORIES) &&
                    ((ret.bytes ?: 0L) > 0L)
            })
            val reason = when {
                onlyWeak -> "survives only through WEAK edge(s); not kept alive under ${policy.version}"
                leak -> "retained under root category ${rootCat[paths.firstOrNull()?.rootObjectId]}"
                else -> null
            }
            ObjectVerdict(
                o.stableId, oid, isSurviving, isKept, onlyWeak,
                dom?.let { if (it == SUPER) SUPER else byId[it]?.stableId },
                dist[oid] ?: -1, o.shallowSize?.normalizedBytes, ret, paths, leak, reason,
            )
        }

        val dupAddresses = snapshot.objects.mapNotNull { it.address }.groupBy { it }
            .filter { it.value.size > 1 }
            .mapValues { (_, addrs) ->
                snapshot.objects.filter { it.address == addrs.first() }.map { it.stableId }.sorted()
            }
        val notes = buildList {
            if (policy == WeakPolicy.WEAK_DOES_NOT_KEEP_ALIVE)
                add("dominator tree built on STRONG+INTERNAL reachable graph only (${policy.version})")
            else add("dominator tree includes WEAK edges by explicit ${policy.version} policy")
            if (dupAddresses.isNotEmpty()) add("addresses repeat; address is NOT used as stable identity")
            if (verdicts.any { !it.retained.exact }) add("some retained sizes invalid: unconvertible source values kept in place")
        }
        return GraphAnalysis(policy.version, SUPER, objects.map { it.stableId },
            ka.mapValues { it.value }, wk.mapValues { it.value }, verdicts, dupAddresses, notes)
    }

    private fun bfs(starts: List<String>, adj: Map<String, List<String>>): Set<String> {
        val seen = LinkedHashSet(starts.filter { byId.containsKey(it) })
        val q = ArrayDeque(seen)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            (adj[n] ?: emptyList()).forEach { if (seen.add(it)) q.addLast(it) }
        }
        return seen
    }

    /** Multi-source BFS recording *all* parents on a shortest path, preserving equal-length ties. */
    private fun bfsParents(
        roots: List<String>, adj: Map<String, List<String>>,
    ): Triple<Map<String, Int>, Map<String, List<String>>, Map<String, Boolean>> {
        val dist = LinkedHashMap<String, Int>()
        val parents = LinkedHashMap<String, MutableList<String>>()
        val weakHop = HashMap<String, Boolean>()
        roots.forEach { if (byId.containsKey(it)) { dist[it] = 0; parents[it] = mutableListOf(); weakHop[it] = false } }
        val q = ArrayDeque(roots.filter { byId.containsKey(it) })
        // Edge strengths lookup
        val strengthByPair = snapshot.edges.groupBy { it.fromObjectId to it.toObjectId }
            .mapValues { e -> e.value.map { it.strength } }
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val d = dist.getValue(n)
            (adj[n] ?: emptyList()).forEach { m ->
                val existing = dist[m]
                val viaWeak = (strengthByPair[n to m] ?: emptyList()).any { it == EdgeStrength.WEAK }
                when {
                    existing == null -> {
                        dist[m] = d + 1; parents[m] = mutableListOf(n); weakHop[m] = viaWeak
                        q.addLast(m)
                    }
                    existing == d + 1 -> {
                        parents.getOrPut(m) { mutableListOf() }.add(n)
                        weakHop[m] = (weakHop[m] == true) || viaWeak
                    }
                }
            }
        }
        return Triple(dist, parents, weakHop)
    }

    private fun enumeratePaths(
        target: String,
        parents: Map<String, List<String>>,
        dist: Map<String, Int>,
        roots: List<String>,
        weakHopAt: Map<String, Boolean>,
        edges: List<HeapEdge>,
    ): List<RootPath> {
        val edgeStrengths = edges.groupBy { it.fromObjectId to it.toObjectId }
            .mapValues { e -> e.value.map { it.strength }.toSet() }
        val rootSet = roots.toHashSet()
        val out = mutableListOf<RootPath>()
        fun walk(node: String, chain: List<String>, weakIdx: List<Int>) {
            if (out.size >= MAX_PATHS) return
            if (node in rootSet || parents[node].isNullOrEmpty()) {
                if (node in rootSet) {
                    val full = (chain.reversed() + target).let { p -> if (p.first() == target) listOf(target) else p }
                    val objPath = if (chain.isEmpty()) listOf(target) else chain.reversed() + target
                    out += RootPath(
                        node,
                        snapshot.roots.filter { it.objectId == node }.minOfOrNull { it.category } ?: node,
                        objPath, objPath.size - 1,
                        weakIdx,
                    )
                }
                return
            }
            parents[node]!!.sorted().forEach { p ->
                val idx = weakIdx + listOfNotNull(
                    if (EdgeStrength.WEAK in (edgeStrengths[p to node] ?: emptySet())) chain.size else null)
                walk(p, chain + node, idx)
            }
        }
        walk(target, emptyList(), emptyList())
        return out.sortedWith(compareBy({ it.length }, { it.rootObjectId }, { it.objectIds.joinToString(",") }))
            .distinctBy { it.objectIds }
    }

    companion object {
        const val MAX_PATHS = 64
        val SUSPICIOUS_ROOT_CATEGORIES = setOf("NATIVE_STACK", "JNI_GLOBAL", "SYSTEM_CLASSLOADER", "FINALIZER")
    }
}

/** Lengauer–Tarjan iterative dominators, adjacency over the reachable graph + super root. */
internal class LengauerTarjan(private val succ: Map<String, List<String>>, private val entry: String) {
    private val vertices = mutableListOf<String>()
    private val num = HashMap<String, Int>()
    private val parent = HashMap<String, String>()
    private val semi = HashMap<String, Int>()
    private val vertex = HashMap<Int, String>()
    private val ancestor = HashMap<String, String>()
    private val label = HashMap<String, String>()
    private val bucket = HashMap<String, MutableList<String>>()
    private val idom = HashMap<String, String>()

    fun dominators(): Map<String, String> {
        dfs(entry)
        val pred = predecessors()
        (vertices.size - 1 downTo 1).forEach { i ->
            val w = vertex.getValue(i)
            (pred[w] ?: emptyList()).forEach { v ->
                if (num[v] == null) return@forEach
                val u = eval(v)
                if (semi.getValue(u) < semi.getValue(w)) semi[w] = semi.getValue(u)
            }
            bucket.getOrPut(vertex.getValue(semi.getValue(w))) { mutableListOf() }.add(w)
            parent[w]?.let { link(it, w) }
            (bucket[parent[w]] ?: mutableListOf()).toList().let { list ->
                bucket[parent[w]]?.clear()
                list.forEach { v ->
                    val u = eval(v)
                    idom[v] = if (semi.getValue(u) < semi.getValue(v)) u else parent.getValue(v)
                }
            }
        }
        (1 until vertices.size).forEach { i ->
            val w = vertex.getValue(i)
            if (idom[w] != vertex[semi[w]]) idom[w]?.let { idom[w] = idom.getValue(it) }
        }
        idom[entry] = ""
        return idom.filterValues { it.isNotEmpty() }
    }

    private fun dfs(start: String) {
        val stack = ArrayDeque<Pair<String, Iterator<String>>>()
        fun visit(n: String) {
            num[n] = vertices.size; vertex[vertices.size] = n
            semi[n] = vertices.size; label[n] = n
            vertices += n
        }
        visit(start)
        stack.addLast(start to (succ[start] ?: emptyList()).distinct().sorted().iterator())
        while (stack.isNotEmpty()) {
            val (node, it) = stack.last()
            if (!it.hasNext()) { stack.removeLast(); continue }
            val child = it.next()
            if (num[child] != null) continue
            parent[child] = node
            visit(child)
            stack.addLast(child to (succ[child] ?: emptyList()).distinct().sorted().iterator())
        }
    }

    private fun predecessors(): Map<String, MutableList<String>> {
        val p = HashMap<String, MutableList<String>>()
        succ.forEach { (u, vs) -> vs.forEach { v -> p.getOrPut(v) { mutableListOf() }.add(u) } }
        return p
    }

    private fun compress(v: String) {
        val a = ancestor[v] ?: return
        if (ancestor[a] != null) {
            compress(a)
            if (semi.getValue(label[a]) < semi.getValue(label[v])) label[v] = label[a]
            ancestor[v] = ancestor[a]
        }
    }

    private fun eval(v: String): String {
        if (ancestor[v] == null) return label[v]
        compress(v)
        return label[v]
    }

    private fun link(p: String, v: String) { ancestor[v] = p }
}
