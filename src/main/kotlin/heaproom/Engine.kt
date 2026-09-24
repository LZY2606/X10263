package heaproom

const val VIRTUAL_ROOT = "<virtual-root>"

data class ParamSet(
    val version: String,
    val weakPolicy: WeakPolicy,
    val nodeBudget: Int
)

object Engine {

    /**
     * 规范化输入顺序：等价输入（数组顺序不同）得到相同摘要与稳定顺序。
     * 身份是 objectId/classId；地址绝不参与身份与排序。
     */
    fun canonical(doc: SnapshotDoc): SnapshotDoc = doc.copy(
        classes = doc.classes.sortedBy { it.classId },
        objects = doc.objects.sortedBy { it.objectId },
        edges = doc.edges.sortedWith(compareBy({ it.from }, { it.to }, { it.kind.name }, { it.name })),
        roots = doc.roots.sortedWith(compareBy({ it.objectId }, { it.rootType.name })),
        incompleteBlocks = doc.incompleteBlocks.sortedBy { it.position }
    )

    fun canonicalDigest(doc: SnapshotDoc): String =
        Digests.sha256(CanonicalJson.encodeToString(canonical(doc)))

    data class Graph(
        val ids: Set<String>,
        val outgoing: Map<String, List<EdgeRec>>,
        val incoming: Map<String, List<EdgeRec>>,
        val roots: Set<String>,
        val diagnostics: List<Diagnostic>
    )

    fun buildGraph(doc: SnapshotDoc): Graph {
        val diagnostics = mutableListOf<Diagnostic>()
        val ids = doc.objects.map { it.objectId }.toSet()
        if (ids.size != doc.objects.size) {
            diagnostics += Diagnostic("objects", "error", "重复 objectId，身份必须唯一")
        }
        val outgoing = HashMap<String, MutableList<EdgeRec>>()
        val incoming = HashMap<String, MutableList<EdgeRec>>()
        for (e in doc.edges) {
            if (e.from !in ids) diagnostics += Diagnostic("edge:${e.from}->${e.to}", "error", "边的起点对象缺失（不完整对象）")
            if (e.to !in ids) diagnostics += Diagnostic("edge:${e.from}->${e.to}", "error", "边的终点对象 ${e.to} 缺失（不完整对象），引用悬空")
            if (e.from in ids && e.to in ids) {
                outgoing.getOrPut(e.from) { mutableListOf() }.add(e)
                incoming.getOrPut(e.to) { mutableListOf() }.add(e)
            }
        }
        val roots = doc.roots.filter { it.objectId in ids }.map { it.objectId }.toSortedSet()
        doc.roots.filter { it.objectId !in ids }.forEach {
            diagnostics += Diagnostic("root:${it.objectId}", "error", "root 指向缺失对象（不完整对象）")
        }
        return Graph(ids, outgoing, incoming, roots, diagnostics)
    }

    fun reachable(doc: SnapshotDoc, params: ParamSet): ComputeOutcome<Set<String>> {
        val g = buildGraph(doc)
        val visited = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        for (r in g.roots.sorted()) {
            if (visited.add(r)) queue.addLast(r)
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (e in g.outgoing[cur].orEmpty()) {
                val allowed = when (e.kind) {
                    EdgeKind.STRONG, EdgeKind.INTERNAL -> true
                    EdgeKind.WEAK -> params.weakPolicy == WeakPolicy.WEAK_KEEP_ALIVE
                }
                if (allowed && visited.add(e.to)) {
                    if (visited.size > params.nodeBudget)
                        return ComputeOutcome.BudgetExhausted(
                            scope = "reachability:${doc.snapshotId}",
                            limit = params.nodeBudget,
                            consumed = visited.size
                        )
                    queue.addLast(e.to)
                }
            }
        }
        return ComputeOutcome.Ok(visited.toSet(), g.diagnostics)
    }

