package heapforensics.analysis

import heapforensics.domain.RawSnapshot
import heapforensics.domain.parseSize

enum class DiffStatus { SAME, RENAMED, META_CHANGED, ADDED, REMOVED }

data class ClassDiffRow(
    val classId: String,
    val nameA: String?,
    val nameB: String?,
    val status: DiffStatus,
    val objectsA: Int,
    val objectsB: Int,
    val bytesA: Long?,
    val bytesB: Long?
)

data class ClassDiff(
    val snapshotA: String,
    val snapshotB: String,
    val rows: List<ClassDiffRow>
) {
    fun count(status: DiffStatus): Int = rows.count { it.status == status }
}

/** 两个快照的类群差异：类按 classId（稳定身份）对应，名称变化记为改名而非增删。 */
object ClassDiffer {
    fun diff(a: RawSnapshot, b: RawSnapshot): ClassDiff {
        val classesA = a.classes.associateBy { it.id }
        val classesB = b.classes.associateBy { it.id }
        val statsA = objectStats(a)
        val statsB = objectStats(b)
        val rows = mutableListOf<ClassDiffRow>()
        for (id in (classesA.keys + classesB.keys).sorted()) {
            val ca = classesA[id]
            val cb = classesB[id]
            val status = when {
                ca != null && cb == null -> DiffStatus.REMOVED
                ca == null && cb != null -> DiffStatus.ADDED
                ca!!.name != cb!!.name -> DiffStatus.RENAMED
                ca.superId != cb.superId || ca.loader != cb.loader -> DiffStatus.META_CHANGED
                else -> DiffStatus.SAME
            }
            rows += ClassDiffRow(
                classId = id,
                nameA = ca?.name,
                nameB = cb?.name,
                status = status,
                objectsA = statsA[id]?.first ?: 0,
                objectsB = statsB[id]?.first ?: 0,
                bytesA = statsA[id]?.second,
                bytesB = statsB[id]?.second
            )
        }
        return ClassDiff(a.snapshotId, b.snapshotId, rows)
    }

    /** 每类对象数与字节合计；存在无法转换的大小时该组合计为 null（不丢原值）。 */
    private fun objectStats(s: RawSnapshot): Map<String, Pair<Int, Long?>> {
        val byClass = s.objects.groupBy { it.classId }
        return byClass.mapValues { (classId, objs) ->
            var total = 0L
            var convertible = true
            objs.forEachIndexed { _, o ->
                val (bytes, diag) = parseSize(o.sizeRaw, "objects(classId=$classId,id=${o.id}).size")
                if (bytes == null || diag?.severity == heapforensics.domain.Severity.ERROR) convertible = false
                if (bytes != null) total += bytes
            }
            objs.size to (if (convertible) total else null)
        }
    }
}
