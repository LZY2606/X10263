package heapforensics.core

import kotlinx.serialization.Serializable

/**
 * 两个快照的类群差异。类跨快照的身份是 classId（不是类名）：
 * 类改名被识别为 RENAMED 而不是“删除+新增”；对象跨快照身份是 objectId（不是地址）。
 */
object ClassDiff {

    enum class Status { PERSISTED, RENAMED, ADDED, REMOVED }

    @Serializable
    data class ClassGroup(
        val classId: String,
        val nameA: String?,
        val nameB: String?,
        val status: Status,
        val countA: Int,
        val countB: Int,
        val bytesA: Long,
        val bytesB: Long,
    ) {
        val countDelta: Int get() = countB - countA
        val bytesDelta: Long get() = bytesB - bytesA
    }

    @Serializable
    data class SnapshotIdentityNote(
        val note: String,
        val examples: List<String>,
    )

    @Serializable
    data class DiffResult(
        val snapshotA: String,
        val snapshotB: String,
        val groups: List<ClassGroup>,
        val identityNotes: List<SnapshotIdentityNote>,
    )

    fun diff(a: HeapSnapshot, b: HeapSnapshot): DiffResult {
        val byIdA = a.classIndex
        val byIdB = b.classIndex
        val countA = a.objects.groupingBy { it.classId }.eachCount()
        val countB = b.objects.groupingBy { it.classId }.eachCount()
        val bytesA = a.objects.groupBy { it.classId }.mapValues { (_, v) -> v.sumOf { it.sizeBytes } }
        val bytesB = b.objects.groupBy { it.classId }.mapValues { (_, v) -> v.sumOf { it.sizeBytes } }

        val allIds = (byIdA.keys + byIdB.keys).sorted()
        val groups = allIds.map { id ->
            val ca = byIdA[id]
            val cb = byIdB[id]
            val status = when {
                ca != null && cb == null -> Status.REMOVED
                ca == null && cb != null -> Status.ADDED
                ca != null && cb != null && ca.name != cb.name -> Status.RENAMED
                else -> Status.PERSISTED
            }
            ClassGroup(
                classId = id,
                nameA = ca?.name,
                nameB = cb?.name,
                status = status,
                countA = countA[id] ?: 0,
                countB = countB[id] ?: 0,
                bytesA = bytesA[id] ?: 0,
                bytesB = bytesB[id] ?: 0,
            )
        }.sortedWith(compareBy({ it.status.ordinal }, { it.classId }))

        val notes = mutableListOf<SnapshotIdentityNote>()
        val renamed = groups.filter { it.status == Status.RENAMED }
        if (renamed.isNotEmpty()) {
            notes += SnapshotIdentityNote(
                note = "类改名通过 classId 对应快照身份，不按名称拆分",
                examples = renamed.map { "${it.classId}: ${it.nameA} -> ${it.nameB}" },
            )
        }
        val addrA = a.objects.associate { it.objectId to it.address }
        val addrB = b.objects.associate { it.objectId to it.address }
        val moved = (addrA.keys intersect addrB.keys).filter { addrA[it] != addrB[it] }.sorted()
        if (moved.isNotEmpty()) {
            notes += SnapshotIdentityNote(
                note = "同一 objectId 跨快照地址变化：地址不是稳定身份，对应关系按 objectId 建立",
                examples = moved.map { "$it: ${addrA[it]} -> ${addrB[it]}" },
            )
        }
        return DiffResult(a.snapshotId, b.snapshotId, groups, notes)
    }
}