    /**
     * 支配树只在可达子图上构建（弱边是否参与由显式策略决定）。
     * Cooper-Harvey-Kennedy 迭代法，支持循环图。
     */
    fun dominators(doc: SnapshotDoc, params: ParamSet, reachable: Set<String>): Map<String, String> {
        val g = buildGraph(doc)
        fun allowed(e: EdgeRec) = when (e.kind) {
            EdgeKind.STRONG, EdgeKind.INTERNAL -> true
            EdgeKind.WEAK -> params.weakPolicy == WeakPolicy.WEAK_KEEP_ALIVE
        }
        val succ = HashMap<String, MutableList<String>>()
        for (e in doc.edges) {
            if (!allowed(e) || e.from !in reachable || e.to !in reachable) continue
            succ.getOrPut(e.from) { mutableListOf() }.add(e.to)
        }
        for (r in g.roots) if (r in reachable) succ.getOrPut(VIRTUAL_ROOT) { mutableListOf() }.add(r)

        val order = ArrayList<String>()
        val dfsVisited = HashSet<String>()
        fun dfs(u: String) {
            if (!dfsVisited.add(u)) return
            order.add(u)
            for (v in succ[u].orEmpty().sorted()) dfs(v)
        }
        dfs(VIRTUAL_ROOT)
        val orderIndex = order.withIndex().associate { (i, v) -> v to i }

        val preds = HashMap<String, MutableList<String>>()
        for ((u, vs) in succ) for (v in vs) preds.getOrPut(v) { mutableListOf() }.add(u)

        val idom = HashMap<String, String>()
        idom[VIRTUAL_ROOT] = VIRTUAL_ROOT
        fun intersect(aIn: String, bIn: String): String {
            var a = aIn
            var b = bIn
            while (a != b) {
                while (orderIndex[a]!! > orderIndex[b]!!) a = idom[a]!!
                while (orderIndex[b]!! > orderIndex[a]!!) b = idom[b]!!
            }
            return a
        }
        var changed = true
        while (changed) {
            changed = false
            for (i in 1 until order.size) {
                val b = order[i]
                val candidates = preds[b].orEmpty().filter { it in idom }
                if (candidates.isEmpty()) continue
                val sorted = candidates.sortedBy { orderIndex[it] }
                var d = sorted.first()
                for (j in 1 until sorted.size) d = intersect(sorted[j], d)
                if (idom[b] != d) {
                    idom[b] = d
                    changed = true
                }
            }
        }
        idom.remove(VIRTUAL_ROOT)
        return idom.toSortedMap()
    }

    fun retainedSizes(doc: SnapshotDoc, idom: Map<String, String>): Pair<Map<String, Long>, List<String>> {
        val byId = doc.objects.associateBy { it.objectId }
        val children = HashMap<String, MutableList<String>>()
        for ((node, parent) in idom) children.getOrPut(parent) { mutableListOf() }.add(node)
        val unpriced = mutableListOf<String>()
        val shallow = HashMap<String, Long>()
        for (id in idom.keys) {
            val obj = byId[id]
            val bytes = obj?.let { Units.normalizeSize(it.size) }
            if (bytes == null) unpriced += id else shallow[id] = bytes
        }
        val depth = HashMap<String, Int>()
        fun depthOf(v: String): Int = depth.getOrPut(v) {
            val p = idom[v]
            if (p == null || p == v) 0 else depthOf(p) + 1
        }
        idom.keys.forEach { depthOf(it) }
        val retained = HashMap<String, Long>()
        for (n in idom.keys.sortedByDescending { depth[it] }) {
            retained[n] = shallow[n] ?: 0L
            for (c in children[n].orEmpty()) retained[n] = retained[n]!! + (retained[c] ?: 0L)
        }
        return retained.toSortedMap() to unpriced.sorted()
    }

