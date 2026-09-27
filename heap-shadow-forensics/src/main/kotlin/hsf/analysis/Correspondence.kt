package hsf.analysis

import hsf.model.*

/**
 * Cross-snapshot object correspondence. Addresses are candidates only, never
 * authoritative. Every match reports its basis so the UI can show it as a
 * stable identity plus provenance.
 */
object Correspondence {
    data class Match(
        val before: String,
        val after: String,
        val basis: String,
        val addressCandidate: Boolean,
        val confident: Boolean
    )

    fun match(beforeSnap: HeapSnapshot, afterSnap: HeapSnapshot): List<Match> {
        val bp = beforeSnap.snapshotId + "#"
        val ap = afterSnap.snapshotId + "#"
        val bClassRename = beforeSnap.classes.associate { it.classId to (it.renameKey ?: it.classId) }
        val aClassByRename = afterSnap.classes.associateBy { it.renameKey ?: it.classId }

        val matches = mutableListOf<Match>()
        // Basis 1: same object id (runtime-stable id space) - strongest.
        val afterById = afterSnap.objects.associateBy { it.objectId }
        for (b in beforeSnap.objects) {
            val a = afterById[b.objectId]
            if (a != null) matches += Match(bp + b.objectId, ap + a.objectId, "stable-object-id", false, true)
        }
        val matchedAfter = matches.map { it.after }.toSet()

        // Basis 2: allocation site + class(rename-aware) + size signature.
        val aBySig = afterSnap.objects.filter { ap + it.objectId !in matchedAfter }
            .groupBy { o ->
                val clsKey = o.classId
                Triple(o.allocationSiteId, clsKey, o.sizeRaw)
            }
        val matchedAfter2 = matchedAfter.toMutableSet()
        for (b in beforeSnap.objects) {
            if (matches.any { it.before == bp + b.objectId }) continue
            val bRename = bClassRename[b.classId]
            val aClassId = aClassByRename[bRename]?.classId
            val cand = afterSnap.objects.filter { ap + it.objectId !in matchedAfter2 }
                .filter { it.allocationSiteId == b.allocationSiteId && it.classId == aClassId && it.sizeRaw == b.sizeRaw }
            if (cand.size == 1) {
                matches += Match(bp + b.objectId, ap + cand[0].objectId, "allocsite+class+size", false, true)
                matchedAfter2 += ap + cand[0].objectId
            }
        }

        // Basis 3: reused address - a weak candidate only, never adopted silently.
        val aByAddr = afterSnap.objects.mapNotNull { o -> o.addressRaw?.let { it to o } }.toMap()
        for (b in beforeSnap.objects) {
            if (matches.any { it.before == bp + b.objectId }) continue
            val addr = b.addressRaw ?: continue
            val a = aByAddr[addr] ?: continue
            matches += Match(bp + b.objectId, ap + a.objectId, "address-reuse-candidate", true, false)
        }
        return matches.sortedWith(compareBy({ it.basis }, { it.before }))
    }
}
