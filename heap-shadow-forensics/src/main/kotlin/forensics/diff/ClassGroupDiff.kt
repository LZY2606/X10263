package forensics.diff

import forensics.model.*
import forensics.graph.*
import kotlinx.serialization.Serializable

@Serializable
data class ClassGroup(
    val classStableId: String,
    val className: String,
    val renamedFrom: String?,
    val objectCount: Int,
    val shallowBytes: Long?,
    val retainedBytes: Long?,
    val unresolvedObjectIds: List<String>,
)

@Serializable
data class ClassGroupRow(
    val classStableId: String,
    val nameInA: String?,
    val nameInB: String?,
    val renamed: Boolean,
    val countA: Int?,
    val countB: Int?,
    val countDelta: Int?,
    val retainedA: Long?,
    val retainedB: Long?,
    val retainedDelta: Long?,
    val validity: String,
    val invalidReason: String?,
)

@Serializable
data class ClassGroupDiff(
    val snapshotIdA: String,
    val snapshotIdB: String,
    val rows: List<ClassGroupRow>,
    val note: String,
)

object ClassGroupDiffer {
    private fun group(snapshot: HeapSnapshot, analysis: GraphAnalysis): Map<String, ClassGroup> {
        val objs = snapshot.objects
        val byClass = objs.groupBy { it.classId }
        val verdictByObject = analysis.objects.associateBy { it.objectId }
        return byClass.map { (classStableId, members) ->
            val cls = snapshot.classes.first { it.stableId == classStableId }
            var shallow = 0L
            var retained = 0L
            val bad = mutableListOf<String>()
            members.forEach { o ->
                val v = verdictByObject[o.objectId]
                o.shallowSize?.normalizedBytes?.let { shallow += it } ?: bad.add(o.objectId)
                if (v != null && v.keptAlive) {
                    v.retained.bytes?.let { retained += it } ?: bad.addAll(v.retained.unresolvedObjectIds)
                }
            }
            classStableId to ClassGroup(
                classStableId, cls.name, cls.renamedFrom, members.size,
                if (bad.isEmpty()) shallow else null,
                if (bad.isEmpty()) retained else null,
                bad.distinct().sorted(),
            )
        }.toMap()
    }

    fun diff(a: HeapSnapshot, aa: GraphAnalysis, b: HeapSnapshot, ba: GraphAnalysis): ClassGroupDiff {
        val ga = group(a, aa); val gb = group(b, ba)
        val keys = (ga.keys + gb.keys).toSortedSet()
        val rows = keys.map { k ->
            val x = ga[k]; val y = gb[k]
            val bad = (x?.unresolvedObjectIds.orEmpty() + y?.unresolvedObjectIds.orEmpty()).distinct().sorted()
            ClassGroupRow(
                k, x?.className, y?.className,
                renamed = y?.renamedFrom != null || (x != null && y != null && x.className != y.className),
                countA = x?.objectCount, countB = y?.objectCount,
                countDelta = if (x != null && y != null) y.objectCount - x.objectCount else null,
                retainedA = x?.retainedBytes, retainedB = y?.retainedBytes,
                retainedDelta = if (x?.retainedBytes != null && y?.retainedBytes != null)
                    y.retainedBytes - x.retainedBytes else null,
                validity = if (bad.isEmpty()) "exact" else "invalid",
                invalidReason = if (bad.isEmpty()) null else "unconvertible sizes: ${bad.joinToString(",")}",
            )
        }
        return ClassGroupDiff(
            a.header.snapshotId, b.header.snapshotId, rows,
            "rows keyed by class stableId; a rename is correspondence, not a new class",
        )
    }
}
