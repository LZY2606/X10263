package heaplab.diff

import heaplab.analysis.AnalysisResult
import heaplab.model.RawSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class ClassGroup(
    val classKey: String,             // 该快照内类稳定身份（原始 classId）
    val className: String,
    val objectIds: List<String>,
    val count: Int,
    val shallowBytes: Long?,
    val retainedBytes: Long?,
    val completeCount: Int,
    val incompleteCount: Int
)

@Serializable
data class ClassGroupDiffRow(
    val relation: String,            // MATCHED / RENAMED / ADDED / REMOVED
    val baseClassKey: String?,
    val otherClassKey: String?,
    val baseName: String?,
    val otherName: String?,
    val baseCount: Int,
    val otherCount: Int,
    val countDelta: Int,
    val baseRetainedBytes: Long?,
    val otherRetainedBytes: Long?,
    /** 改名对应依据：地址签名重叠数 / 浅字节多重集相似度。地址仅作证据，非身份。 */
    val renameEvidence: String?
)

@Serializable
data class SnapshotDiff(
    val baseSnapshotId: String,
    val otherSnapshotId: String,
    val rows: List<ClassGroupDiffRow>,
    val renamedPairs: List<Pair<String, String>>
)

/**
 * 两个快照的类群差异。
 * 身份规则：类在各自快照内用原始 classId 做稳定身份；跨快照默认按类名对应，
 * 名称变化时用“规范化地址集合 + 浅字节多重集”签名给出 RENAMED 候选。
 * 无法唯一决定改名时，保留全部达到阈值的候选（不强行 1:1）。
 * 地址永不作为对象稳定身份，仅用于改名证据。
 */
object ClassGroupDiffer {
    private const val RENAME_ADDR_THRESHOLD = 0.34

    fun groups(snap: RawSnapshot, result: AnalysisResult?): List<ClassGroup> {
        val byClass = snap.objects.values.groupBy { it.classIdRaw.ifEmpty { "<unknown>" } }
        return byClass.map { (key, objs) ->
            val ids = objs.map { it.objId }.sorted()
            val shallow = objs.mapNotNull { it.size.bytes }.takeIf { it.isNotEmpty() }?.sum()
            val retained = if (result == null) null else objs.mapNotNull {
                result.objects[it.objId]?.retainedBytes
            }.takeIf { it.isNotEmpty() }?.sum()
            ClassGroup(
                classKey = key,
                className = if (key in snap.classes) snap.classes[key]!!.name else key,
                objectIds = ids,
                count = objs.size,
                shallowBytes = shallow,
                retainedBytes = retained,
                completeCount = objs.count { it.complete },
                incompleteCount = objs.count { !it.complete }
            )
        }.sortedWith(compareByDescending<ClassGroup> { it.count }.thenBy { it.classKey })
    }

    fun diff(base: RawSnapshot, other: RawSnapshot,
             baseResult: AnalysisResult?, otherResult: AnalysisResult?): SnapshotDiff {
        val bg = groups(base, baseResult).associateBy { it.classKey }
        val og = groups(other, otherResult).associateBy { it.classKey }
        val rows = mutableListOf<ClassGroupDiffRow>()
        val consumedOther = HashSet<String>()

        for ((key, g) in bg) {
            val sameName = og.values.firstOrNull { it.className == g.className }
            if (sameName != null) {
                consumedOther += sameName.classKey
                rows += row("MATCHED", g, sameName, null)
                continue
            }
            val candidates = renameCandidates(g, base, og.values, other)
            if (candidates.isNotEmpty()) {
                for (c in candidates) {
                    consumedOther += c.first.classKey
                    rows += row("RENAMED", g, c.first,
                        "地址重叠 ${c.second.addrOverlap}/${c.second.addrUnion}，浅字节相似度=%.2f"
                            .format(c.second.sizeSimilarity))
                }
            } else {
                rows += row("REMOVED", g, null, null)
            }
        }
        for ((key, g) in og) if (key !in consumedOther) rows += row("ADDED", null, g, null)

        val renamed = rows.filter { it.relation == "RENAMED" }
            .map { it.baseClassKey!! to it.otherClassKey!! }
        return SnapshotDiff(
            base.snapshotId, other.snapshotId,
            rows.sortedWith(compareBy({ it.relation }, { it.baseName ?: it.otherName },
                { it.baseClassKey ?: "" }, { it.otherClassKey ?: "" })),
            renamed
        )
    }

    private data class RenameScore(val addrOverlap: Int, val addrUnion: Int, val sizeSimilarity: Double)

    private fun renameCandidates(
        g: ClassGroup, base: RawSnapshot,
        candidates: Collection<ClassGroup>, other: RawSnapshot
    ): List<Pair<ClassGroup, RenameScore>> {
        val (bAddr, bSizes) = signature(g, base)
        val out = mutableListOf<Pair<ClassGroup, RenameScore>>()
        for (c in candidates) {
            val (cAddr, cSizes) = signature(c, other)
            val overlap = bAddr.intersect(cAddr).size
            val union = bAddr.union(cAddr).size
            val addrRatio = if (union == 0) 0.0 else overlap.toDouble() / union
            val sizeSim = multisetSimilarity(bSizes, cSizes)
            if (addrRatio >= RENAME_ADDR_THRESHOLD || (overlap > 0 && sizeSim >= 0.5)) {
                out += c to RenameScore(overlap, union, sizeSim)
            }
        }
        return out.sortedWith(compareByDescending<Pair<ClassGroup, RenameScore>> { it.second.addrOverlap }
            .thenBy { it.first.classKey })
    }

    private fun signature(g: ClassGroup, snap: RawSnapshot): Pair<Set<Long>, List<Long>> {
        val objs = g.objectIds.mapNotNull { snap.objects[it] }
        val addrs = objs.mapNotNull { it.address.value }.toSet()
        val sizes = objs.mapNotNull { it.size.bytes }.sorted()
        return addrs to sizes
    }

    private fun multisetSimilarity(a: List<Long>, b: List<Long>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val ca = a.groupingBy { it }.eachCount()
        val cb = b.groupingBy { it }.eachCount()
        val common = ca.keys.intersect(cb.keys).sumOf { minOf(ca[it]!!, cb[it]!!) }
        return common.toDouble() / maxOf(a.size, b.size)
    }

    private fun row(rel: String, b: ClassGroup?, o: ClassGroup?, ev: String?): ClassGroupDiffRow =
        ClassGroupDiffRow(
            relation = rel,
            baseClassKey = b?.classKey, otherClassKey = o?.classKey,
            baseName = b?.className, otherName = o?.className,
            baseCount = b?.count ?: 0, otherCount = o?.count ?: 0,
            countDelta = (o?.count ?: 0) - (b?.count ?: 0),
            baseRetainedBytes = b?.retainedBytes, otherRetainedBytes = o?.retainedBytes,
            renameEvidence = ev
        )
}
