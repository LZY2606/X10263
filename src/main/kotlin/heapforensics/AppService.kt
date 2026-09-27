package heapforensics

import heapforensics.analysis.*
import heapforensics.domain.*
import heapforensics.ingest.ParseOutcome
import heapforensics.ingest.SnapshotParser
import heapforensics.store.DecisionRow
import heapforensics.store.Event
import heapforensics.store.Store
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 导入快照的结果：成功（含诊断）或格式损坏。 */
sealed interface ImportSnapOutcome {
    data class Ok(val snapshot: RawSnapshot, val digest: String, val eventId: String?, val alreadyPresent: Boolean, val diagnostics: List<Diagnostic>) : ImportSnapOutcome
    data class Corrupt(val diagnostics: List<Diagnostic>, val eventId: String?) : ImportSnapOutcome
}

sealed interface AnalysisRunOutcome {
    data class Done(val row: heapforensics.store.AnalysisRow, val outcome: AnalysisOutcome, val eventId: String) : AnalysisRunOutcome
    data class SnapshotMissing(val snapshotId: String) : AnalysisRunOutcome
}

/** 服务端业务规则：web 层与案例回放共用，可被测试直接调用。 */
class AppService(val store: Store) {

    fun importSnapshot(text: String, sourceName: String, parents: List<String> = emptyList()): ImportSnapOutcome {
        return when (val parsed = SnapshotParser.parse(text, sourceName)) {
            is ParseOutcome.Corrupt -> {
                val ev = store.inBatch("snapshot-rejected") { bid ->
                    store.appendEvent("snapshot.rejected", buildJsonObject {
                        put("sourceName", sourceName)
                        put("diagnostics", diagnosticsJson(parsed.diagnostics))
                    }, parents, bid)
                }
                ImportSnapOutcome.Corrupt(parsed.diagnostics, ev.id)
            }
            is ParseOutcome.Ok -> {
                val snapshot = parsed.snapshot
                val digest = digestOf(snapshot)
                if (store.hasSnapshot(snapshot.snapshotId, digest)) {
                    return ImportSnapOutcome.Ok(snapshot, digest, null, alreadyPresent = true, parsed.diagnostics)
                }
                val isAmendment = store.hasSnapshot(snapshot.snapshotId)
                val oldDigest = store.snapshotDigests(snapshot.snapshotId).lastOrNull()
                val ev = store.inBatch("snapshot-import") { bid ->
                    val event = if (isAmendment) {
                        val invalidated = store.invalidateAnalyses(
                            snapshot.snapshotId, digest = oldDigest, keepAnalysisId = null,
                            reason = "源数据修订: 输入摘要 ${oldDigest?.take(12)}… → ${digest.take(12)}…",
                            byEvent = "pending", batchId = bid
                        )
                        val e = store.appendEvent("source.amended", buildJsonObject {
                            put("snapshotId", snapshot.snapshotId)
                            put("fromDigest", oldDigest ?: "")
                            put("toDigest", digest)
                            put("sourceName", sourceName)
                            put("parserVersion", snapshot.parserVersion)
                            put("diagnostics", diagnosticsJson(parsed.diagnostics))
                            put("invalidated", JsonArray(invalidated.map { JsonPrimitive(it) }))
                        }, parents, bid)
                        for (id in invalidated) {
                            store.exec("UPDATE analyses SET superseded_by=? WHERE analysis_id=?", e.id, id)
                        }
                        e
                    } else {
                        store.appendEvent("snapshot.imported", buildJsonObject {
                            put("snapshotId", snapshot.snapshotId)
                            put("digest", digest)
                            put("sourceName", sourceName)
                            put("capturedAt", snapshot.capturedAt)
                            put("parserVersion", snapshot.parserVersion)
                            put("counts", buildJsonObject {
                                put("classes", snapshot.classes.size)
                                put("objects", snapshot.objects.size)
                                put("edges", snapshot.edges.size)
                                put("roots", snapshot.roots.size)
                                put("incompleteBlocks", snapshot.incompleteBlocks.size)
                            })
                            put("diagnostics", diagnosticsJson(parsed.diagnostics))
                        }, parents, bid)
                    }
                    store.putSnapshot(snapshot, text, digest, event.id, bid)
                    event
                }
                ImportSnapOutcome.Ok(snapshot, digest, ev.id, alreadyPresent = false, parsed.diagnostics)
            }
        }
    }

