package heapsnapshot.analysis

import heapsnapshot.domain.*
import java.security.MessageDigest

/** 一个对象被某条边“保活”的判定（业务规则在服务端）。 */
private fun Edge.keepsAlive(policy: AnalysisPolicy): Boolean = when (kind) {
    EdgeKind.STRONG -> true
    EdgeKind.WEAK -> policy.weakKeepsAlive
    EdgeKind.INTERNAL -> policy.internalKeepsAlive
    EdgeKind.UNKNOWN -> policy.unknownKeepsAlive
}

class AnalysisEngine {

    /**
     * @param createdEventId 触发本次计算的事件 id；结果有效性区间挂在事件链上。
     * @param dependencyIds 父级依赖（通常是导入事件的结果 id）。
     */
    fun analyze(
        snapshot: RawSnapshot,
        policy: AnalysisPolicy,
        createdEventId: String,
        resultId: String = "analysis:" + snapshot.snapshotId + ":" + policy.policyVersion + ":" + createdEventId,
        dependencyIds: List<String>
    ): AnalysisResult {
        val diags = mutableListOf<ParseDiagnostic>()
        val vRoot = SnapshotRef.virtualRoot(snapshot.snapshotId).localId

        // 1) 保活邻接表（确定性顺序：按 to objectId）。
        val keepEdges = snapshot.edges.filter { it.keepsAlive(policy) }
        val adjacency = LinkedHashMap<String, MutableList<String>>()
        val incoming = LinkedHashMap<String, MutableList<String>>()
        snapshot.objects.forEach { adjacency[it.objectId] = mutableListOf(); incoming[it.objectId] = mutableListOf() }
        keepEdges.forEach { e ->
            if (adjacency.containsKey(e.fromObjectId) && adjacency.containsKey(e.toObjectId)) {
                adjacency[e.fromObjectId]!!.add(e.toObjectId)
            }
        }
        adjacency[vRoot] = mutableListOf()
        // 虚拟 root -> 每个 root 记录指向的对象；多个 root 指向同一对象会产生多个等长 root 路径。
        val rootTargets = snapshot.roots.map { it.objectId }.filter { adjacency.containsKey(it) }
        rootTargets.forEach { adjacency[vRoot]!!.add(it) }
        adjacency.values.forEach { it.sort() }
        // incoming 只从最终保活邻接表构建一次，保证路径不重复。
        adjacency.forEach { (u, vs) -> vs.forEach { incoming.getOrPut(it) { mutableListOf() }.add(u) } }
        incoming[vRoot] = mutableListOf()

        // 2) 可达性：支配树只在可达图上构建。
        val reachable = LinkedHashSet<String>()
        val stack = ArrayDeque<String>().also { it.add(vRoot) }
        while (stack.isNotEmpty()) {
            val u = stack.removeLast()
            if (!reachable.add(u)) continue
            adjacency[u]?.forEach { stack.add(it) }
        }
        val reachableObjects = reachable.filter { it != vRoot }.sorted()
        val unreachableObjects = snapshot.objects.map { it.objectId }.filter { it !in reachable }.sorted()
        if (unreachableObjects.isNotEmpty())
            diags += ParseDiagnostic(Severity.INFO, "UNREACHABLE_OBJECTS",
                "${unreachableObjects.size} 个对象在当前弱引用策略下不可达，不进入支配树",
                "$.analysis.reachable")

        // 3) 仅保留可达边的支配树（CHK 迭代算法，循环安全）。
        val idom = dominators(vRoot, reachableObjects.toSet(), adjacency)

        // 4) 保留大小 = 自身 shallow + 支配子树汇总。无环支配树，倒序汇总安全。
        val shallow = HashMap<String, Double>()
        val selfIncomplete = HashMap<String, Boolean>()
        snapshot.objects.forEach { o ->
            shallow[o.objectId] = (o.shallowSize.value ?: 0.0).let { v ->
                if (o.shallowSize.unit != null && o.shallowSize.unit != snapshot.header.sizeUnitCanonical)
                    convertToCanonical(v, o.shallowSize.unit!!, snapshot.header.sizeUnitCanonical) else v
            }
            selfIncomplete[o.objectId] = o.incomplete || !o.shallowSize.isConvertible
        }
        val children = HashMap<String, MutableList<String>>()
        idom.forEach { (node, parent) ->
            if (node != vRoot) children.getOrPut(parent) { mutableListOf() }.add(node)
        }
        val retained = HashMap<String, Double>()
        val retainedIncomplete = HashMap<String, Boolean>()
        fun compute(node: String) {
            var sum = shallow[node] ?: 0.0
            var inc = selfIncomplete[node] ?: false
            children[node]?.sorted()?.forEach { c -> compute(c); sum += retained[c]!!; inc = inc || (retainedIncomplete[c] ?: false) }
            retained[node] = sum
            retainedIncomplete[node] = inc
        }
        compute(vRoot)

        // 5) 到虚拟 root 的距离与全部最短（等长）root 路径。
        val distance = bfsDistance(vRoot, adjacency, reachable)
        val rootPaths = HashMap<String, List<List<String>>>()
        reachableObjects.forEach { id ->
            val paths = allShortestPaths(id, vRoot, incoming, distance, policy.maxRootPathsPerObject, idom)
            rootPaths[id] = paths
        }

        // 6) 泄漏候选：保留大小超阈值且通过支配关系独自保留下游；等距多 root 时仅标注不唯一。
        val leak = reachableObjects.filter { id ->
            (retained[id] ?: 0.0) >= policy.leakRetainedMinBytes &&
                (children[id]?.isNotEmpty() ?: false)
        }

        val basis = ResultBasis(
            sizeUnit = snapshot.header.sizeUnitCanonical,
            coordinateBasis = "address:${snapshot.header.addressUnit}",
            coordinateUnit = snapshot.header.addressUnit,
            timeBasis = snapshot.header.timeBasis,
            takenAtEpochMs = snapshot.header.takenAtEpochMs,
            weakKeepsAlive = policy.weakKeepsAlive,
            internalKeepsAlive = policy.internalKeepsAlive
        )

        val nodes = reachableObjects.map { id ->
            DerivedNode(
                ref = SnapshotRef.obj(snapshot.snapshotId, id),
                objectId = id,
                idomParent = idom[id]?.let { if (it == vRoot) SnapshotRef.virtualRoot(snapshot.snapshotId) else SnapshotRef.obj(snapshot.snapshotId, it) },
                retainedBytes = retained[id] ?: 0.0,
                retainedIncomplete = retainedIncomplete[id] ?: false,
                selfIncomplete = selfIncomplete[id] ?: false,
                rootDistance = (distance[id] ?: 0),
                rootPaths = rootPaths[id].orEmpty(),
                leakCandidate = id in leak
            )
        }
        snapshot.objects.filter { it.incomplete }.forEach { o ->
            diags += ParseDiagnostic(Severity.WARNING, "INCOMPLETE_RETAINED_SIZE",
                "对象 ${o.objectId} 不完整或大小不可转换，其保留大小是下界并已标注", "$.objects(${o.objectId})", o.objectId)
        }
        return AnalysisResult(
            resultId = resultId,
            snapshotId = snapshot.snapshotId,
            policyVersion = policy.policyVersion,
            inputSummary = InputSummary(
                snapshot.objects.size, snapshot.edges.size, snapshot.roots.size, snapshot.classes.size,
                materialHash(snapshot)
            ),
            parentDependencyIds = dependencyIds,
            createdAtEventId = createdEventId,
            reachableObjectIds = reachableObjects,
            unreachableObjectIds = unreachableObjects,
            nodes = nodes.sortedBy { it.objectId },
            leakCandidates = leak.sorted(),
            diagnostics = snapshot.diagnostics + diags,
            validFromEventId = createdEventId,
            basis = basis
        )
    }

