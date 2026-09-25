package heapsleuth

/**
 * 分析引擎。规则：
 *  - 支配树只在可达图上构建；
 *  - 弱引用是否保活由显式 weakPolicy 决定；
 *  - 多个等长 root 路径全部保留（直到 pathBudget）；
 *  - 地址不作为稳定身份，身份 = (snapshotId, objectId)。
 */
object Engine {
    const val VROOT = "@root"

    fun analyze(bundle: SnapshotBundle, params: AnalysisParams): RunOutcome {
        val diags = mutableListOf<Diagnostic>()
        diags += bundle.diagnostics

        val objIds = bundle.objects.map { it.objectId }.toSet()

        // 丢弃端点缺失的边（记录诊断，不用空值伪装）。
        val droppedEdges = mutableListOf<String>()
        val activeEdges = bundle.edges.filter { e ->
            val ok = e.from in objIds && e.to in objIds
            if (!ok) {
                droppedEdges += e.edgeId
                diags += Diagnostic("/edges/${e.edgeId}", "EDGE_ENDPOINT_MISSING",
                    "边 ${e.edgeId} (${e.from} -> ${e.to}) 端点缺失，已丢弃", Severity.WARNING)
            }
            ok
        }

        // root 必须指向存在的对象；全部缺失 => 语义无解。
        val validRoots = mutableListOf<HeapRoot>()
        bundle.roots.forEach { r ->
            if (r.objectId in objIds) validRoots += r
            else diags += Diagnostic("/roots/${r.rootId}", "ROOT_TARGET_MISSING",
                "root ${r.rootId} (${r.rootType}) 指向缺失对象 ${r.objectId}", Severity.ERROR)
        }
        if (validRoots.isEmpty()) {
            diags += Diagnostic("/roots", "NO_VALID_ROOT", "没有任何指向现存对象的 root，无法构建可达图", Severity.ERROR)
            return RunOutcome.SemanticUnsolvable(diags)
        }

        fun active(e: HeapEdge): Boolean = when (e.kind) {
            EdgeKind.STRONG -> true
            EdgeKind.INTERNAL -> params.internalEdgesStrong
            EdgeKind.WEAK -> params.weakPolicy == WeakPolicy.RETAIN
        }
        val usable = activeEdges.filter { active(it) }
        val adj = mutableMapOf<String, MutableList<String>>()
        usable.forEach { adj.getOrPut(it.from) { mutableListOf() }.add(it.to) }
        adj.values.forEach { it.sort() } // 等价输入 => 稳定顺序

        // 可达性 BFS（带节点预算）。
        val reachable = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        validRoots.map { it.objectId }.distinct().sorted().forEach {
            if (reachable.add(it)) queue.add(it)
        }
        while (queue.isNotEmpty()) {
            if (reachable.size > params.nodeBudget) {
                return RunOutcome.BudgetExhausted(reachable.size, params.nodeBudget,
                    "可达节点数超过预算 ${params.nodeBudget}")
            }
            val u = queue.removeFirst()
            for (v in adj[u].orEmpty()) if (reachable.add(v)) queue.add(v)
        }
        val unreachable = (objIds - reachable).sorted()
        unreachable.forEach { id ->
            diags += Diagnostic("/objects/$id", "UNREACHABLE",
                "对象 $id 在当前边策略下不可达，不参与支配树与保留大小", Severity.INFO)
        }

        // 支配树：只在可达图上构建（虚拟根 VROOT -> 各 root 对象）。
        val rootObjs = validRoots.map { it.objectId }.distinct().sorted()
        val idom = computeIdom(rootObjs, reachable, adj)

        // 保留大小：idom 树上的子树和；不完整对象向上传播 partial。
        val sizeOf = bundle.objects.associate { it.objectId to it.sizeBytes }
        val incompleteSet = bundle.objects.filter { it.incomplete || it.sizeBytes == null }
            .map { it.objectId }.toSet()
        incompleteSet.filter { it in reachable }.forEach { id ->
            diags += Diagnostic("/objects/$id/size", "INCOMPLETE_SIZE",
                "对象 $id 大小未知（incomplete），其保留大小为部分值", Severity.WARNING)
        }
        val children = mutableMapOf<String, MutableList<String>>()
        idom.forEach { (node, parent) -> children.getOrPut(parent) { mutableListOf() }.add(node) }
        children.values.forEach { it.sort() }
        val retained = mutableMapOf<String, Retained>()
        fun retainedOf(node: String): Retained {
            retained[node]?.let { return it }
            var bytes = sizeOf[node] ?: 0L
            var partial = node in incompleteSet
            val reasons = mutableListOf<String>()
            if (node in incompleteSet) reasons += "self-incomplete"
            for (c in children[node].orEmpty()) {
                val r = retainedOf(c)
                bytes += r.bytes
                if (r.partial) {
                    partial = true
                    reasons += "via:$c"
                }
            }
            val r = Retained(bytes, partial, reasons)
            retained[node] = r
            return r
        }
        reachable.forEach { retainedOf(it) }

        // 全部最短 root 路径（等长并列保留，受 pathBudget 限制）。
        val (rootPaths, truncated) = shortestRootPaths(rootObjs, reachable, adj, params.pathBudget)
        truncated.forEach { id ->
            diags += Diagnostic("/objects/$id", "PATHS_TRUNCATED",
                "对象 $id 的等长 root 路径超过 pathBudget=${params.pathBudget}，已截断", Severity.WARNING)
        }

        // 泄漏路径候选：保留大小达到阈值的支配节点。
        val candidates = reachable
            .filter { (retained[it]?.bytes ?: 0L) >= params.leakThresholdBytes }
            .map { id ->
                LeakCandidate(
                    key = "cand:$id",
                    objectId = id,
                    retainedBytes = retained[id]?.bytes ?: 0L,
                    partial = retained[id]?.partial ?: false,
                    paths = rootPaths[id] ?: emptyList(),
                )
            }
            .sortedWith(compareByDescending<LeakCandidate> { it.retainedBytes }.thenBy { it.objectId })

        return RunOutcome.Ok(
            AnalysisResult(
                reachable = reachable.sorted(),
                unreachable = unreachable,
                idom = idom.toSortedMap(),
                retained = retained.toSortedMap(),
                rootPaths = rootPaths.toSortedMap(),
                pathsTruncated = truncated.sorted(),
                droppedEdges = droppedEdges.sorted(),
                diagnostics = diags.sortedWith(compareBy({ it.path }, { it.code })),
                candidates = candidates,
            )
        )
    }