    fun runAnalysis(
        snapshotId: String,
        digest: String? = null,
        params: AnalysisParams,
        extraParents: List<String> = emptyList()
    ): AnalysisRunOutcome {
        val snapshot = store.readSnapshot(snapshotId, digest)
            ?: return AnalysisRunOutcome.SnapshotMissing(snapshotId)
        val actualDigest = digest ?: digestOf(snapshot)
        val outcome = Engine.analyze(snapshot, params)
        val importEvent = store.snapshotImportEvent(snapshotId, actualDigest)
        val parents = (listOfNotNull(importEvent) + extraParents).distinct()
        val existing = store.analysis(store.analysisId(snapshotId, actualDigest, params.paramVersion))
        if (existing != null) {
            return AnalysisRunOutcome.Done(existing, outcome, existing.eventId)
        }
        val result = store.inBatch("analysis") { bid ->
            val ev = store.appendEvent("analysis.computed", buildJsonObject {
                put("snapshotId", snapshotId)
                put("inputDigest", actualDigest)
                put("analysisId", store.analysisId(snapshotId, actualDigest, params.paramVersion))
                put("paramVersion", params.paramVersion)
                put("params", buildJsonObject {
                    put("weakPolicy", params.weakPolicy.name)
                    put("maxNodes", params.limits.maxNodes)
                    put("maxEdges", params.limits.maxEdges)
                    put("maxRootPaths", params.limits.maxRootPaths)
                })
                put("outcome", outcomeName(outcome))
                put("conclusion", conclusionJson(outcome))
            }, parents, bid)
            val row = store.putAnalysis(
                snapshotId, actualDigest, params,
                outcome = outcomeName(outcome),
                resultJson = (outcome as? AnalysisOutcome.Ok)?.let {
                    heapforensics.store.JSON.encodeToString(AnalysisResult.serializer(), it.result)
                },
                outcomeDetail = when (outcome) {
                    is AnalysisOutcome.BudgetExhausted -> """{"resource":"${outcome.resource}","limit":${outcome.limit},"position":"${outcome.position}"}"""
                    is AnalysisOutcome.SemanticUnsolvable -> diagnosticsJson(outcome.diagnostics).toString()
                    else -> null
                },
                eventId = ev.id, batchId = bid
            )
            row to ev.id
        }
        return AnalysisRunOutcome.Done(result.first, outcome, result.second)
    }

    /** 参数修订：显式记录弱引用策略变化，旧分析标记失效（有效区间 + 失效原因），历史不覆盖。 */
    fun reviseParams(snapshotId: String, weakPolicy: WeakPolicy, reason: String): Pair<Event, AnalysisRunOutcome>? {
        val snapshot = store.readSnapshot(snapshotId) ?: return null
        val digest = digestOf(snapshot)
        val existing = store.distinctParamVersions(snapshotId)
        val newVersion = "v${existing.size + 1}-" + weakPolicy.name.lowercase().replace('_', '-')
        val params = AnalysisParams(weakPolicy = weakPolicy, paramVersion = newVersion)
        var revisedEvent: Event? = null
        val run = store.inBatch("params-revise") { bid ->
            val invalidated = store.invalidateAnalyses(
                snapshotId, digest = digest, keepAnalysisId = null,
                reason = "参数修订: 弱引用策略调整为 ${weakPolicy.name}（$newVersion）",
                byEvent = "pending", batchId = bid
            )
            val ev = store.appendEvent("params.revised", buildJsonObject {
                put("snapshotId", snapshotId)
                put("inputDigest", digest)
                put("toParamVersion", newVersion)
                put("weakPolicy", weakPolicy.name)
                put("reason", reason)
                put("invalidated", JsonArray(invalidated.map { JsonPrimitive(it) }))
            }, listOfNotNull(store.snapshotImportEvent(snapshotId, digest)), bid)
            for (id in invalidated) {
                store.exec("UPDATE analyses SET superseded_by=? WHERE analysis_id=?", ev.id, id)
            }
            revisedEvent = ev
            // 同一批次内用新参数重算
            val outcome = Engine.analyze(snapshot, params)
            val analysisEvent = store.appendEvent("analysis.computed", buildJsonObject {
                put("snapshotId", snapshotId)
                put("inputDigest", digest)
                put("analysisId", store.analysisId(snapshotId, digest, newVersion))
                put("paramVersion", newVersion)
                put("params", buildJsonObject { put("weakPolicy", weakPolicy.name) })
                put("outcome", outcomeName(outcome))
                put("conclusion", conclusionJson(outcome))
            }, listOf(ev.id), bid)
            val row = store.putAnalysis(
                snapshotId, digest, params, outcomeName(outcome),
                resultJson = (outcome as? AnalysisOutcome.Ok)?.let {
                    heapforensics.store.JSON.encodeToString(AnalysisResult.serializer(), it.result)
                },
                outcomeDetail = null, eventId = analysisEvent.id, batchId = bid
            )
            AnalysisRunOutcome.Done(row, outcome, analysisEvent.id)
        }
        return revisedEvent!! to run
    }

