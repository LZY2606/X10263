package com.heaptrace.domain

import java.security.MessageDigest

const val VIRTUAL_ROOT = "\$virtual-root"

/**
 * 派生层：在不可变材料上执行分析。
 * 关键规则：
 *  1. 支配树只在可达子图上构建（不可达对象不参与）。
 *  2. 弱引用是否保活由显式 AnalysisPolicy.weakRetains 决定。
 *  3. 多个等长 root 路径全部保留；无法唯一决定时保留全部合法候选。
 *  4. 地址从不参与身份；对象 id 才是稳定身份。
 *  5. 等价输入 -> 稳定排序；预算耗尽是独立失败结果。
 */
class AnalysisEngine(private val budget: Budget = Budget()) {

    private val stableJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    fun inputDigest(bundle: SnapshotBundle): String {
        val canonical = stableJson.encodeToString(SnapshotBundle.serializer(), bundle)
        return sha256(canonical)
    }

    fun analyze(
        bundle: SnapshotBundle,
        policy: AnalysisPolicy = AnalysisPolicy(),
        dependencyEventIds: List<String> = emptyList(),
        importDiagnostics: List<Diagnostic> = emptyList()
    ): AnalysisOutcome {
        val diagnostics = importDiagnostics.toMutableList()
        val objects = bundle.objects.associateBy { it.id }.toSortedMap()
        val objectIds = objects.keys

        val roots = bundle.roots.filter { it.objectId in objectIds }
        val missingRoots = bundle.roots.filter { it.objectId !in objectIds }.map {
            Diagnostic(
                "ROOT_TARGET_MISSING", "WARN",
                "root ${it.id}(${it.type}) 目标对象 ${it.objectId} 不在材料中，分析中忽略该 root",
                Location(bundle.meta.id, "root", it.id), objectId = it.objectId
            )
        }
        diagnostics += missingRoots

        // 1) 按显式策略筛选会保活的边。
        val edges = bundle.edges.filter { it.from in objectIds && it.to in objectIds }
        val keep: (Edge) -> Boolean = { e ->
            when (e.kind) {
                EdgeKind.STRONG -> true
                EdgeKind.INTERNAL -> policy.internalRetains
                EdgeKind.WEAK -> policy.weakRetains
            }
        }
        val adjacency = HashMap<String, MutableList<String>>()
        val adjacencyAll = HashMap<String, MutableList<String>>()
        for (e in edges) {
            adjacencyAll.getOrPut(e.from) { mutableListOf() }.add(e.to)
            if (keep(e)) adjacency.getOrPut(e.from) { mutableListOf() }.add(e.to)
        }
        adjacency.values.forEach { it.sort() }
        adjacencyAll.values.forEach { it.sort() }

        // 2) 可达性 + 全部最短（等长）root 路径前驱表。
        val rootIds = roots.map { it.objectId }.distinct().sorted()
        val dist = HashMap<String, Int>()
        val predecessors = HashMap<String, MutableList<String>>()
        val rootSources = HashMap<String, MutableList<String>>()
        val queue = ArrayDeque<String>()
        for (r in rootIds) {
            if (dist.putIfAbsent(r, 0) == null) {
                queue.add(r)
                rootSources[r] = mutableListOf(r)
            }
        }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            val du = dist.getValue(u)
            for (v in adjacency[u].orEmpty()) {
                val known = dist[v]
                if (known == null) {
                    dist[v] = du + 1
                    predecessors[v] = mutableListOf(u)
                    queue.add(v)
                } else if (known == du + 1) {
                    predecessors.getOrPut(v) { mutableListOf() }.add(u)
                }
            }
        }

        // 等长路径计数（带上限），爆炸即预算耗尽，绝不静默截断成“唯一结论”。
        val pathCount = HashMap<String, Long>()
        for (id in dist.keys.sortedBy { dist[it] }) {
            val preds = predecessors[id]
            if (preds == null) pathCount[id] = 1L
            else {
                var sum = 0L
                for (p in preds.sorted()) sum = minOf(Long.MAX_VALUE, sum + (pathCount[p] ?: 0L))
                pathCount[id] = sum
                if (sum > budget.maxPathsPerObject) {
                    return AnalysisOutcome.BudgetExhausted(
                        listOf(
                            Diagnostic(
                                "BUDGET_PATHS", "FATAL",
                                "对象 ${id} 的等长 root 路径数 $sum 超过预算 ${budget.maxPathsPerObject}",
                                Location(bundle.meta.id, "path-count", id, dist[id] ?: -1),
                                objectId = id
                            )
                        ),
                        stage = "path-enumeration"
                    )
                }
            }
        }