    /** Cooper-Harvey-Kennedy 迭代支配算法，作用域 = 可达子图。 */
    internal fun computeIdom(
        roots: List<String>,
        reachable: Set<String>,
        adj: Map<String, List<String>>,
    ): Map<String, String> {
        val preds = mutableMapOf<String, MutableList<String>>()
        val fullAdj = mutableMapOf<String, List<String>>()
        fullAdj[VROOT] = roots
        reachable.forEach { n -> fullAdj[n] = (adj[n] ?: emptyList()).filter { it in reachable } }
        fullAdj.forEach { (u, vs) -> vs.forEach { v -> preds.getOrPut(v) { mutableListOf() }.add(u) } }

        // 逆后序
        val postorder = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val stack = ArrayDeque<Pair<String, Iterator<String>>>()
        stack.addLast(VROOT to fullAdj[VROOT]!!.iterator())
        visited += VROOT
        while (stack.isNotEmpty()) {
            val (node, it) = stack.last()
            if (it.hasNext()) {
                val next = it.next()
                if (visited.add(next)) stack.addLast(next to fullAdj[next].orEmpty().iterator())
            } else {
                postorder += node
                stack.removeLast()
            }
        }
        val rpo = postorder.reversed()
        val rpoIndex = rpo.withIndex().associate { it.value to it.index }

        val idom = mutableMapOf<String, String>()
        idom[VROOT] = VROOT

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
            for (b in rpo) {
                if (b == VROOT) continue
                val ps = preds[b].orEmpty().filter { it == VROOT || idom.containsKey(it) }
                if (ps.isEmpty()) continue
                var newIdom = ps.first()
                for (p in ps.drop(1)) newIdom = intersect(newIdom, p)
                if (idom[b] != newIdom) {
                    idom[b] = newIdom
                    changed = true
                }
            }
        }
        // 输出：对象 -> 支配父级（root 对象的父级为 VROOT）。
        return reachable.associateWith { idom[it] ?: VROOT }.toSortedMap()
    }

    /** BFS 求每个可达对象的全部最短 root 路径（等长并列保留）。 */
    internal fun shortestRootPaths(
        roots: List<String>,
        reachable: Set<String>,
        adj: Map<String, List<String>>,
        pathBudget: Int,
    ): Pair<Map<String, List<List<String>>>, List<String>> {
        val depth = mutableMapOf<String, Int>()
        val paths = mutableMapOf<String, MutableList<List<String>>>()
        val truncated = mutableListOf<String>()
        roots.forEach { r ->
            depth[r] = 0
            paths[r] = mutableListOf(listOf(r))
        }
        val queue = ArrayDeque<String>()
        roots.forEach { queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            val du = depth.getValue(u)
            val pu = paths[u].orEmpty()
            for (v in adj[u].orEmpty()) {
                if (v !in reachable) continue
                val dv = depth[v]
                if (dv == null) {
                    depth[v] = du + 1
                    paths[v] = pu.map { it + v }.take(pathBudget).toMutableList()
                    if (pu.size > pathBudget) truncated += v
                    queue.addLast(v)
                } else if (dv == du + 1) {
                    val list = paths.getValue(v)
                    for (p in pu) {
                        if (list.size >= pathBudget) {
                            if (v !in truncated) truncated += v
                            break
                        }
                        list.add(p + v)
                    }
                }
            }
        }
        return paths.mapValues { it.value.toList() } to truncated
    }

    /** 两个快照的类群差异：按 classId 对应身份（类改名仍对应同一身份）。 */
    fun diffClassGroups(a: SnapshotBundle, b: SnapshotBundle): List<ClassGroupDiff> {
        fun group(s: SnapshotBundle) = s.objects.groupBy { it.classId }
        val ga = group(a); val gb = group(b)
        val classIds = (ga.keys + gb.keys).sorted()
        return classIds.map { cid ->
            val oa = ga[cid].orEmpty(); val ob = gb[cid].orEmpty()
            val nameA = a.classes.firstOrNull { it.classId == cid }?.name
            val nameB = b.classes.firstOrNull { it.classId == cid }?.name
            val bytesA = oa.sumOf { it.sizeBytes ?: 0L }
            val bytesB = ob.sumOf { it.sizeBytes ?: 0L }
            val flags = mutableListOf<String>()
            if (oa.isEmpty()) flags += "ADDED"
            if (ob.isEmpty()) flags += "REMOVED"
            if (nameA != null && nameB != null && nameA != nameB) flags += "RENAMED"
            if (oa.isNotEmpty() && ob.isNotEmpty() && oa.size != ob.size) flags += "COUNT_CHANGED"
            if (oa.isNotEmpty() && ob.isNotEmpty() && bytesA != bytesB) flags += "SIZE_CHANGED"
            if (flags.isEmpty()) flags += "UNCHANGED"
            ClassGroupDiff(cid, nameA, nameB, oa.size.toLong(), ob.size.toLong(), bytesA, bytesB, flags)
        }
    }
}
