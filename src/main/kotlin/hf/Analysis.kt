package hf

import java.security.MessageDigest

object Digest {
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 两个快照的类群差异：按稳定 classId 对应，名字变化记为 renamed。 */
data class ClassDiff(
    val leftSnapshot: String,
    val rightSnapshot: String,
    val renamed: List<RenamedClass>,
    val addedOnly: List<ClassMeta>,   // 仅在 right
    val removedOnly: List<ClassMeta>, // 仅在 left
    val countChanges: List<ClassCountChange>,
) {
    data class RenamedClass(val classId: String, val oldName: String, val newName: String)
    data class ClassCountChange(val classId: String, val name: String, val leftCount: Int, val rightCount: Int)
}

object AnalysisEngine {

    /** 输入摘要：实体规范化序列化 + 参数，保证等价输入得到稳定摘要与稳定顺序。 */
    fun inputDigest(snapshot: Snapshot, params: AnalysisParams): String {
        val sb = StringBuilder()
        sb.append("parser=").append(snapshot.parserVersion).append('\n')
        snapshot.classes.sortedBy { it.classId }.forEach { sb.append("C ${it.classId} ${it.name} ${it.instanceSize}\n") }
        snapshot.objects.sortedBy { it.objectId }.forEach { sb.append("O ${it.objectId} ${it.classId} ${it.size} ${it.incomplete}\n") }
        snapshot.edges.sortedWith(compareBy({ it.src }, { it.dst }, { it.kind.ordinal }))
            .forEach { sb.append("E ${it.src} ${it.dst} ${it.kind}\n") }
        snapshot.roots.sortedBy { it.rootId }.forEach { sb.append("R ${it.rootId} ${it.kind} ${it.objectId}\n") }
        sb.append("P ${params.version} ${params.weakPolicy} ${params.maxNodes} ${params.maxPathsPerObject} ${params.leakThresholdBytes}")
        return Digest.sha256(sb.toString()).take(16)
    }

    /**
     * 主分析：支配树只在可达图上构建；弱引用是否保活由 params.weakPolicy 显式决定；
     * 不完整对象不参与图遍历但保留在存储层。
     */
    fun analyze(snapshot: Snapshot, params: AnalysisParams): Outcome<AnalysisResult> {
        val total = snapshot.objects.size.toLong()
        if (total > params.maxNodes)
            return Outcome.BudgetExhausted("nodes", total, params.maxNodes)

        val byId = snapshot.objectById()
        val incomplete = snapshot.objects.filter { it.incomplete }.map { it.objectId }.toSet()

        // 参与遍历的边：strong/internal 始终保活；weak 由显式策略决定。
        val liveEdges = snapshot.edges.filter { e ->
            when (e.kind) {
                EdgeKind.STRONG, EdgeKind.INTERNAL -> true
                EdgeKind.WEAK -> params.weakPolicy == WeakPolicy.KEEP
            }
        }.filter { it.src !in incomplete && it.dst !in incomplete }

        val out = mutableMapOf<Long, MutableList<Long>>()
        liveEdges.forEach { out.getOrPut(it.src) { mutableListOf() }.add(it.dst) }
        out.values.forEach { it.sort() } // 稳定顺序

        val rootObjs = snapshot.roots.map { it.objectId }.filter { it !in incomplete && byId.containsKey(it) }.sorted()
        if (rootObjs.isEmpty())
            return Outcome.Unsolvable("no usable roots after excluding incomplete objects", emptyList())

        // ---- 可达性 + 全部等长最短 root 路径（BFS 分层）----
        val depth = mutableMapOf<Long, Int>()
        val paths = mutableMapOf<Long, MutableList<List<Long>>>()
        val truncated = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>()
        rootObjs.forEach { r ->
            depth[r] = 0
            paths.getOrPut(r) { mutableListOf() }.add(listOf(r))
            queue.add(r)
        }
        var visited = 0L
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            visited++
            if (visited > params.maxNodes) return Outcome.BudgetExhausted("traversal", visited, params.maxNodes)
            val d = depth.getValue(node)
            for (next in out[node].orEmpty()) {
                val nd = d + 1
                val known = depth[next]
                if (known == null) {
                    depth[next] = nd
                    val acc = mutableListOf<List<Long>>()
                    for (p in paths[node].orEmpty()) {
                        if (acc.size >= params.maxPathsPerObject) { truncated += next; break }
                        acc.add(p + next)
                    }
                    paths[next] = acc
                    queue.add(next)
                } else if (known == nd) {
                    // 等长路径：全部保留，直到预算
                    val acc = paths.getOrPut(next) { mutableListOf() }
                    for (p in paths[node].orEmpty()) {
                        if (acc.size >= params.maxPathsPerObject) { truncated += next; break }
                        val candidate = p + next
                        if (candidate !in acc) acc.add(candidate)
                    }
                }
            }
        }
        val reachable = depth.keys.toSet()

        // ---- 支配树（Cooper-Harvey-Kennedy），只在可达图上 ----
        val idom = dominators(AnalysisResult.VIRTUAL_ROOT, rootObjs, reachable, out)