    // ---- Cooper / Harvey / Kennedy 迭代支配者，天然处理循环图 ----
    private fun dominators(
        vRoot: String,
        nodes: Set<String>,
        adj: Map<String, List<String>>
    ): Map<String, String> {
        val all = nodes + vRoot
        // 从 vRoot 的 DFS 逆后序
        val visited = LinkedHashSet<String>()
        val postorder = mutableListOf<String>()
        fun dfs(u: String) {
            if (!visited.add(u)) return
            adj[u]?.sorted()?.forEach { if (it in all) dfs(it) }
            postorder += u
        }
        dfs(vRoot)
        val rpo = postorder.reversed()
        val rpoIndex = rpo.withIndex().associate { (i, n) -> n to i }
        val idom = HashMap<String, String>()
        idom[vRoot] = vRoot
        var changed = true
        fun intersect(aIn: String, bIn: String): String {
            var finger1 = aIn; var finger2 = bIn
            while (finger1 != finger2) {
                while (rpoIndex[finger1]!! > rpoIndex[finger2]!!) finger1 = idom[finger1]!!
                while (rpoIndex[finger2]!! > rpoIndex[finger1]!!) finger2 = idom[finger2]!!
            }
            return finger1
        }
        while (changed) {
            changed = false
            for (b in rpo.drop(1)) {
                val preds = predWithin(b, vRoot, adj, all)
                val processed = preds.filter { idom.containsKey(it) }
                if (processed.isEmpty()) continue
                var newIdom = processed.first()
                for (p in processed.drop(1)) newIdom = intersect(p, newIdom)
                if (idom[b] != newIdom) { idom[b] = newIdom; changed = true }
            }
        }
        return idom.filterKeys { it != vRoot }
    }

