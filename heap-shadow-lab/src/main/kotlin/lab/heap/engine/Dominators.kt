package lab.heap.engine

/**
 * Cooper, Harvey & Kennedy "A Simple, Fast Dominance Algorithm" 的迭代实现。
 * 只允许传入“可达图”；不可达节点必须在调用前排除——支配树绝不建立在全图上。
 *
 * 节点顺序由 [successors] 的迭代顺序决定，调用方需给出确定性顺序，
 * 以保证等价输入得到稳定结果（等价输入稳定顺序这一不变量）。
 */
class DominatorComputer<T>(
    private val allNodes: List<T>,
    private val virtualRoot: T,
    private val successors: (T) -> List<T>,
    private val predecessors: (T) -> List<T>
) {
    data class Idoms<T>(val immediate: Map<T, T>, val virtualRoot: T)

    fun compute(): Idoms<T> {
        // 从虚根出发的 DFS 产生逆后序（RPO）
        val rpo = ArrayList<T>()
        val visited = HashSet<T>()
        fun dfs(n: T) {
            if (!visited.add(n)) return
            for (s in successors(n)) dfs(s)
            rpo.add(0, n) // 后序头插 => 逆后序
        }
        dfs(virtualRoot)

        val rpoIndex = HashMap<T, Int>()
        rpo.forEachIndexed { i, n -> rpoIndex[n] = i }
        val idom = HashMap<T, T>()
        idom[virtualRoot] = virtualRoot

        fun intersect(fingerIn: T?, bIn: T?): T? {
            var finger = fingerIn ?: return null
            var b = bIn ?: return null
            val guard = rpoIndex.size + 1
            var steps = 0
            while (finger != b) {
                while ((rpoIndex[finger] ?: 0) > (rpoIndex[b] ?: 0)) {
                    finger = idom[finger]
                    if (finger == null || finger === virtualRoot) break
                }
                while ((rpoIndex[b] ?: 0) > (rpoIndex[finger] ?: 0)) {
                    b = idom[b]
                    if (b == null || b === virtualRoot) break
                }
                if (finger === b || (finger === virtualRoot && b === virtualRoot)) break
                if (++steps > guard) { finger = virtualRoot; break }
            }
            return finger
        }

        var changed = true
        // 循环图也成立：支配关系基于“带根的可达流图”，环上每个节点仍有唯一 idom
        while (changed) {
            changed = false
            for (n in rpo) {
                if (n === virtualRoot) continue
                val preds = predecessors(n).filter { idom.containsKey(it) || it === virtualRoot }
                val first = preds.firstOrNull { idom.containsKey(it) } ?: continue
                var newIdom: T? = first
                for (p in preds) {
                    if (p === first) continue
                    if (idom.containsKey(p)) {
                        newIdom = intersect(p, newIdom)
                    }
                }
                if (newIdom != null && idom[n] !== newIdom) {
                    idom[n] = newIdom
                    changed = true
                }
            }
        }
        return Idoms(idom, virtualRoot)
    }
}
