package heaplab.analysis

import heaplab.model.Snapshot
import heaplab.model.SemanticallyAmbiguousException
import kotlinx.serialization.Serializable

/**
 * 两个快照的类群差异。
 *
 * 类改名对应：优先用对象上的 correspondenceKey 把两个快照的同一逻辑对象对应起来，
 * 再看其 classId 指向的类名是否变化；没有 corr 时退化为“类名即身份”。
 * 地址绝不参与跨快照身份对应。
 */
@Serializable
data class RenamedClass(
    val correspondenceKey: String,
    val fromClassId: String,
    val fromName: String,
    val toClassId: String,
    val toName: String,
    val evidenceObjectKeys: List<String>
)

@Serializable
data class ClassGroupDelta(
    val classId: String,
    val name: String,
    val beforeCount: Int,
    val afterCount: Int,
    val beforeRetained: Long,
    val afterRetained: Long,
    val sizeUnit: String,
    val kind: String // ADDED | REMOVED | GREW | SHRANK | STABLE | RENAMED
)

@Serializable
data class ClassDiffResult(
    val beforeSnapshotId: String,
    val afterSnapshotId: String,
    val deltas: List<ClassGroupDelta>,
    val renamedClasses: List<RenamedClass>,
    val identityBasis: String,
    val diagnostics: List<String>
)

object ClassDiff {
    fun diff(before: AnalysisResult, after: AnalysisResult, beforeSnap: Snapshot, afterSnap: Snapshot): ClassDiffResult {
        val diagnostics = mutableListOf<String>()

        // correspondenceKey -> (beforeObj, afterObj)
        val bByKey = beforeSnap.objects.filter { !it.correspondenceKey.isNullOrBlank() }
            .groupBy { it.correspondenceKey!! }
        val aByKey = afterSnap.objects.filter { !it.correspondenceKey.isNullOrBlank() }
            .groupBy { it.correspondenceKey!! }

        // 语义无解：同一 corr 在同一快照内对应到多个不同 classId（改名映射冲突）。
        for ((k, objs) in bByKey) {
            val classes = objs.map { it.classId }.toSet()
            if (classes.size > 1) throw SemanticallyAmbiguousException(
                "correspondenceKey=$k 在旧快照映射到多个类，类改名无法唯一决定",
                candidates = classes.toList(), objectIds = objs.map { it.rawObjectId }
            )
        }
        for ((k, objs) in aByKey) {
            val classes = objs.map { it.classId }.toSet()
            if (classes.size > 1) throw SemanticallyAmbiguousException(
                "correspondenceKey=$k 在新快照映射到多个类，类改名无法唯一决定",
                candidates = classes.toList(), objectIds = objs.map { it.rawObjectId }
            )
        }

        val renamed = mutableListOf<RenamedClass>()
        for (k in (bByKey.keys intersect aBy.keys).sorted()) {
            val b = bByKey.getValue(k).first()
            val a = aByKey.getValue(k).first()
            val bName = beforeSnap.classById[b.classId]?.nameRaw ?: b.classId
            val aName = afterSnap.classById[a.classId]?.nameRaw ?: a.classId
            if (b.classId != a.classId || bName != aName) {
                renamed += RenamedClass(
                    correspondenceKey = k,
                    fromClassId = b.classId, fromName = bName,
                    toClassId = a.classId, toName = aName,
                    evidenceObjectKeys = listOf(
                        "${before.snapshotId}#${b.rawObjectId}",
                        "${after.snapshotId}#${a.rawObjectId}"
                    ).sorted()
                )
            }
        }

        // 类群 delta。改名后的两类用 RENAMED 串起来，不计成 ADDED/REMOVED。
        val renamedFrom = renamed.map { it.fromClassId }.toSet()
        val renamedTo = renamed.map { it.toClassId }.toSet()
        val unit = after.classGroups.firstOrNull()?.sizeUnit ?: "BYTE"
        val bMap = before.classGroups.associateBy { it.classId }
        val aMap = after.classGroups.associateBy { it.classId }
        val deltas = mutableListOf<ClassGroupDelta>()
        for ((cid, g) in aMap) {
            val old = bMap[cid]
            val kind = when {
                cid in renamedTo -> "RENAMED"
                old == null -> "ADDED"
                g.objectCount > old.objectCount || g.retainedSizeBytes > old.retainedSizeBytes -> "GREW"
                g.objectCount < old.objectCount || g.retainedSizeBytes < old.retainedSizeBytes -> "SHRANK"
                else -> "STABLE"
            }
            deltas += ClassGroupDelta(cid, g.classNameRaw, old?.objectCount ?: 0, g.objectCount,
                old?.retainedSizeBytes ?: 0L, g.retainedSizeBytes, unit, kind)
        }
        for ((cid, g) in bMap) {
            if (cid !in aMap && cid !in renamedFrom)
                deltas += ClassGroupDelta(cid, g.classNameRaw, g.objectCount, 0, g.retainedSizeBytes, 0L, unit, "REMOVED")
        }
        if (bByKey.isEmpty() && aByKey.isEmpty())
            diagnostics += "NO_CORRESPONDENCE_KEYS: 两个快照均无 corr，类改名只能按类名近似对应（地址未参与）"

        return ClassDiffResult(
            beforeSnapshotId = before.snapshotId,
            afterSnapshotId = after.snapshotId,
            deltas = deltas.sortedWith(compareBy<ClassGroupDelta> { it.kind }.thenByDescending { it.afterRetained }.thenBy { it.classId }),
            renamedClasses = renamed.sortedBy { it.correspondenceKey },
            identityBasis = "correspondenceKey (地址不作身份)",
            diagnostics = diagnostics.sorted()
        )
    }
}
