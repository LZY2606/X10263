package heaplab.analysis

/**
 * Lengauer–Tarjan 支配树（近线性）。
 *
 * 关键取证语义：支配树**只在可达图上构建**。输入是“带单个虚拟 SUPER_ROOT 的可达子图”：
 * 所有 GC root 都由 SUPER_ROOT 直接连出，因此多 root 都被纳入同一支配树，
 * 对象即使被多个 root 等长路径同时到达，也只有唯一支配父级。
 *
 * 为保证等价输入稳定：编号顺序由调用方提供（BFS + 字典序），算法内部不再做任意选择。
 */
class DominatorTree(
    val superRoot: String,
    val nodes: List<String>,                 // 含 superRoot，按 DFS 前序
    val idom: Map<String, String>,          // 每个节点（除 superRoot）的直接支配者
    val children: Map<String, List<String>>
) {
    /** 对象是否被 root 支配树覆盖（即可达）。 */
    fun contains(id: String): Boolean = id == superRoot || idom.containsKey(id)

    fun parent(id: String): String? = idom[id]

    /**
     保留大小：支配子树 shallow size 之和（字节）。
     * 无可用字节大小的对象按 0 计（其原始 sizeRaw 在材料层与界面同时展示）。
     */
    fun retainedSizes(shallow: Map<String, Long>): Map<String, Long> {
        val acc = HashMap<String, Long>()
        // 逆 DFS 序累加：支配树子树 = DFS 连续区间（LT 编号天然满足）。
        for (v in nodes.asReversed()) {
            acc[v] = (acc[v] ?: 0L) + (shallow[v] ?: 0L)
            val p = idom[v]
            if (p != null) acc[p] = (acc[p] ?: 0L) + acc.getValue(v)
        }
        return acc
    }
}

object Dominators {
    /**
     * @param root 虚拟根
     * @param succ 稳定有序后继（已裁剪到可达图）
     */
    fun build(root: String, succ: Map<String, List<String>>): DominatorTree {
        val n0 = HashMap<String, Int>()
        val vertex = ArrayList<String>()
        val parent = ArrayList<Int>()
        vertex.add(root); n0[root] = 0; parent.add(-1)

        // 迭代式 DFS，后继顺序即给定稳定顺序。
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (u, idx) = stack.removeLast()
            val children = succ[u].orEmpty()
            if (idx < children.size) {
                stack.addLast(u to idx + 1)
                val w = children[idx]
                if (w !in n0) {
                    n0[w] = vertex.size
                    vertex.add(w)
                    parent.add(n0.getValue(u))
                    stack.addLast(w to 0)
                }
            }
        }
        val n = vertex.size
        val idomArr = IntArray(n) { -1 }
        val semi = IntArray(n) { it }
        val label = IntArray(n) { it }
        val ancestor = IntArray(n) { -1 }
        val pred = Array(n) { ArrayList<Int>() }
        for ((u, outs) in succ) {
            val ui = n0[u] ?: continue
            for (w in outs) n0[w]?.let { pred[it].add(ui) }
        }
        val bucket = Array(n) { ArrayList<Int>() }

        fun compress(v0: Int) {
            val path = ArrayList<Int>()
            var v = v0
            while (ancestor[ancestor[v]] != -1) { path.add(v); v = ancestor[v] }
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
        fun link(p: Int, v: Int) { ancestor[v] = p }

        for (w in n - 1 downTo 1) {
            for (v in pred[w]) {
                val u = eval(v)
                if (semi[u] < semi[w]) semi[w] = semi[u]
            }
            bucket[semi[w]].add(w)
            link(parent[w], w)
            for (v in bucket[parent[w]]) {
                val u = eval(v)
                idomArr[v] = if (semi[u] < semi[v]) u else parent[w]
            }
            bucket[parent[w]].clear()
        }
        for (w in 1 until n) {
            if (idomArr[w] != semi[w]) idomArr[w] = idomArr[idomArr[w]]
        }

        val idom = LinkedHashMap<String, String>()
        val childrenMap = LinkedHashMap<String, MutableList<String>>()
        childrenMap.getOrPut(root) { mutableListOf() }
        for (i in 1 until n) {
            val v = vertex[i]
            val p = vertex[idomArr[i]]
            idom[v] = p
            childrenMap.getOrPut(p) { mutableListOf() }.add(v)
        }
        childrenMap.values.forEach { it.sort() }
        return DominatorTree(root, vertex, idom, childrenMap)
    }
}
