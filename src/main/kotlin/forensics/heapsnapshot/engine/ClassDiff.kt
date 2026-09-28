package forensics.heapsnapshot.engine

import forensics.heapsnapshot.domain.Diagnostic
import forensics.heapsnapshot.domain.ParsedSnapshot

/**
 * 两个快照的类群差异。
 * 身份依据 identityBasis 必须可追溯：
 *  - CORRESPONDENCE_DIRECTIVE：显式 CORRESPOND class 指令（类改名）
 *  - STABLE_CLASS_KEY：类记录中的 KEY
 *  - CLASS_ID：原始类 id
 * 名称从不参与身份判定（改名是数据，不是不同类）。
 */
object ClassDiffEngine {

    fun diff(before: ParsedSnapshot, after: ParsedSnapshot, afterResult: AnalysisResult? = null): ClassGroupDiff {
        val renameMap = after.correspondences
            .filter { it.kind == "class" }
            .associate { it.fromKey to it.toKey }
        val unmatched = mutableListOf<Diagnostic>()

        // 校验 CORRESPOND 两端都存在（按 key/id）
        val beforeKeys = before.classes.map { it.key }.toSet() + before.classes.map { it.id }.toSet()
        val afterKeys = after.classes.map { it.key }.toSet() + after.classes.map { it.id }.toSet()
        renameMap.forEach { (from, to) ->
            if (from !in beforeKeys || to !in afterKeys) {
                unmatched.add(
                    Diagnostic(
                        "CORRESPONDENCE_UNMATCHED", Diagnostic.Level.WARNING, 0, 0,
                        "correspondence.class", "类改名对应无法解析：$from -> $to", null,
                    ),
                )
            }
        }

        data class CInfo(val key: String, val name: String, val count: Int, val retained: Long?, val basis: String)

        fun groups(snap: ParsedSnapshot): Map<String, CInfo> {
            val byClass = snap.objects.groupBy { it.classId }
            return snap.classes.map { c ->
                val objs = byClass[c.id].orEmpty()
                val retainedTotal = if (objs.all { it.size.valueBytes != null })
                    objs.sumOf { it.size.valueBytes!! } else null
                c.key to CInfo(c.key, c.name, objs.size, retainedTotal, "STABLE_CLASS_KEY")
            }.toMap()
        }

        val g1 = groups(before)
        val g2Base = groups(after)
        // 应用显式改名映射：把 after 的 key 重写为 before 侧 key，identityBasis 标记为指令
        val g2 = HashMap<String, CInfo>()
        g2Base.forEach { (key, info) ->
            val renameFrom = renameMap.entries.firstOrNull { it.value == key }?.key
            if (renameFrom != null) g2[renameFrom] = info.copy(key = renameFrom, basis = "CORRESPONDENCE_DIRECTIVE")
            else g2[key] = info
        }

        val allKeys = (g1.keys + g2.keys).toSortedSet()
        val deltas = allKeys.map { key ->
            val a = g1[key]
            val b = g2[key]
            val basis = when {
                a == null || b == null -> if (b?.basis == "CORRESPONDENCE_DIRECTIVE" || renameMap.containsValue(key))
                    "CORRESPONDENCE_DIRECTIVE" else "STABLE_CLASS_KEY"
                b.basis == "CORRESPONDENCE_DIRECTIVE" -> "CORRESPONDENCE_DIRECTIVE"
                else -> "STABLE_CLASS_KEY"
            }
            val renamedByDirective = renameMap[key] != null
            val verdict = when {
                a == null -> "ADDED_IN_AFTER"
                b == null -> "REMOVED_IN_AFTER"
                renamedByDirective && a.name != b.name -> "RENAMED_VIA_DIRECTIVE"
                renamedByDirective -> "IDENTITY_VIA_DIRECTIVE"
                a.name != b.name -> "NAME_CHANGED_SAME_IDENTITY"
                b.count > a.count -> "INSTANCE_COUNT_GREW"
                b.count < a.count -> "INSTANCE_COUNT_SHRANK"
                else -> "UNCHANGED"
            }
            ClassGroupDelta(
                classKey = key,
                nameBefore = a?.name,
                nameAfter = b?.name,
                countBefore = a?.count ?: 0,
                countAfter = b?.count ?: 0,
                retainedBefore = a?.retained,
                retainedAfter = b?.retained,
                verdict = verdict,
                identityBasis = basis,
            )
        }

        return ClassGroupDiff(
            snapshotBefore = before.meta.identity,
            snapshotAfter = after.meta.identity,
            paramsVersion = afterResult?.paramsVersion ?: 0,
            deltas = deltas.sortedWith(compareBy({ it.classKey }, { it.nameBefore ?: "" })),
            unmatchedCorrespondences = unmatched,
        )
    }
}
