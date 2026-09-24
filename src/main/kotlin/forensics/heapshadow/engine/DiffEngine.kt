package forensics.heapshadow.engine

import forensics.heapshadow.model.*

/**
 * 两个快照的类群差异。
 *
 * 类身份对应规则（显式，禁止地址配对）：
 *  1. classRenames 提供跨快照 (classId 或 renameKey) 对应，或
 *  2. 材料层类名相同视为同一逻辑类；
 *  其余视为新增/消失。
 * 对象跨快照对应只用 allocation site + 结构指纹（siteId,className,shape），
 * 地址（尤其重复地址）永不参与。
 */
data class ClassGroupStats(
    val classKey: String,
    val className: String,
    val instanceCount: Int,
    val shallowBytes: Long?,
    val retainedBytes: Long?,
    val incompleteCount: Int,
)

enum class ClassChangeKind { SAME, RENAMED, ADDED, REMOVED, GREW, SHRANK }

data class ClassGroupDelta(
    val change: ClassChangeKind,
    val classKeyA: String?,
    val classKeyB: String?,
    val nameA: String?,
    val nameB: String?,
    val instanceDelta: Int,
    val shallowDeltaBytes: Long?,
    val retainedDeltaBytes: Long?,
    /** 对应依据：NAME_MATCH / RENAME_MAP / SITE_FINGERPRINT。 */
    val matchedBy: String?,
)

data class SnapshotDiff(
    val snapshotIdA: String,
    val snapshotIdB: String,
    val digestA: String,
    val digestB: String,
    val deltas: List<ClassGroupDelta>,
    val identityNotes: List<String>,
    val diagnostics: List<Diagnostic>,
)

data class ClassRename(val classIdA: String, val classIdB: String, val key: String)

class DiffEngine {

    fun diff(
        matA: SnapshotMaterial, matB: SnapshotMaterial,
        resultA: AnalysisResult, resultB: AnalysisResult,
        renames: List<ClassRename> = emptyList(),
    ): SnapshotDiff {
        val diags = mutableListOf<Diagnostic>()
        val notes = mutableListOf(
            "对象身份仅用原始 objectId；跨快照对应依据显式 rename 映射/类名，地址永不参与",
            "重复地址材料全部保留，不影响类群配对",
        )
        val statsA = groupStats(matA, resultA)
        val statsB = groupStats(matB, resultB)

        val keyA = HashMap<String, ClassGroupStats>()
        val keyB = HashMap<String, ClassGroupStats>()
        statsA.forEach { keyA[classKey(it)] = it }
        statsB.forEach { keyB[classKey(it)] = it }

        val renameByA = renames.associateBy({ it.classIdA }, { it })
        val renameByB = renames.associateBy({ it.classIdB }, { it })

        val deltas = mutableListOf<ClassGroupDelta>()
        val matchedB = HashSet<String>()

        for (a in statsA) {
            val rename = renameByA[a.classKey]
            val b: ClassGroupStats? = when {
                rename != null && keyB.containsKey(rename.classIdB) -> {
                    matchedB.add(rename.classIdB); keyB[rename.classIdB]
                }
                else -> statsB.firstOrNull { it.className == a.className }?.also { matchedB.add(it.classKey) }
            }
            if (b == null) {
                deltas.add(
                    ClassGroupDelta(
                        ClassChangeKind.REMOVED, a.classKey, null, a.className, null,
                        -a.instanceCount, neg(a.shallowBytes), neg(a.retainedBytes), null
                    )
                )
                continue
            }
            val renamed = b.className != a.className
            val change = when {
                renamed -> ClassChangeKind.RENAMED
                b.instanceCount > a.instanceCount -> ClassChangeKind.GREW
                b.instanceCount < a.instanceCount -> ClassChangeKind.SHRANK
                else -> ClassChangeKind.SAME
            }
            deltas.add(
                ClassGroupDelta(
                    change, a.classKey, b.classKey, a.className, b.className,
                    b.instanceCount - a.instanceCount,
                    sub(b.shallowBytes, a.shallowBytes), sub(b.retainedBytes, a.retainedBytes),
                    if (rename != null) "RENAME_MAP" else "NAME_MATCH"
                )
            )
        }
        for (b in statsB) {
            if (b.classKey in matchedB) continue
            // 也可能 B 端是 rename 目标（A 已处理）；若该 key 已匹配跳过。
            val rename = renameByB[b.classKey]
            if (rename != null && statsA.any { it.classKey == rename.classIdA }) continue
            deltas.add(
                ClassGroupDelta(
                    ClassChangeKind.ADDED, null, b.classKey, null, b.className,
                    b.instanceCount, b.shallowBytes, b.retainedBytes, null
                )
            )
        }

        val order = listOf(
            ClassChangeKind.RENAMED, ClassChangeKind.ADDED, ClassChangeKind.REMOVED,
            ClassChangeKind.GREW, ClassChangeKind.SHRANK, ClassChangeKind.SAME,
        )
        val sorted = deltas.sortedWith(
            compareBy<ClassGroupDelta> { order.indexOf(it.change) }
                .thenBy { it.nameA ?: it.nameB ?: "" }
        )
        return SnapshotDiff(
            matA.snapshotId, matB.snapshotId,
            resultA.inputDigest.materialSha256, resultB.inputDigest.materialSha256,
            sorted, notes, diags
        )
    }

    private fun classKey(s: ClassGroupStats) = s.classKey

    private fun groupStats(mat: SnapshotMaterial, result: AnalysisResult): List<ClassGroupStats> {
        val retainedById = result.dominators.associate { it.objectId to it.retainedBytes }
        data class Acc(var count: Int, var shallow: Long?, var retained: Long?, var incomplete: Int)
        val map = LinkedHashMap<String, Acc>()
        val names = HashMap<String, String>()
        val classById = mat.classes.associateBy { it.classId }
        for (o in mat.objects.sortedWith(compareBy({ it.ordinal }, { it.objectId }))) {
            val key = o.klassId ?: "<unclassified>"
            val acc = map.getOrPut(key) { Acc(0, 0L, 0L, 0) }
            names[key] = o.klassId?.let { classById[it]?.name } ?: "<unclassified>"
            acc.count++
            acc.shallow = add(acc.shallow, o.shallowSize?.normalizedBytes)
            acc.retained = add(acc.retained, retainedById[o.objectId])
            if (o.incomplete) acc.incomplete++
        }
        return map.map { (k, a) ->
            ClassGroupStats(k, names[k] ?: k, a.count, a.shallow, a.retained, a.incomplete)
        }
    }

    private fun add(x: Long?, y: Long?): Long? = if (x == null || y == null) null else x + y
    private fun sub(a: Long?, b: Long?): Long? = if (a == null || b == null) null else a - b
    private fun neg(x: Long?): Long? = if (x == null) null else -x
}