        // 3) 枚举全部等长路径（稳定顺序：按前驱排序后深度优先）。
        val allPaths = HashMap<String, List<List<String>>>()
        for (id in dist.keys.sortedBy { -dist.getValue(it) }) {
            val preds = predecessors[id]
            if (preds == null) {
                allPaths[id] = listOf(listOf(id))
            } else {
                val paths = mutableListOf<List<String>>()
                for (p in preds.sorted()) {
                    for (path in allPaths.getValue(p)) paths += path + id
                }
                allPaths[id] = paths
            }
        }

        // 4) 支配树：只在可达子图上，以虚拟 root 聚合所有 root（Cooper-Harvey-Kennedy）。
        val reachableIds = dist.keys.sorted()
        val reachableSet = dist.keys
        val reachableAdj = adjacency.filterKeys { it in reachableSet }
            .mapValues { (_, vs) -> vs.filter { it in reachableSet } }
        val rpo = rpoFromVirtual(rootIds, reachableAdj)
        val rpoIndex = rpo.withIndex().associate { (i, n) -> n to i }
        val idom = HashMap<String, String>()
        idom[VIRTUAL_ROOT] = VIRTUAL_ROOT
        var changed = true
        while (changed) {
            changed = false
            for (b in rpo.drop(1)) {
                val predsOfB = (reachableAdj.entries.filter { b in it.value }.map { it.key } +
                        if (b in rootIds) listOf(VIRTUAL_ROOT) else emptyList())
                    .filter { it == VIRTUAL_ROOT || it in idom }.distinct().sortedBy { rpoIndex[it] ?: Int.MAX_VALUE }
                if (predsOfB.isEmpty()) continue
                var newIdom = predsOfB.first()
                for (p in predsOfB.drop(1)) {
                    newIdom = intersect(p, newIdom, idom, rpoIndex)
                }
                if (idom[b] != newIdom) {
                    idom[b] = newIdom
                    changed = true
                }
            }
        }
        val domDepth = HashMap<String, Int>()
        domDepth[VIRTUAL_ROOT] = 0
        for (n in rpo.drop(1)) {
            var depth = 0
            var cur: String? = n
            while (cur != null && cur != VIRTUAL_ROOT) {
                depth++
                cur = idom[cur]
            }
            domDepth[n] = depth
        }

        // 5) 保留大小：支配树子树聚合；未知尺寸保留原值并标记不精确。
        val sizes = objects.mapValues { (_, o) -> SizeUnits.parseBytes(o.sizeRaw) }
        val unknownSet = objects.filterValues { sizes[it.key] == null }.keys
        val retained = HashMap<String, Long>()
        val retainedExact = HashMap<String, Boolean>()
        val retainedUnknowns = HashMap<String, MutableList<String>>()
        for (id in reachableIds) {
            retained[id] = sizes[id] ?: 0L
            retainedExact[id] = id !in unknownSet
            if (id in unknownSet) retainedUnknowns[id] = mutableListOf(id)
        }
        val children = HashMap<String, MutableList<String>>()
        for (id in reachableIds) {
            val parent = idom[id]
            if (parent != null && parent != VIRTUAL_ROOT && parent != id) {
                children.getOrPut(parent) { mutableListOf() }.add(id)
            }
        }
        for (id in reachableIds.sortedByDescending { domDepth[it] }) {
            for (child in children[id].orEmpty()) {
                retained[id] = retained.getValue(id) + retained.getValue(child)
                retainedExact[id] = retainedExact.getValue(id) && retainedExact.getValue(child)
                retainedUnknowns.getOrPut(id) { mutableListOf() }
                    .addAll(retainedUnknowns[child].orEmpty())
            }
        }

        // 6) 每对象结论。
        val objectAnalyses = objects.keys.map { id ->
            if (id in reachableSet) {
                val unknowns = retainedUnknowns[id]?.distinct()?.sorted().orEmpty()
                ObjectAnalysis(
                    objectId = id,
                    reachable = true,
                    shallowSizeBytes = sizes[id],
                    retainedSizeBytes = retained.getValue(id),
                    retainedExact = retainedExact.getValue(id),
                    unknownInRetainedSet = unknowns,
                    dominatorParent = idom[id]?.takeIf { it != VIRTUAL_ROOT },
                    dominatorDepth = domDepth[id] ?: -1,
                    rootPaths = allPaths[id].orEmpty()
                )
            } else {
                ObjectAnalysis(
                    objectId = id,
                    reachable = false,
                    shallowSizeBytes = sizes[id],
                    retainedSizeBytes = 0L,
                    retainedExact = id !in unknownSet,
                    unknownInRetainedSet = if (id in unknownSet) listOf(id) else emptyList(),
                    dominatorParent = null,
                    dominatorDepth = -1,
                    rootPaths = emptyList(),
                    unreachableReason = "当前策略(weakRetains=${policy.weakRetains},internalRetains=${policy.internalRetains})下无 root 可达路径"
                )
            }
        }.sortedBy { it.objectId }