    /** 人工决定：接受/否定/参数调整都生成事件；互相冲突的决定全部保留并互相引用。 */
    fun decide(target: String, verdict: String, author: String, rationale: String): DecisionRow {
        return store.inBatch("decision") { bid ->
            val conflicts = store.decisions().filter { it.target == target && it.verdict != verdict }
            val ev = store.appendEvent("decision.recorded", buildJsonObject {
                put("target", target)
                put("verdict", verdict)
                put("author", author)
                put("rationale", rationale)
                put("conflictWith", JsonArray(conflicts.map { JsonPrimitive(it.id) }))
            }, conflicts.map { it.eventId }, bid)
            store.putDecision(target, verdict, rationale, author, ev.id, bid)
        }
    }

    /** 两个快照的类群差异：按 classId 对应（快照身份对应），名称变化记为改名。 */
    fun computeDiff(aId: String, bId: String, recordEvent: Boolean = true): ClassDiff? {
        val a = store.readSnapshot(aId) ?: return null
        val b = store.readSnapshot(bId) ?: return null
        val diff = ClassDiffer.diff(a, b)
        if (recordEvent) {
            store.inBatch("class-diff") { bid ->
                store.appendEvent("diff.computed", buildJsonObject {
                    put("snapshotA", aId)
                    put("snapshotB", bId)
                    put("identityBasis", "classId（地址不作为稳定身份）")
                    put("renamed", diff.count(DiffStatus.RENAMED))
                    put("added", diff.count(DiffStatus.ADDED))
                    put("removed", diff.count(DiffStatus.REMOVED))
                    put("same", diff.count(DiffStatus.SAME))
                    put("metaChanged", diff.count(DiffStatus.META_CHANGED))
                }, listOfNotNull(
                    store.snapshotImportEvent(aId, digestOf(a)),
                    store.snapshotImportEvent(bId, digestOf(b))
                ), bid)
            }
        }
        return diff
    }

    private fun outcomeName(outcome: AnalysisOutcome): String = when (outcome) {
        is AnalysisOutcome.Ok -> "ok"
        is AnalysisOutcome.BudgetExhausted -> "budget_exhausted"
        is AnalysisOutcome.SemanticUnsolvable -> "semantic_unsolvable"
    }

    private fun conclusionJson(outcome: AnalysisOutcome): JsonObject = when (outcome) {
        is AnalysisOutcome.Ok -> buildJsonObject {
            val r = outcome.result
            put("reachable", r.reachable.size)
            put("unreachable", r.unreachable.size)
            put("cycles", r.cycles.size)
            put("complete", r.complete)
            put("topRetained", buildJsonArray {
                r.retainedBytes.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
                    .take(5).forEach { (id, bytes) ->
                        add(buildJsonArray {
                            add(JsonPrimitive(id)); add(JsonPrimitive(bytes))
                        })
                    }
            })
        }
        is AnalysisOutcome.BudgetExhausted -> buildJsonObject {
            put("resource", outcome.resource); put("limit", outcome.limit); put("position", outcome.position)
        }
        is AnalysisOutcome.SemanticUnsolvable -> buildJsonObject {
            put("diagnostics", diagnosticsJson(outcome.diagnostics))
        }
    }

    private fun diagnosticsJson(diags: List<Diagnostic>): JsonArray = buildJsonArray {
        diags.forEach { d ->
            add(buildJsonObject {
                put("severity", d.severity.name)
                put("code", d.code)
                put("message", d.message)
                put("position", d.position)
            })
        }
    }
}
