package com.heaptrace.domain

/**
 * 两个快照的类群差异：按稳定 class id 对应，名称变化识别为“改名”而非“删除+新增”。
 * 地址、对象数量的变化都不能把同一个类误判成不同类。
 */
object ClassDiffEngine {

    fun diff(resultA: AnalysisResult, resultB: AnalysisResult): ClassGroupDiff {
        val a = resultA.classGroups.associateBy { it.classId }.toSortedMap()
        val b = resultB.classGroups.associateBy { it.classId }.toSortedMap()

        val renamed = mutableListOf<ClassRename>()
        val changed = mutableListOf<ClassMetricDelta>()
        for (id in a.keys.filter { it in b.keys }.sorted()) {
            val ga = a.getValue(id)
            val gb = b.getValue(id)
            if (ga.name != gb.name) renamed += ClassRename(id, ga.name, gb.name)
            if (ga.instanceCount != gb.instanceCount ||
                ga.reachableCount != gb.reachableCount ||
                ga.shallowBytes != gb.shallowBytes ||
                ga.retainedBytes != gb.retainedBytes
            ) {
                changed += ClassMetricDelta(
                    classId = id,
                    name = gb.name,
                    deltaInstances = gb.instanceCount - ga.instanceCount,
                    deltaReachable = gb.reachableCount - ga.reachableCount,
                    deltaShallowBytes = gb.shallowBytes - ga.shallowBytes,
                    deltaRetainedBytes = gb.retainedBytes - ga.retainedBytes
                )
            }
        }
        val removed = a.keys.filter { it !in b.keys }.map { a.getValue(it) }
            .sortedBy { it.classId }
        val added = b.keys.filter { it !in a.keys }.map { b.getValue(it) }
            .sortedBy { it.classId }
        return ClassGroupDiff(
            snapshotA = resultA.snapshotId,
            snapshotB = resultB.snapshotId,
            renamed = renamed.sortedBy { it.classId },
            added = added,
            removed = removed,
            changed = changed.sortedBy { it.classId }
        )
    }
}