        val dominatorNodes = (listOf(VIRTUAL_ROOT) + reachableIds).map {
            DominatorNode(it, if (it == VIRTUAL_ROOT) null else idom[it]?.takeIf { p -> p != VIRTUAL_ROOT }, domDepth[it] ?: 0)
        }.sortedWith(compareBy({ it.depth }, { it.objectId }))

        // 7) 类群。
        val className = bundle.classes.associate { it.id to it.name }
        val groups = LinkedHashMap<String, ClassGroup>()
        for (o in bundle.objects.sortedBy { it.id }) {
            val g = groups.getOrPut(o.classId) {
                ClassGroup(o.classId, className[o.classId] ?: "<unresolved-class:${o.classId}>", 0, 0, 0L, 0L)
            }
            val updated = g.copy(
                instanceCount = g.instanceCount + 1,
                reachableCount = g.reachableCount + if (o.id in reachableSet) 1 else 0,
                shallowBytes = g.shallowBytes + (sizes[o.id] ?: 0L),
                retainedBytes = g.retainedBytes + if (o.id in reachableSet) retained.getValue(o.id) else 0L,
                unknownSizeObjects = g.unknownSizeObjects + if (sizes[o.id] == null) listOf(o.id) else emptyList()
            )
            groups[o.classId] = updated
        }
        val classGroups = groups.values.sortedBy { it.classId }

        // 8) 泄漏候选。
        val candidates = reachableIds
            .filter { retained.getValue(it) >= policy.leakThresholdBytes }
            .sortedWith(compareByDescending<String> { id -> retained.getValue(id) }.thenBy { it })
            .take(budget.maxLeakCandidates)
            .map { id ->
                val paths = allPaths.getValue(id)
                LeakCandidate(
                    objectId = id,
                    retainedSizeBytes = retained.getValue(id),
                    retainedExact = retainedExact.getValue(id),
                    equalPathCount = paths.size,
                    representativePath = paths.sortedWith(Comparator { a, b ->
                        if (a.size != b.size) a.size.compareTo(b.size) else lexCompare(a, b)
                    }).first(),
                    reason = buildString {
                        append("保留 ${retained.getValue(id)} byte")
                        if (!retainedExact.getValue(id)) append("（含未知尺寸成员，为下界）")
                        append("；${paths.size} 条等长 root 路径")
                        val rootsUsed = paths.map { it.first() }.distinct()
                        append("，经 ${rootsUsed.size} 个 root：")
                        append(rootsUsed.joinToString { rid -> roots.firstOrNull { it.objectId == rid }?.let { "${it.type}:${it.id}" } ?: rid })
                    }
                )
            }

        val digest = sha256(inputDigest(bundle) + "|" + stableJson.encodeToString(AnalysisPolicy.serializer(), policy))
        val result = AnalysisResult(
            analysisId = "ana-${digest.take(12)}",
            snapshotId = bundle.meta.id,
            policy = policy,
            inputDigest = inputDigest(bundle),
            dependencyEventIds = dependencyEventIds,
            reachable = reachableIds,
            unreachable = objectIds.filter { it !in reachableSet }.sorted(),
            objects = objectAnalyses,
            dominator = dominatorNodes,
            classGroups = classGroups,
            leakCandidates = candidates,
            diagnostics = diagnostics.sortedWith(compareBy({ it.location.ordinal }, { it.code })),
            units = UnitBasis(addressBase = bundle.meta.addressBase, timeBase = bundle.meta.timeBase),
            budget = budget
        )
        return AnalysisOutcome.Ok(result)
    }

    private fun lexCompare(a: List<String>, b: List<String>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    private fun intersect(
        b1In: String, b2In: String,
        idom: Map<String, String>, rpoIndex: Map<String, Int>
    ): String {
        var finger1 = b1In
        var finger2 = b2In
        while (finger1 != finger2) {
            while (rpoIndex[finger1] ?: 0 > (rpoIndex[finger2] ?: 0)) finger1 = idom[finger1] ?: finger1
            while (rpoIndex[finger2] ?: 0 > (rpoIndex[finger1] ?: 0)) finger2 = idom[finger2] ?: finger2
        }
        return finger1
    }

    private fun rpoFromVirtual(
        rootIds: List<String>,
        adj: Map<String, List<String>>
    ): List<String> {
        val visited = HashSet<String>()
        val order = mutableListOf<String>()
        fun dfs(u: String) {
            visited.add(u)
            for (v in (adj[u]?.sorted().orEmpty())) {
                if (v !in visited) dfs(v)
            }
            order.add(u)
        }
        visited.add(VIRTUAL_ROOT)
        for (r in rootIds.sorted()) if (r !in visited) dfs(r)
        order.add(VIRTUAL_ROOT)
        return order.reversed()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
