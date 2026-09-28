package forensics.heapsnapshot.engine

/**
 * Lengauer–Tarjan 支配树（迭代版 compress，避免大快照栈溢出）。
 * 只在“可达图 + 虚拟超级根 VROOT”上构建：不可达对象绝不进入支配树，
 * 这是领域硬规则（支配树只在可达图上构建）。
 *
 * @param successors 已按稳定顺序排好的邻接表，索引 0 约定为 VROOT。
 * @return idom[i]：节点 i 的直接支配者；VROOT 的 idom 为 0（自身）。
 */
internal object DominatorTree {
    fun build(successors: Array<List<Int>>): IntArray {
        val n = successors.size
        val dfn = IntArray(n) { -1 }   // 节点 -> DFS 序
        val vertex = IntArray(n)       // DFS 序 -> 节点
        val parent = IntArray(n)
        var dfsCount = 0

        // 迭代 DFS：VROOT(0) -> 全部可达节点
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to 0)
        while (stack.isNotEmpty()) {
            val (u, startIdx) = stack.removeLast()
            if (dfn[u] == -1 && u != 0) {
                // 首次进入（VROOT 在循环外处理）
            }
            if (startIdx == 0 && dfn[u] == -1) {
                dfn[u] = dfsCount
                vertex[dfsCount] = u
                dfsCount++
            }
            val succ = successors[u]
            var i = startIdx
            while (i < succ.size) {
                val v = succ[i]
                if (dfn[v] == -1) {
                    parent[v] = u
                    stack.addLast(u to i + 1)
                    stack.addLast(v to 0)
                    break
                }
                i++
            }
        }

        val m = dfsCount
        val semi = IntArray(m)
        val label = IntArray(m)
        val ancestor = IntArray(m) { -1 }
        val bucket = Array(m) { mutableListOf<Int>() }
        val idom = IntArray(m) { -1 }

        fun nodeDfn(v: Int) = dfn[v]

        fun compress(v: Int) {
            // 迭代路径压缩
            val path = mutableListOf<Int>()
            var cur = v
            while (ancestor[ancestor[cur]] != -1) {
                path.add(cur)
                cur = ancestor[cur]
            }
            for (x in path.asReversed()) {
                val a = ancestor[x]
                if (semi[label[a]] < semi[label[x]]) label[x] = label[a]
                ancestor[x] = ancestor[a]
            }
        }

        fun eval(v: Int): Int {
            if (ancestor[v] == -1) return v
            compress(v)
            return label[v]
        }

        val predByDfn = Array(m) { mutableListOf<Int>() }
        for (iDfn in 1 until m) {
            val u = vertex[iDfn]
            successors[u].forEach { w ->
                val wd = dfn[w]
                if (wd != -1) predByDfn[iDfn].add(wd)
            }
        }

        for (i in 1 until m) {
            semi[i] = i
            label[i] = i
        }

        for (iDfn in (1 until m).reversed()) {
            val w = vertex[iDfn]
            for (vd in predByDfn[iDfn]) {
                val u = eval(vd)
                if (semi[u] < semi[iDfn]) semi[iDfn] = semi[u]
            }
            bucket[semi[iDfn]].add(iDfn)
            val pw = parent[w]
            ancestor[iDfn] = dfn[pw]
            for (vd in bucket[dfn[pw]]) {
                val u = eval(vd)
                idom[vd] = if (semi[u] < semi[vd]) vertex[u] else pw
            }
            bucket[dfn[pw]].clear()
        }

        for (iDfn in 1 until m) {
            if (idom[iDfn] != vertex[semi[iDfn]]) {
                idom[iDfn] = idom[dfn[idom[iDfn]]]
            }
        }
        idom[0] = 0

        val result = IntArray(n) { -1 }
        for (i in 0 until m) result[vertex[i]] = idom[i]
        return result
    }
}
