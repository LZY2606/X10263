package org.forensics.heap.analysis

import org.forensics.heap.model.AnalysisResult
import org.forensics.heap.model.ClassGroupDelta
import org.forensics.heap.model.RawSnapshot

/**
 * 两快照类群差异。对应规则：
 *  - 以 classId（稳定身份）对应，地址永不参与；
 *  - 名字变化显式标 renamed=true，但不拆成“一删一增”；
 *  - 快照身份由 SNAPSHOT identity 配对（不匹配时由调用方决定是否拒绝）。
 *  - 等长 group 顺序按 classId 稳定排序。
 */
object SnapshotDiff {

    fun diff(before: RawSnapshot, beforeResult: AnalysisResult, after: RawSnapshot, afterResult: AnalysisResult): List<ClassGroupDelta> {
        val beforeMap = beforeResult.classGroups.associateBy { it.classId }
        val afterMap = afterResult.classGroups.associateBy { it.classId }
        val beforeClassNames = before.classes.associate { it.classId to it.rawName }
        val afterClassNames = after.classes.associate { it.classId to it.rawName }

        val ids = (beforeMap.keys + afterMap.keys).toSortedSet()
        return ids.map { id ->
            val b = beforeMap[id]
            val a = afterMap[id]
            val nameB = beforeClassNames[id] ?: b?.className ?: "?"
            val nameA = afterClassNames[id] ?: a?.className ?: "?"
            ClassGroupDelta(
                classId = id,
                nameBefore = nameB,
                nameAfter = nameA,
                renamed = b != null && a != null && nameB != nameA,
                countBefore = b?.instanceCount ?: 0,
                countAfter = a?.instanceCount ?: 0,
                shallowBefore = b?.shallowBytes ?: 0L,
                shallowAfter = a?.shallowBytes ?: 0L,
                retainedBefore = b?.retainedBytes ?: 0L,
                retainedAfter = a?.retainedBytes ?: 0L,
                onlyIn = when {
                    b == null && a != null -> "after"
                    a == null && b != null -> "before"
                    else -> null
                }
            )
        }
    }
}
