package heapforensics

import kotlinx.serialization.Serializable

@Serializable
data class ClassGroup(
    val classId: String,
    val nameA: String?,
    val nameB: String?,
    val countA: Long,
    val countB: Long,
    val bytesA: Long,
    val bytesB: Long,
    val status: String   // SAME | RENAMED | ADDED | REMOVED | CHANGED
)

@Serializable
data class SnapshotDiff(
    val snapshotA: String,
    val snapshotB: String,
    val groups: List<ClassGroup>
)

/**
 * Class identity is classId (never the display name, never an address).
 * A rename is same classId with different name across snapshots.
 */
fun diffSnapshots(a: RawSnapshot, b: RawSnapshot): SnapshotDiff {
    fun groups(s: RawSnapshot): Map<String, Triple<String, Long, Long>> {
        val names = s.classes.associate { it.classId to it.name }
        val d = derive(s)
        return d.objects.values.groupBy { it.raw.classId ?: "<unknown>" }
            .mapValues { (cid, objs) ->
                Triple(names[cid] ?: "<unknown>", objs.size.toLong(), objs.sumOf { it.sizeBytes ?: 0L })
            }
    }
    val ga = groups(a); val gb = groups(b)
    val all = (ga.keys + gb.keys).sorted()
    val rows = all.map { cid ->
        val x = ga[cid]; val y = gb[cid]
        val status = when {
            x == null -> "ADDED"
            y == null -> "REMOVED"
            x.first != y.first -> "RENAMED"
            x.second != y.second || x.third != y.third -> "CHANGED"
            else -> "SAME"
        }
        ClassGroup(cid, x?.first, y?.first, x?.second ?: 0, y?.second ?: 0,
            x?.third ?: 0, y?.third ?: 0, status)
    }
    return SnapshotDiff(a.snapshotId, b.snapshotId, rows)
}