    private fun predWithin(b: String, vRoot: String, adj: Map<String, List<String>>, all: Set<String>): List<String> {
        val result = mutableListOf<String>()
        if (b in adj[vRoot]) result += vRoot
        adj.forEach { (u, vs) -> if (u != vRoot && b in vs && u in all) result += u }
        return result.sorted()
    }

    private fun bfsDistance(vRoot: String, adj: Map<String, List<String>>, reachable: Set<String>): Map<String, Int> {
        val dist = HashMap<String, Int>(); dist[vRoot] = 0
        val q = ArrayDeque<String>(); q.add(vRoot)
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            adj[u]?.forEach { v ->
                if (v in reachable && v !in dist) { dist[v] = dist[u]!! + 1; q.add(v) }
            }
        }
        return dist
    }

    /** 枚举从 start 到 vRoot 的所有最短路径；返回路径（从 vRoot 到 start 的 objectId 序列）。 */
    private fun allShortestPaths(
        start: String, vRoot: String, incoming: Map<String, List<String>>,
        distance: Map<String, Int>, cap: Int, idom: Map<String, String>
    ): List<List<String>> {
        val out = mutableListOf<List<String>>()
        fun walk(node: String, tailReversed: MutableList<String>) {
            if (out.size >= cap) return
            if (node == vRoot) { out += (listOf(vRoot) + tailReversed.reversed()).filter { it != vRoot }; return }
            val d = distance[node] ?: return
            val preds = (incoming[node].orEmpty()).filter { distance[it] == d - 1 }.sorted()
            preds.forEach { p ->
                tailReversed.add(node)
                walk(p, tailReversed)
                tailReversed.removeAt(tailReversed.lastIndex)
            }
        }
        walk(start, mutableListOf())
        return out
    }

    private fun convertToCanonical(value: Double, from: String, to: String): Double {
        val scale = mapOf("B" to 1.0, "KB" to 1024.0, "MB" to 1024.0 * 1024, "GB" to 1024.0 * 1024 * 1024)
        val f = scale[from.uppercase()] ?: 1.0
        val t = scale[to.uppercase()] ?: 1.0
        return value * f / t
    }

    private fun materialHash(snapshot: RawSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun feed(s: String) { digest.update(s.toByteArray()) }
        feed(snapshot.header.snapshotId); feed(snapshot.header.parserVersion)
        snapshot.classes.sortedBy { it.classId }.forEach { feed(it.classId); feed(it.name) }
        snapshot.objects.sortedBy { it.objectId }.forEach {
            feed(it.objectId); feed(it.address.raw); feed(it.classId); feed(it.shallowSize.raw)
        }
        snapshot.edges.sortedBy { it.edgeId }.forEach {
            feed(it.edgeId); feed(it.fromObjectId); feed(it.toObjectId); feed(it.kind.name); feed(it.label ?: "")
        }
        snapshot.roots.sortedBy { it.rootId }.forEach { feed(it.rootId); feed(it.objectId); feed(it.kindRaw) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