    /**
     * 保留从任意 root 出发的全部最短路径；多个等长 root 路径都保留。
     */
    fun rootPaths(doc: SnapshotDoc, params: ParamSet, reachable: Set<String>): ComputeOutcome<Map<String, List<List<String>>>> {
        val g = buildGraph(doc)
        fun allowed(e: EdgeRec) = when (e.kind) {
            EdgeKind.STRONG, EdgeKind.INTERNAL -> true
            EdgeKind.WEAK -> params.weakPolicy == WeakPolicy.WEAK_KEEP_ALIVE
        }
        val depth = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        for (r in g.roots.sorted()) if (r in reachable) {
            depth[r] = 0
            queue.addLast(r)
        }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for (e in g.outgoing[u].orEmpty()) {
                if (!allowed(e) || e.to !in reachable) continue
                if (e.to !in depth) {
                    depth[e.to] = depth[u]!! + 1
                    queue.addLast(e.to)
                }
            }
        }
        val paths = HashMap<String, List<List<String>>>()
        var totalPaths = 0L
        for (id in depth.keys.sortedWith(compareBy({ depth[it] }, { it }))) {
            val cur = if (depth[id] == 0) listOf(listOf(id))
            else g.incoming[id].orEmpty()
                .filter { allowed(it) && it.from in reachable && depth[it.from] == depth[id]!! - 1 }
                .flatMap { pe -> paths[pe.from].orEmpty().map { it + id } }
            totalPaths += cur.size
            if (totalPaths > params.nodeBudget * 8L)
                return ComputeOutcome.BudgetExhausted(
                    scope = "rootPaths:${doc.snapshotId}",
                    limit = params.nodeBudget * 8,
                    consumed = totalPaths
                )
            paths[id] = cur
        }
        return ComputeOutcome.Ok(paths.toSortedMap())
    }

    /** 要求唯一结论时，多合法候选 -> 语义无解（而不是挑一个伪装确定）。 */
    fun uniqueLeakPath(objectId: String, all: Map<String, List<List<String>>>): ComputeOutcome<List<String>> {
        val ps = all[objectId].orEmpty()
        return when {
            ps.isEmpty() -> ComputeOutcome.SemanticUnsolvable(
                reason = "对象 $objectId 不存在任何可达 root 路径，无法判定为泄漏候选",
                candidates = emptyList()
            )
            ps.size == 1 -> ComputeOutcome.Ok(ps.first())
            else -> ComputeOutcome.SemanticUnsolvable(
                reason = "对象 $objectId 存在 ${ps.size} 条等长 root 路径，无法唯一决定，保留全部合法候选",
                candidates = ps.map { it.joinToString(" -> ") }
            )
        }
    }

    fun layout(reachable: Set<String>, depth: Map<String, Int>): Map<String, LayoutNode> {
        val slotsPerDepth = HashMap<Int, Int>()
        return reachable.sortedWith(compareBy({ depth[it] ?: Int.MAX_VALUE }, { it })).associate { id ->
            val d = depth[id] ?: 0
            val slot = slotsPerDepth.getOrDefault(d, 0)
            slotsPerDepth[d] = slot + 1
            id to LayoutNode(id, x = d * 180 + 40, y = slot * 72 + 40, depth = d, slot = slot)
        }
    }

    fun analyze(doc: SnapshotDoc, params: ParamSet): ComputeOutcome<AnalysisResult> {
        val reachOutcome = reachable(doc, params)
        val reach = when (reachOutcome) {
            is ComputeOutcome.Ok -> reachOutcome.value
            is ComputeOutcome.BudgetExhausted -> return reachOutcome
            is ComputeOutcome.SemanticUnsolvable -> return reachOutcome
        }
        val idom = dominators(doc, params, reach)
        val (retained, unpriced) = retainedSizes(doc, idom)
        val pathsOutcome = rootPaths(doc, params, reach)
        val paths = when (pathsOutcome) {
            is ComputeOutcome.Ok -> pathsOutcome.value
            is ComputeOutcome.BudgetExhausted -> return pathsOutcome
            is ComputeOutcome.SemanticUnsolvable -> return pathsOutcome
        }
        val depth = paths.mapValues { (_, ps) -> ps.first().size - 1 }
        val byId = doc.objects.associateBy { it.objectId }
        val diagnostics = reachOutcome.diagnostics.toMutableList()
        for (id in unpriced) {
            val raw = byId[id]?.size
            diagnostics += Diagnostic(
                position = "object:$id.size",
                severity = "warning",
                message = "大小 ${raw?.raw} ${raw?.unit} 无法转换为字节基准，原值保留，不计入保留大小"
            )
        }
        val result = AnalysisResult(
            snapshotId = doc.snapshotId,
            inputDigest = Engine.canonicalDigest(doc),
            paramVersion = params.version,
            weakPolicy = params.weakPolicy,
            reachable = reach.sorted(),
            unreachable = (buildGraph(doc).ids - reach).sorted(),
            idom = idom,
            retainedBytes = retained,
            unpricedObjects = unpriced,
            rootPaths = paths,
            layout = layout(reach, depth),
            diagnostic = diagnostics
        )
        return ComputeOutcome.Ok(result, diagnostics)
    }

    /**
     * 两个快照的类群差异：身份以 classId 为准，renamedFrom 表示改名，而不是删除+新增。
     */
    fun classDiff(
        a: SnapshotDoc,
        b: SnapshotDoc,
        retainedA: Map<String, Long>,
        retainedB: Map<String, Long>
    ): List<ClassGroupDiff> {
        val classA = a.classes.associateBy { it.classId }
        val classB = b.classes.associateBy { it.classId }
        val objCountA = a.objects.groupingBy { it.classId }.eachCount()
        val objCountB = b.objects.groupingBy { it.classId }.eachCount()
        fun retainedByClass(doc: SnapshotDoc, ret: Map<String, Long>) =
            doc.objects.groupBy { it.classId }.mapValues { (_, os) -> os.sumOf { ret[it.objectId] ?: 0L } }
        val retA = retainedByClass(a, retainedA)
        val retB = retainedByClass(b, retainedB)

        val bToA = HashMap<String, String?>()
        for (cb in b.classes) {
            bToA[cb.classId] = when {
                cb.renamedFrom != null && cb.renamedFrom in classA -> cb.renamedFrom
                cb.classId in classA -> cb.classId
                else -> null
            }
        }
        val identities = sortedSetOf<String>()
        identities += classA.keys
        for (cb in b.classes) identities += bToA[cb.classId] ?: "b:${cb.classId}"
        return identities.map { identity ->
            val ca = classA[identity]
            val cb = b.classes.firstOrNull { bToA[it.classId] == identity }
            val nA = ca?.let { objCountA[it.classId] ?: 0 } ?: 0
            val nB = cb?.let { objCountB[it.classId] ?: 0 } ?: 0
            val change = when {
                ca != null && cb != null && ca.name != cb.name -> "RENAMED"
                ca != null && cb == null -> "REMOVED"
                ca == null && cb != null -> "ADDED"
                nA > nB -> "SHRUNK"
                nA < nB -> "GROWN"
                else -> "UNCHANGED"
            }
            ClassGroupDiff(
                classIdentity = identity,
                nameA = ca?.name,
                nameB = cb?.name,
                renamedFrom = cb?.renamedFrom,
                countA = nA,
                countB = nB,
                retainedA = ca?.let { retA[it.classId] },
                retainedB = cb?.let { retB[it.classId] },
                change = change
            )
        }
    }
}