        // ---- 保留大小：支配树后序累加 ----
        val children = mutableMapOf<Long, MutableList<Long>>()
        idom.forEach { (node, parent) -> if (node != AnalysisResult.VIRTUAL_ROOT) children.getOrPut(parent) { mutableListOf() }.add(node) }
        children.values.forEach { it.sort() }
        val retained = mutableMapOf<Long, Long>()
        fun accumulate(node: Long): Long {
            var sum = byId[node]?.size ?: 0L
            for (c in children[node].orEmpty()) sum += accumulate(c)
            retained[node] = sum
            return sum
        }
        accumulate(AnalysisResult.VIRTUAL_ROOT)
        retained.remove(AnalysisResult.VIRTUAL_ROOT)

        // ---- 泄漏路径候选：保留大小超阈值且支配其他对象 ----
        val candidates = reachable
            .filter { (retained[it] ?: 0L) >= params.leakThresholdBytes && children.containsKey(it) }
            .sortedWith(compareByDescending<Long> { retained[it] ?: 0L }.thenBy { it })
            .map { obj ->
                val chain = mutableListOf<Long>()
                var cur: Long? = obj
                while (cur != null && cur != AnalysisResult.VIRTUAL_ROOT) {
                    chain.add(0, cur)
                    cur = idom[cur]
                }
                LeakCandidate(obj, retained[obj] ?: 0L, chain, paths[obj].orEmpty())
            }

        return Outcome.Ok(
            AnalysisResult(
                snapshotId = snapshot.snapshotId,
                paramVersion = params.version,
                inputDigest = inputDigest(snapshot, params),
                reachable = reachable,
                idom = idom.filterKeys { it != AnalysisResult.VIRTUAL_ROOT },
                retained = retained,
                rootPaths = paths,
                truncatedPaths = truncated,
                leakCandidates = candidates,
                excludedIncomplete = incomplete,
            )
        )
    }

    /** CHK 迭代支配算法。entry 为虚拟根，直接支配所有 root 对象。 */
    private fun dominators(
        entry: Long,
        roots: List<Long>,
        reachable: Set<Long>,
        out: Map<Long, List<Long>>,
    ): Map<Long, Long> {
        // 反向边（仅可达节点）
        val preds = mutableMapOf<Long, MutableList<Long>>()
        reachable.forEach { n -> out[n].orEmpty().forEach { m -> if (m in reachable) preds.getOrPut(m) { mutableListOf() }.add(n) } }
        roots.forEach { r -> preds.getOrPut(r) { mutableListOf() }.add(entry) }
        preds.values.forEach { it.sort() }

        // DFS 后序编号
        val postorder = mutableListOf<Long>()
        val seen = mutableSetOf<Long>()
        fun dfs(node: Long) {
            if (!seen.add(node)) return
            val nexts = if (node == entry) roots else out[node].orEmpty().filter { it in reachable }
            nexts.forEach(::dfs)
            postorder.add(node)
        }
        dfs(entry)
        val index = postorder.withIndex().associate { it.value to it.index }
        val idom = mutableMapOf<Long, Long>()
        idom[entry] = entry

        fun intersect(a0: Long, b0: Long): Long {
            var a = a0; var b = b0
            while (a != b) {
                while (index.getValue(a) < index.getValue(b)) a = idom.getValue(a)
                while (index.getValue(b) < index.getValue(a)) b = idom.getValue(b)
            }
            return a
        }

        var changed = true
        while (changed) {
            changed = false
            for (node in postorder.asReversed()) {
                if (node == entry) continue
                val ps = preds[node].orEmpty().filter { idom.containsKey(it) }
                if (ps.isEmpty()) continue
                var newIdom = ps.first()
                for (p in ps.drop(1)) newIdom = intersect(p, newIdom)
                if (idom[node] != newIdom) { idom[node] = newIdom; changed = true }
            }
        }
        return idom
    }

    /** 类群差异：按稳定 classId 对应；同名不同 id 不算同一类；同 id 不同名记 renamed。 */
    fun classDiff(left: Snapshot, right: Snapshot): ClassDiff {
        val lc = left.classById(); val rc = right.classById()
        val renamed = (lc.keys intersect rc.keys)
            .filter { lc[it]!!.name != rc[it]!!.name }
            .sorted()
            .map { ClassDiff.RenamedClass(it, lc[it]!!.name, rc[it]!!.name) }
        val added = (rc.keys - lc.keys).sorted().map { rc[it]!! }
        val removed = (lc.keys - rc.keys).sorted().map { lc[it]!! }
        fun counts(s: Snapshot) = s.objects.groupingBy { it.classId }.eachCount()
        val lCount = counts(left); val rCount = counts(right)
        val changes = (lc.keys intersect rc.keys).sorted().mapNotNull { id ->
            val l = lCount[id] ?: 0; val r = rCount[id] ?: 0
            if (l != r) ClassDiff.ClassCountChange(id, rc[id]!!.name, l, r) else null
        }
        return ClassDiff(left.snapshotId, right.snapshotId, renamed, added, removed, changes)
    }
}
