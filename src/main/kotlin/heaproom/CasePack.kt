package heaproom

import java.io.File

object CasePack {

    @Serializable
    private data class PatchDoc(
        val format: String,
        val patchId: String,
        val derivedFrom: String,
        val description: String,
        val newSnapshotId: String,
        val changes: List<Change>
    ) {
        @Serializable
        data class Change(val op: String, val objectId: String, val size: Quantity? = null)
    }

    fun applyPatch(base: SnapshotDoc, p: PatchDoc): SnapshotDoc =
        base.copy(
            snapshotId = p.newSnapshotId,
            objects = base.objects.map { o ->
                val ch = p.changes.firstOrNull { it.objectId == o.objectId && it.op == "replace-size" }
                if (ch != null && ch.size != null) o.copy(size = ch.size) else o
            }
        )

    fun ensureLoaded(storage: Storage, dir: File) {
        storage.init()
        val already = storage.listSnapshots()
        if (already.any { it.doc.snapshotId == "snap-baseline" }) return

        val baseline = JsonFmt.decodeFromString(SnapshotDoc.serializer(), File(dir, "baseline.json").readText())
        val v2 = JsonFmt.decodeFromString(SnapshotDoc.serializer(), File(dir, "snapshot-v2.json").readText())
        val patch = JsonFmt.decodeFromString(
            PatchDoc.serializer(), File(dir, "mutations/patch-local-source.json").readText()
        )
        val patched = applyPatch(baseline, patch)

        storage.tx { c ->
            fun event(kind: String, payload: String, parents: List<Int> = emptyList()) =
                storage.appendEvent(c, kind, payload, parents)

            fun payloadOf(vararg kv: Pair<String, String>) =
                kotlinx.serialization.json.JsonObject(kv.toMap().mapValues {
                    kotlinx.serialization.json.JsonPrimitive(it.value)
                }).toString()

            val r1 = storage.importSnapshot(c, baseline, "casepack:baseline.json")
            check(r1 is ImportOutcome.Ok) { "案例包基线导入失败: $r1" }
            val e1 = storage.appendEvent(c, "IMPORT_SNAPSHOT",
                payloadOf("snapshotId" to baseline.snapshotId, "source" to "casepack/baseline.json",
                    "parserVersion" to baseline.parserVersion))

            val r2 = storage.importSnapshot(c, v2, "casepack:snapshot-v2.json")
            check(r2 is ImportOutcome.Ok) { "案例包 v2 导入失败: $r2" }
            val e2 = storage.appendEvent(c, "IMPORT_SNAPSHOT",
                payloadOf("snapshotId" to v2.snapshotId, "source" to "casepack/snapshot-v2.json",
                    "parserVersion" to v2.parserVersion))

            val e3seq = e2.seq + 1
            storage.putParamSet(c, ParamRow("param-v1", WeakPolicy.WEAK_IGNORED, 10000, e3seq))
            val e3 = event("PARAM_REVISION",
                payloadOf("version" to "param-v1", "weakPolicy" to "WEAK_IGNORED", "nodeBudget" to "10000"),
                listOf(e1.seq, e2.seq))

            val e4seq = e3.seq + 1
            storage.putParamSet(c, ParamRow("param-v2", WeakPolicy.WEAK_KEEP_ALIVE, 10000, e4seq))
            val e4 = event("PARAM_REVISION",
                payloadOf("version" to "param-v2", "weakPolicy" to "WEAK_KEEP_ALIVE", "nodeBudget" to "10000",
                    "supersedes" to "param-v1"),
                listOf(e3.seq))

            val r5 = storage.importSnapshot(c, patched, "casepack:mutations/patch-local-source.json")
            check(r5 is ImportOutcome.Ok) { "案例包补丁快照导入失败: $r5" }
            val e5 = event("SOURCE_PATCH",
                payloadOf("patchId" to patch.patchId, "derivedFrom" to patch.derivedFrom,
                    "newSnapshotId" to patch.newSnapshotId, "description" to patch.description,
                    "changedObject" to "obj-0004"),
                listOf(e1.seq, e4.seq))

            val candidate = "leak-cand:snap-baseline:obj-0016"
            val d1Id = "dec-accept-obj0016"
            storage.putDecision(c, DecisionRow(
                d1Id, candidate, "snap-baseline", "ACCEPT",
                "obj-0016 经 obj-0002(target) 可达，疑似 Cache 泄漏，先接受为泄漏候选", e5.seq + 1, null))
            val e6 = event("DECISION",
                payloadOf("decisionId" to d1Id, "candidateId" to candidate, "action" to "ACCEPT"),
                listOf(e5.seq))

            val d2Id = "dec-reject-obj0016"
            storage.putDecision(c, DecisionRow(
                d2Id, candidate, "snap-baseline", "REJECT",
                "复核认为 obj-0016 是合法 target 缓存条目，拒绝泄漏结论（与事件 6 冲突，二者并存）",
                e6.seq + 1, null))
            event("DECISION",
                payloadOf("decisionId" to d2Id, "candidateId" to candidate, "action" to "REJECT",
                    "conflictsWith" to d1Id),
                listOf(e6.seq))
        }
    }
}
