package forensics.graph

/**
 * Cooper, Harvey, Waterman 支配算法。输入是从单一入口出发的可达节点集合（RPO 序）。
 * 支配树只在可达图上构建；不可达对象不出现在 idom 中。
 * 排序全部使用稳定 id，保证等价输入得到稳定顺序。
 */
class DominatorComputer(
    private val nodesRpo: List<String>,
    private val preds: Map<String, List<String>>,
    private val succ: Map<String, List<String>>,
    private val entry: String,
) {
    private val rpoIndex: Map<String, Int> = nodesRpo.withIndex().associate { it.value to it.index }
    private val idom = HashMap<String, String?>()

    fun compute(): Map<String, String?> {
        idom[entry] = entry
        var changed = true
        while (changed) {
            changed = false
            for (b in nodesRpo) {
                if (b == entry) continue
                val processedPreds = preds[b].orEmpty().filter { idom.containsKey(it) }
                val newIdom = processedPreds.firstOrNull() ?: continue
                var picked: String? = newIdom
                for (p in processedPreds.drop(1)) {
                    picked = intersect(p, picked!!)
                }
                if (idom[b] != picked) {
                    idom[b] = picked
                    changed = true
                }
            }
        }
        return idom.mapValues { (k, v) -> if (k == entry) null else v }
    }

    private fun intersect(aIn: String, bIn: String): String {
        var finger1 = aIn
        var finger2 = bIn
        while (finger1 != finger2) {
            while (rpoIndex[finger1]!! > rpoIndex[finger2]!!) finger1 = idom[finger1]!!
            while (rpoIndex[finger2]!! > rpoIndex[finger1]!!) finger2 = idom[finger2]!!
        }
        return finger1
    }
}
