package heaplab

/**
 * 核心判定：
 *  1. 支配树只在可达图上构建（弱边按显式策略决定是否参与可达性）；
 *  2. 弱引用是否保活由显式策略决定（未指定 -> SEMANTIC_UNSOLVABLE）；
 *  3. 多个等长 root 路径全部保留（超出预算上限时截断并诊断）；
 *  4. 地址不当身份：引擎只使用 objectId。
 * 所有集合按稳定 id 排序，等价输入产生稳定顺序。
 */
object Analysis {

    fun run(s: Snapshot, p: AnalysisParams): AnalysisOutcome {
        val diags = mutableListOf<Diagnostic>()

        if (p.weakRefsKeepAlive == null) {
            return AnalysisOutcome(
                RunStatus.SEMANTIC_UNSOLVABLE,
                null,
                listOf(Diagnostic("$.params.weakRefsKeepAlive", "弱引用是否保活必须由显式策略决定，拒绝使用隐式默认", Severity.ERROR)),
            )
        }

        val objectsById = s.objects.associateBy { it.objectId }
        val keepEdge: (HeapEdge) -> Boolean = { e ->
            when (e.kind) {
                EdgeKind.STRONG -> true
                EdgeKind.INTERNAL -> p.internalEdgesKeepAlive
                EdgeKind.WEAK -> p.weakRefsKeepAlive
            }
        }
        val outEdges = s.objects.associate { o ->
            o.objectId to s.edges.filter { it.fromId == o.objectId && keepEdge(it) }
                .sortedWith(compareBy({ it.toId }, { it.edgeId }))
        }

        // 可达性 BFS（根按稳定顺序排序；预算耗尽是独立结果）
        val reachable = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        val rootSet = s.roots.map { it.objectId }.distinct().sorted()
        rootSet.forEach { if (reachable.add(it)) queue.addLast(it) }
        var exhausted = false
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (e in outEdges[node].orEmpty()) {
                if (reachable.add(e.toId)) {
                    if (reachable.size > p.maxVisitedNodes) {
                        exhausted = true
                        break
                    }
                    queue.addLast(e.toId)
                }
            }
            if (exhausted) break
        }
        if (exhausted) {
            return AnalysisOutcome(
                RunStatus.BUDGET_EXHAUSTED,
                null,
                listOf(Diagnostic("$.params.maxVisitedNodes", "资源预算耗尽：已访问 ${reachable.size} 个节点，超过上限 ${p.maxVisitedNodes}；支配树未构建", Severity.ERROR)),
            )
        }

        // 支配树（Cooper-Harvey-Kennedy），只在可达图上，加虚拟根连接所有 root
        val succ = reachable.sorted().associateWith { outEdges[it].orEmpty().map { e -> e.toId }.filter { t -> t in reachable }.distinct() }
        val pred = HashMap<String, MutableList<String>>()
        succ.forEach { (from, tos) -> tos.forEach { to -> pred.getOrPut(to) { mutableListOf() }.add(from) } }
        rootSet.forEach { r -> pred.getOrPut(r) { mutableListOf() }.add(AnalysisResult.VIRTUAL_ROOT) }
        val allNodes = listOf(AnalysisResult.VIRTUAL_ROOT) + reachable.toList()

        val rpo = reversePostorder(AnalysisResult.VIRTUAL_ROOT, succ, allNodes.toSet(), rootSet)
        val rpoIndex = rpo.withIndex().associate { (i, n) -> n to i }
        val idom = HashMap<String, String>()
        idom[AnalysisResult.VIRTUAL_ROOT] = AnalysisResult.VIRTUAL_ROOT
        var changed = true
        while (changed) {
            changed = false
            for (node in rpo.drop(1)) {
                val preds = pred[node].orEmpty().filter { it in idom && rpoIndex.containsKey(it) }
                    .sortedBy { if (it == AnalysisResult.VIRTUAL_ROOT) -1 else rpoIndex.getValue(it) }
                val first = preds.firstOrNull() ?: continue
                var newIdom = first
                for (other in preds.drop(1)) {
                    newIdom = intersect(other, newIdom, idom, rpoIndex)
                }
                if (idom[node] != newIdom) {
                    idom[node] = newIdom
                    changed = true
                }
            }
        }
        val idomParent = reachable.associateWith { idom.getValue(it) }.toSortedMap()

        // 保留大小 = 支配子树大小之和
        val children = HashMap<String, MutableList<String>>()
        idomParent.forEach { (child, parent) -> children.getOrPut(parent) { mutableListOf() }.add(child) }
        val selfSize = s.objects.filter { it.objectId in reachable }.associate { it.objectId to (it.sizeBytes ?: 0L) }
        val unconvertible = s.objects.filter { it.objectId in reachable && it.sizeBytes == null }.map { it.objectId }.toSortedSet()
        if (unconvertible.isNotEmpty())
            diags.add(Diagnostic("$.objects.size", "以下对象大小无法转换，保留大小按 0 计入但不丢弃：$unconvertible", Severity.WARNING))
        val retained = HashMap<String, Long>()
        fun compute(node: String): Long {
            var total = selfSize[node] ?: 0L
            for (child in children[node].orEmpty().sorted()) total += compute(child)
            retained[node] = total
            return total
        }
        compute(AnalysisResult.VIRTUAL_ROOT)
        val retainedByObject = reachable.associateWith { retained.getValue(it) }.toSortedMap()

        // 深度
        val depth = HashMap<String, Int>()
        for (node in rpo.drop(1)) depth[node] = (depth[idomParent[node]] ?: -1) + 1

