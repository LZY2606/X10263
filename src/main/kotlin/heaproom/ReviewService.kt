package heaproom

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ReviewState(
    val atSeq: Int,
    val snapshotIds: List<String>,
    val param: ParamRow?,
    val activeDecisions: List<DecisionRow>,
    val retractedDecisions: List<DecisionRow>,
    val conflicts: List<String>,
    val latestSeq: Int
)

object ReviewService {

    fun state(storage: Storage, atSeq: Int?): ReviewState {
        val allEvents = storage.events()
        val ceiling = atSeq ?: allEvents.lastOrNull()?.seq ?: 0
        val events = allEvents.filter { it.seq <= ceiling }

        val snapshotIds = storage.listSnapshots()
            .filter { it.importedSeq <= ceiling }
            .map { it.doc.snapshotId }

        val params = storage.paramSets().filter { it.eventSeq <= ceiling }
        val param = params.maxByOrNull { it.eventSeq }

        val decisions = storage.decisions().filter { it.eventSeq <= ceiling }
        val active = decisions.filter { it.retractedByEvent == null || it.retractedByEvent > ceiling }
        val retracted = decisions.filter { !(it.retractedByEvent == null || it.retractedByEvent > ceiling) }
        val conflicts = active.groupBy { it.candidateId }
            .filter { (_, ds) -> ds.map { it.action }.toSet().size > 1 }
            .map { (cand, ds) ->
                "候选 $cand 同时存在互相冲突且未撤回的人工决定: ${ds.joinToString { "${it.action}@${it.eventSeq}" }}"
            }

        return ReviewState(ceiling, snapshotIds, param, active, retracted, conflicts,
            allEvents.lastOrNull()?.seq ?: 0)
    }

    /** 计算结果带输入摘要、参数版本与父级依赖；缓存按 (snapshotId, digest, paramVersion)。 */
    fun compute(storage: Storage, snapshotId: String, atSeq: Int?): Pair<ComputeOutcome<AnalysisResult>, ComputationRow?> {
        val st = state(storage, atSeq)
        val param = st.param ?: return ComputeOutcome.SemanticUnsolvable(
            reason = "尚未声明任何参数版本，弱引用保活策略未显式给出，拒绝按默认值计算"
        ) to null
        val doc = storage.loadSnapshot(snapshotId)
        val digest = Engine.canonicalDigest(doc)
        val params = ParamSet(param.version, param.weakPolicy, param.nodeBudget)
        val historical = atSeq != null
        val existing = if (!historical) storage.computations(snapshotId).firstOrNull {
            it.inputDigest == digest && it.paramVersion == param.version
        } else null
        if (existing != null) {
            if (existing.status != "OK") return ComputeOutcome.SemanticUnsolvable(
                existing.invalidationReason ?: "stored non-OK", emptyList()
            ) to existing
            return ComputeOutcome.Ok(JsonFmt.decodeFromString(AnalysisResult.serializer(), existing.resultJson)) to existing
        }
        val outcome = Engine.analyze(doc, params)
        if (historical) return outcome to null
        val seq = storage.events().lastOrNull()?.seq ?: 0
        val row = when (outcome) {
            is ComputeOutcome.Ok -> ComputationRow(
                computeId = "cmp:$snapshotId:${digest.take(10)}:${param.version}",
                snapshotId = snapshotId, inputDigest = digest, paramVersion = param.version,
                parentEventSeq = param.eventSeq, status = "OK",
                resultJson = JsonFmt.encodeToString(AnalysisResult.serializer(), outcome.value),
                validFromEvent = st.atSeq, validUntilEvent = null, invalidationReason = null
            )
            is ComputeOutcome.BudgetExhausted -> ComputationRow(
                computeId = "cmp:$snapshotId:${digest.take(10)}:${param.version}",
                snapshotId = snapshotId, inputDigest = digest, paramVersion = param.version,
                parentEventSeq = param.eventSeq, status = "BUDGET_EXHAUSTED",
                resultJson = "{}", validFromEvent = st.atSeq, validUntilEvent = null,
                invalidationReason = "预算耗尽 scope=${outcome.scope} limit=${outcome.limit} consumed=${outcome.consumed}"
            )
            is ComputeOutcome.SemanticUnsolvable -> ComputationRow(
                computeId = "cmp:$snapshotId:${digest.take(10)}:${param.version}",
                snapshotId = snapshotId, inputDigest = digest, paramVersion = param.version,
                parentEventSeq = param.eventSeq, status = "SEMANTIC_UNSOLVABLE",
                resultJson = "{}", validFromEvent = st.atSeq, validUntilEvent = null,
                invalidationReason = outcome.reason
            )
        }
        storage.tx { c ->
            storage.putComputation(c, row)
            val payload = JsonObject(mapOf(
                "snapshotId" to JsonPrimitive(snapshotId),
                "inputDigest" to JsonPrimitive(digest),
                "paramVersion" to JsonPrimitive(param.version),
                "status" to JsonPrimitive(row.status),
                "parentEventSeq" to JsonPrimitive(row.parentEventSeq)
            )).toString()
            storage.appendEvent(c, "COMPUTED", payload, listOf(param.eventSeq))
        }
        return outcome to row
