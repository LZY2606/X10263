package heapsnapshot.analysis

import heapsnapshot.domain.*

/**
 * 双快照类群差异。对应依据（按优先级）：
 *  1) 显式 renamedFrom* 提示（类改名）；
 *  2) 跨快照稳定 objectId（快照身份不同且对齐时）；
 *  3) 共享 allocation site；
 *  4) 同名。
 * 地址永不用于跨快照对应。无法唯一决定时保留歧义候选。
 */
class ClassDiffEngine {

    fun diff(base: RawSnapshot, other: RawSnapshot, baseAnalysis: AnalysisResult, otherAnalysis: AnalysisResult): SnapshotClassDiff {
        val diags = mutableListOf<ParseDiagnostic>()
        if (base.snapshotId == other.snapshotId) {
            diags += ParseDiagnostic(Severity.ERROR, "SAME_SNAPSHOT_IDENTITY",
                "两份材料快照身份相同，拒绝把它们当双快照比较", "$.diff.snapshotId")
        }
        val baseRetained = retainedByClass(base, baseAnalysis)
        val otherRetained = retainedByClass(other, otherAnalysis)
        val baseCount = base.objects.groupingBy { it.classId }.eachCount()
        val otherCount = other.objects.groupingBy { it.classId }.eachCount()
        val baseSites = sitesByClass(base)
        val otherSites = sitesByClass(other)
        val otherById = other.objects.associateBy { it.objectId }

        val usedOther = HashSet<String>()
        val rows = mutableListOf<ClassGroupRow>()

        for (c in base.classes.sortedBy { it.classId }) {
            val candidates = candidatesFor(c, base, other, otherById, baseSites, otherSites)
            val best = candidates.firstOrNull()
            val ambiguous = candidates.size > 1
            if (ambiguous) {
                diags += ParseDiagnostic(Severity.WARNING, "CLASS_CORRESPONDENCE_AMBIGUOUS",
                    "类 ${c.classId} 有 ${candidates.size} 个合法对应候选（${candidates.joinToString { it.classId }}），保留歧义不作唯一决定",
                    "$.classes(${c.classId})")
            }
            val otherClass: ClassMeta?
            val correspondence: String
            val sharedObjects: List<String>
            val sharedSites: List<String>
            if (best == null) {
                otherClass = null; correspondence = "REMOVED"; sharedObjects = emptyList(); sharedSites = emptyList()
            } else {
                otherClass = best
                usedOther += best.classId
                sharedObjects = sharedStableObjects(base, otherById, c)
                sharedSites = (baseSites[c.classId].orEmpty()).filter { it in (otherSites[best.classId].orEmpty()) }
                correspondence = when {
                    c.renamedFromClassId != null -> "RENAMED"
                    best.name != c.name && (sharedObjects.isNotEmpty() || explicitHintTo(c, best)) -> "RENAMED"
                    best.name == c.name -> "SAME"
                    else -> "RENAMED"
                }
            }
            rows += ClassGroupRow(
                SnapshotRef(base.snapshotId, "class:${c.classId}"),
                otherClass?.let { SnapshotRef(other.snapshotId, "class:${it.classId}") },
                c.name, otherClass?.name, if (ambiguous) "UNMATCHED" else correspondence,
                baseCount[c.classId] ?: 0, (otherClass?.let { otherCount[it.classId] } ?: 0),
                baseRetained[c.classId], otherClass?.let { otherRetained[it.classId] },
                base.header.sizeUnitCanonical,
                ClassCorrespondenceEvidence(
                    explicitRenameHint = c.renamedFromClassId != null,
                    sharedAllocationSiteIds = sharedSites,
                    sharedObjectStableIds = sharedObjects,
                    snapshotIdsAligned = base.snapshotId != other.snapshotId
                )
            )
        }
        other.classes.sortedBy { it.classId }.filter { it.classId !in usedOther }.forEach { oc ->
            rows += ClassGroupRow(
                null, SnapshotRef(other.snapshotId, "class:${oc.classId}"),
                null, oc.name, "ADDED", 0, otherCount[oc.classId] ?: 0,
                null, otherRetained[oc.classId], other.header.sizeUnitCanonical,
                ClassCorrespondenceEvidence(false, emptyList(), emptyList(), false)
            )
        }
        return SnapshotClassDiff(
            base.snapshotId, other.snapshotId,
            rows.sortedWith(compareBy({ it.correspondence }, { it.baseClassRef?.localId ?: "" }, { it.otherClassRef?.localId ?: "" })),
            "snapshot 身份：${base.snapshotId} vs ${other.snapshotId}；跨快照仅用稳定 objectId / 显式改名 / allocation site，不用地址",
            diags
        )
    }

    /** 全部合法对应候选，按证据强度稳定排序；找不到返回空。 */
    private fun candidatesFor(
        c: ClassMeta,
        base: RawSnapshot,
        other: RawSnapshot,
        otherById: Map<String, HeapObject>,
        baseSites: Map<String, List<String>>,
        otherSites: Map<String, List<String>>
    ): List<ClassMeta> {
        val explicit = c.renamedFromClassId?.let { id -> other.classById(id) }
        if (explicit != null) return listOf(explicit)
        val stable = sharedStableObjects(base, otherById, c).mapNotNull { otherById[it]?.classId }.distinct()
            .mapNotNull { other.classById(it) }
        if (stable.isNotEmpty()) return stable
        val sites = baseSites[c.classId].orEmpty()
        val bySite = other.classes.sortedBy { it.classId }.filter { oc ->
            (otherSites[oc.classId].orEmpty()).any { it in sites }
        }
        if (bySite.isNotEmpty()) return bySite
        val byName = other.classes.sortedBy { it.classId }.filter { it.name == c.name }
        return if (byName.isNotEmpty()) byName else emptyList()
    }

    private fun explicitHintTo(c: ClassMeta, target: ClassMeta) = c.renamedFromClassId == target.classId

    private fun retainedByClass(s: RawSnapshot, a: AnalysisResult): Map<String, Double> {
        val retained = a.nodes.associate { it.objectId to it.retainedBytes }
        return s.objects.groupBy { it.classId }.mapValues { (_, os) -> os.sumOf { retained[it.objectId] ?: 0.0 } }
    }

    private fun sitesByClass(s: RawSnapshot) =
        s.objects.filter { it.allocationSiteId != null }.groupBy({ it.classId }, { it.allocationSiteId!! })
            .mapValues { (_, v) -> v.distinct().sorted() }

    private fun sharedStableObjects(base: RawSnapshot, otherById: Map<String, HeapObject>, c: ClassMeta) =
        base.objects.filter { it.classId == c.classId && it.objectId in otherById }
            .map { it.objectId }.distinct().sorted()
}