        // 全部等长最短 root 路径（BFS 分层 + 前驱枚举，确定性）
        val paths = enumerateShortestPaths(rootSet, outEdges, reachable, p.maxPathsPerObject)
        if (paths.second.isNotEmpty())
            diags.add(Diagnostic("$.params.maxPathsPerObject", "以下对象的等长路径超过上限被截断：${paths.second.toSortedSet()}", Severity.WARNING))

        return AnalysisOutcome(
            RunStatus.OK,
            AnalysisResult(
                reachable = reachable.toSortedSet(),
                idomParent = idomParent,
                retainedBytes = retainedByObject,
                depth = depth.toSortedMap(),
                rootPaths = paths.first,
                pathsTruncated = paths.second.toSortedSet(),
            ),
            diags,
        )
    }

    private fun intersect(a: String, b: String, idom: Map<String, String>, idx: Map<String, Int>): String {
        var finger1 = a
        var finger2 = b
        while (finger1 != finger2) {
            while (idx.getValue(finger1) > idx.getValue(finger2)) finger1 = idom.getValue(finger1)
            while (idx.getValue(finger2) > idx.getValue(finger1)) finger2 = idom.getValue(finger2)
        }
        return finger1
    }

    private fun reversePostorder(start: String, succ: Map<String, List<String>>, nodes: Set<String>, roots: List<String>): List<String> {
        val visited = HashSet<String>()
        val order = ArrayDeque<String>()
        fun dfs(v: String) {
            visited.add(v)
            val next = if (v == start) roots else succ[v].orEmpty()
            for (w in next.sorted()) if (w !in visited && w in nodes) dfs(w)
            order.addFirst(v)
        }
        dfs(start)
        return order.toList()
    }

    /** BFS 距离 + 枚举每个对象的全部最短路径（仅最短、等长），按路径字典序稳定排序。 */
    private fun enumerateShortestPaths(
        roots: List<String>,
        outEdges: Map<String, List<HeapEdge>>,
        reachable: Set<String>,
        cap: Int,
    ): Pair<Map<String, List<List<PathHop>>>, Set<String>> {
        val dist = HashMap<String, Int>()
        val queue = ArrayDeque<String>()
        roots.forEach { if (!dist.containsKey(it)) { dist[it] = 0; queue.addLast(it) } }
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (e in outEdges[v].orEmpty()) {
                if (e.toId in reachable && !dist.containsKey(e.toId)) {
                    dist[e.toId] = dist.getValue(v) + 1
                    queue.addLast(e.toId)
                }
            }
        }
        val memo = HashMap<String, List<List<PathHop>>>()
        val truncated = HashSet<String>()
        fun paths(v: String): List<List<PathHop>> = memo.getOrPut(v) {
            val d = dist[v] ?: return@getOrPut emptyList()
            if (d == 0) return@getOrPut listOf(emptyList())
            val incoming = outEdges.values.flatten().filter { it.toId == v && dist[it.fromId] == d - 1 }
                .sortedWith(compareBy({ it.fromId }, { it.edgeId }))
            val result = mutableListOf<List<PathHop>>()
            for (e in incoming) {
                for (prefix in paths(e.fromId)) {
                    result.add(prefix + PathHop(e.fromId, v, e.edgeId, e.kind))
                    if (result.size > cap) {
                        truncated.add(v)
                        return@getOrPut result.dropLast(1)
                    }
                }
            }
            result.sortedWith(pathComparator)
        }
        val all = reachable.associateWith { paths(it) }
        return all to truncated
    }

    private val pathComparator = Comparator<List<PathHop>> { a, b ->
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a[i]; val y = b[i]
            val c = compareValuesBy(x, y, { it.fromId }, { it.toId }, { it.edgeId })
            if (c != 0) return@Comparator c
        }
        a.size - b.size
    }

    fun classGroups(s: Snapshot, reachable: Set<String>? = null): List<ClassGroup> {
        val scope = if (reachable == null) s.objects else s.objects.filter { it.objectId in reachable }
        return scope.groupBy { it.classId }.map { (classId, objs) ->
            val c = s.classes.first { it.classId == classId }
            val sizes = objs.mapNotNull { it.sizeBytes }
            ClassGroup(classId, c.name, objs.size, if (sizes.size == objs.size) sizes.sum() else null)
        }.sortedBy { it.name }
    }

    /**
     * 两个快照的类群差异。跨快照身份以快照内 classId 为准；
     * 类名相同但 classId 不同不自动合并（类改名场景 -> 一侧出现/消失）。
     */
    fun diffClasses(a: Snapshot, reachableA: Set<String>?, b: Snapshot, reachableB: Set<String>?): ClassGroupDiff {
        val ga = classGroups(a, reachableA)
        val gb = classGroups(b, reachableB)
        val byIdA = ga.associateBy { it.classId }
        val byIdB = gb.associateBy { it.classId }
        val onlyA = ga.filter { it.classId !in byIdB }
        val onlyB = gb.filter { it.classId !in byIdA }
        val changed = ga.filter { it.classId in byIdB }.mapNotNull { x ->
            val y = byIdB.getValue(x.classId)
            if (x.count == y.count && x.totalBytes == y.totalBytes) null
            else ClassGroupChange(x.classId, y.classId, x.name, x.count, y.count, x.totalBytes, y.totalBytes)
        }
        val note = "跨快照仅按快照内 classId 对应；类改名表现为消失+新增。对象地址在快照间可能复用，不作为跨快照身份。"
        return ClassGroupDiff(onlyA, onlyB, changed, note)
    }
}
