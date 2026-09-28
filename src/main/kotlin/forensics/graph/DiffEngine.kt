package forensics.graph

import forensics.model.Snapshot
import forensics.graph.GraphEngine.Companion.sha1

/**
 * 两个快照的类群差异。
 * 身份对应优先级：参数中的显式 classCorrespondence > 同名类。
 * 地址从不参与跨快照身份判断。
 */
class DiffEngine {

    fun diff(caseId: String, base: Snapshot, target: Snapshot,
             baseAnalysis: AnalysisResult, targetAnalysis: AnalysisResult,
             params: AnalysisParams): SnapshotDiff {
        val groupsBase = groupsOf(base, baseAnalysis)
        val groupsTarget = groupsOf(target, targetAnalysis)

        val canonicalByBaseClass = params.classCorrespondence.mapNotNull { c ->
            c.members[base.snapshotId]?.let { it to c.canonicalId }
        }.toMap()
        val canonicalByTargetClass = params.classCorrespondence.mapNotNull { c ->
            c.members[target.snapshotId]?.let { it to c.canonicalId }
        }.toMap()

        val remapBase = groupsBase.associate { g ->
            g.canonicalId to (canonicalByBaseClass[g.canonicalId.removePrefix("class:")] ?: g.canonicalId)
        }
        val remapTarget = groupsTarget.associate { g ->
            g.canonicalId to (canonicalByTargetClass[g.canonicalId.removePrefix("class:")] ?: g.canonicalId)
        }

        val b = groupsBase.associateBy { remapBase[it.canonicalId]!! }
        val t = groupsTarget.associateBy { remapTarget[it.canonicalId]!! }
        val allCanonicals = (b.keys + t.keys).toSortedSet()

        val deltas = allCanonicals.map { c ->
            val gb = b[c]; val gt = t[c]
            val renamed = gb != null && gt != null && gb.className != gt.className
            ClassGroupDelta(
                canonicalId = c,
                className = gt?.className ?: gb?.className ?: c,
                renamed = renamed,
                instanceCountDelta = if (gb != null && gt != null) gt.instanceCount - gb.instanceCount else null,
                retainedBytesDelta = if (gb != null && gt != null) gt.retainedBytes - gb.retainedBytes else null,
                onlyInSnapshot = when { gb == null -> target.snapshotId; gt == null -> base.snapshotId; else -> null },
            )
        }

        val diffId = "diff-${sha1("$caseId|${base.snapshotId}|${target.snapshotId}|p${params.paramVersion}").take(12)}"
        return SnapshotDiff(
            diffId = diffId, caseId = caseId,
            baseSnapshotId = base.snapshotId, targetSnapshotId = target.snapshotId,
            paramVersion = params.paramVersion,
            parentDependencies = listOf(baseAnalysis.analysisId, targetAnalysis.analysisId, "params-v${params.paramVersion}"),
            groupsBase = groupsBase, groupsTarget = groupsTarget, deltas = deltas,
        )
    }

    private fun groupsOf(snapshot: Snapshot, analysis: AnalysisResult): List<ClassGroupView> {
        val nodeByObj = analysis.nodes.associateBy { it.objectId }
        return snapshot.classes.sortedBy { it.classId }.map { c ->
            val members = snapshot.objects.filter { it.classId == c.classId }
            val retained = members.sumOf { o -> analysis.retainedBytes["${snapshot.snapshotId}:${o.objectId}"] ?: 0L }
            val shallow = members.sumOf { nodeByObj[it.objectId]?.shallowBytes ?: 0L }
            ClassGroupView(
                canonicalId = "class:${c.classId}",
                className = c.name,
                renamed = false,
                instanceCount = members.size.toLong(),
                shallowBytes = shallow,
                retainedBytes = retained,
            )
        }
    }
}
