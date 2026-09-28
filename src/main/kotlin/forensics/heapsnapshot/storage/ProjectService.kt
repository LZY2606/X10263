package forensics.heapsnapshot.storage

import forensics.heapsnapshot.domain.HspParser
import forensics.heapsnapshot.engine.AnalysisParams
import forensics.heapsnapshot.engine.AnalysisResult
import forensics.heapsnapshot.engine.Analyzer
import forensics.heapsnapshot.engine.AnalysisBudgetException
import forensics.heapsnapshot.engine.ClassDiffEngine
import forensics.heapsnapshot.engine.ClassGroupDiff
import java.time.Instant
import java.util.UUID

/** 导入结果：三种失败与成功严格区分，不伪装。 */
sealed class ImportOutcome {
    data class Imported(val snapshotId: String, val event: StoredEvent, val warnings: List<String>) : ImportOutcome()
    data class Corrupt(val diagnostics: List<String>) : ImportOutcome()
    data class SemanticAmbiguity(val snapshotId: String, val event: StoredEvent, val diagnostics: List<String>) : ImportOutcome()
    data class BudgetExhausted(val limit: Int, val observed: Int) : ImportOutcome()
}

class ProjectService(internal val repo: Repository, private val db: Database) {
    private val json get() = db.json

    fun createProject(name: String, casePackage: String? = null): ProjectRow =
        repo.createProject("proj-" + UUID.randomUUID().toString().take(8), name, casePackage)

    fun importSnapshot(
        projectId: String,
        rawName: String,
        raw: String,
        actor: String = "reviewer",
        branch: String? = null,
        snapshotIdOverride: String? = null,
    ): ImportOutcome {
        val events = repo.events(projectId)
        val tipId = repo.tipEventId(projectId)
        val state = Reducer.projectAt(events, tipId)
        val seq = repo.nextSeq(projectId)
        val useBranch = branch ?: state.branch

        when (val parsed = HspParser().parse(raw)) {
            is HspParser.Result.BudgetExhausted ->
                return ImportOutcome.BudgetExhausted(parsed.limit, parsed.observed)
            is HspParser.Result.Corrupt ->
                return ImportOutcome.Corrupt(parsed.diagnostics.map { "${it.location}: ${it.message}" })
            is HspParser.Result.SemanticAmbiguity -> {
                val snapshotId = snapshotIdOverride ?: "snap-${parsed.snapshot.meta.identity}-${UUID.randomUUID().toString().take(6)}"
                val event = StoredEvent(
                    eventId = "evt-${UUID.randomUUID().toString().take(10)}",
                    seq = seq, parentEventId = tipId, branch = useBranch,
                    at = Instant.now().toString(), actor = actor, type = "SnapshotImported",
                    payload = EventPayload.SnapshotImported(
                        snapshotId = snapshotId, rawFileName = rawName,
                        parseOutcome = "SEMANTIC_AMBIGUITY",
                        diagnostics = parsed.diagnostics,
                        rationale = Rationale(
                            "IMPORT_WITH_SEMANTIC_DIAGNOSTICS",
                            "材料完整保留；悬空引用等语义问题附带位置诊断，不使用空值或默认值伪装成功。",
                            listOf("${rawName}:1..${parsed.snapshot.rawLines.size}"),
                        ),
                    ),
                )
                repo.appendEventWithSnapshots(
                    projectId, event,
                    listOf(StoredSnapshot(snapshotId, parsed.snapshot.meta.identity, raw, seq)),
                )
                return ImportOutcome.SemanticAmbiguity(snapshotId, event, parsed.diagnostics.map { "${it.location}: ${it.message}" })
            }
            is HspParser.Result.Ok -> {
                val snapshotId = snapshotIdOverride ?: "snap-${parsed.snapshot.meta.identity}-${UUID.randomUUID().toString().take(6)}"
                val event = StoredEvent(
                    eventId = "evt-${UUID.randomUUID().toString().take(10)}",
                    seq = seq, parentEventId = tipId, branch = useBranch,
                    at = Instant.now().toString(), actor = actor, type = "SnapshotImported",
                    payload = EventPayload.SnapshotImported(
                        snapshotId = snapshotId, rawFileName = rawName,
                        parseOutcome = "OK",
                        diagnostics = parsed.snapshot.diagnostics,
                        rationale = Rationale(
                            "IMPORT_BASELINE_PRESERVED",
                            "原始对象 id、地址、类元数据、强/弱/内部边、root 分类、不完整块与解析器版本全部原样保留。",
                            listOf(rawName),
                        ),
                    ),
                )
                repo.appendEventWithSnapshots(
                    projectId, event,
                    listOf(StoredSnapshot(snapshotId, parsed.snapshot.meta.identity, raw, seq)),
                )
                return ImportOutcome.Imported(snapshotId, event, parsed.snapshot.diagnostics.map { it.message })
            }
        }
    }

    fun reviseParams(projectId: String, params: AnalysisParams, rationale: String, actor: String = "reviewer"): StoredEvent {
        val tipId = repo.tipEventId(projectId)
        val state = Reducer.projectAt(repo.events(projectId), tipId)
        val event = StoredEvent(
            eventId = "evt-${UUID.randomUUID().toString().take(10)}",
            seq = repo.nextSeq(projectId), parentEventId = tipId, branch = state.branch,
            at = Instant.now().toString(), actor = actor, type = "ParamsRevised",
            payload = EventPayload.ParamsRevised(
                newVersion = params.version, params = params,
                rationale = Rationale("POLICY_REVISION", rationale),
            ),
        )
        repo.appendEventWithSnapshots(projectId, event, emptyList())
        return event
    }

    /** 在指定历史时点（tipAtEventId）上分析；null 表示当前 tip。 */
    fun computeAnalysis(
        projectId: String,
        snapshotId: String,
        actor: String = "reviewer",
        tipAtEventId: String? = null,
        paramsOverride: AnalysisParams? = null,
    ): Pair<StoredEvent, AnalysisResult> {
        val allEvents = repo.events(projectId)
        val anchor = tipAtEventId ?: repo.tipEventId(projectId)
        val state = Reducer.projectAt(allEvents, anchor)
        val params = paramsOverride ?: state.currentParams

        val stored = repo.rawSnapshot(snapshotId)
            ?: throw IllegalArgumentException("快照不存在：$snapshotId")
        val parsed = when (val r = HspParser().parse(stored.raw)) {
            is HspParser.Result.Ok -> r.snapshot
            is HspParser.Result.SemanticAmbiguity -> r.snapshot
            else -> throw IllegalStateException("快照材料处于不可分析状态（CORRUPT/BUDGET）")
        }

        val parentDeps = listOfNotNull(
            anchor,
            state.latestAnalysisEventIdBySnapshot[snapshotId],
        ).distinct()

        val result = try {
            Analyzer.analyze(parsed, params, parentDeps)
        } catch (e: AnalysisBudgetException) {
            throw BudgetExhaustedApi(e.limit, e.observed)
        }

        val analysisEventId = "evt-${UUID.randomUUID().toString().take(10)}"
        val event = StoredEvent(
            eventId = analysisEventId,
            seq = repo.nextSeq(projectId), parentEventId = anchor, branch = state.branch,
            at = Instant.now().toString(), actor = actor, type = "AnalysisComputed",
            payload = EventPayload.AnalysisComputed(
                analysisEventId = analysisEventId,
                snapshotId = snapshotId,
                paramsVersion = params.version,
                parentEventIds = parentDeps,
                inputDigest = json.encodeToJsonElement(
                    kotlinx.serialization.serializer<forensics.heapsnapshot.engine.InputDigest>(),
                    result.inputDigest,
                ),
                rationale = Rationale(
                    "DOMINATORS_ON_REACHABLE_GRAPH",
                    "支配树仅在 root 可达图上构建；弱引用保活由 weakEdgesKeepAlive=${params.weakEdgesKeepAlive} 显式决定；" +
                        "等长 root 路径全部保留；地址不作为身份。",
                    listOf("snapshot=$snapshotId", "params=v${params.version}") + parentDeps.map { "parent=$it" },
                ),
            ),
        )
        repo.appendEventWithSnapshots(projectId, event, emptyList(), analysis = analysisEventId to result)
        return event to result
    }

    fun adjudicate(
        projectId: String,
        candidateId: String,
        decision: String,
        rationaleCode: String,
        rationaleText: String,
        params: AnalysisParams? = null,
    ): StoredEvent {
        require(decision in setOf("ACCEPTED", "REJECTED", "PARAM_ADJUST"))
        val tipId = repo.tipEventId(projectId)
        val state = Reducer.projectAt(repo.events(projectId), tipId)
        val events = mutableListOf<StoredEvent>()

        var parent = tipId
        if (decision == "PARAM_ADJUST" && params != null) {
            val revised = StoredEvent(
                eventId = "evt-${UUID.randomUUID().toString().take(10)}",
                seq = repo.nextSeq(projectId), parentEventId = parent, branch = state.branch,
                at = Instant.now().toString(), actor = state.branch, type = "ParamsRevised",
                payload = EventPayload.ParamsRevised(
                    newVersion = params.version, params = params,
                    rationale = Rationale(rationaleCode, rationaleText),
                ),
            )
            repo.appendEventWithSnapshots(projectId, revised, emptyList())
            events.add(revised)
            parent = revised.eventId
        }
        val adj = StoredEvent(
            eventId = "evt-${UUID.randomUUID().toString().take(10)}",
            seq = repo.nextSeq(projectId), parentEventId = parent, branch = state.branch,
            at = Instant.now().toString(), actor = "reviewer", type = "CandidateAdjudicated",
            payload = EventPayload.CandidateAdjudicated(
                candidateId = candidateId,
                decision = decision,
                overridesParamsVersion = params?.version,
                rationale = Rationale(rationaleCode, rationaleText, listOf("candidate=$candidateId")),
            ),
        )
        repo.appendEventWithSnapshots(projectId, adj, emptyList())
        events.add(adj)
        return adj
    }

    fun patchSourceLine(projectId: String, snapshotId: String, lineNo: Int, newRawLine: String, scopeKey: String, rationaleText: String): StoredEvent {
        val stored = repo.rawSnapshot(snapshotId) ?: throw IllegalArgumentException("快照不存在：$snapshotId")
        val lines = stored.raw.split("\n").toMutableList()
        require(lineNo in 1..lines.size) { "行号超出范围：$lineNo" }
        val old = lines[lineNo - 1]
        lines[lineNo - 1] = newRawLine
        val newRaw = lines.joinToString("\n")

        val tipId = repo.tipEventId(projectId)
        val state = Reducer.projectAt(repo.events(projectId), tipId)
        val event = StoredEvent(
            eventId = "evt-${UUID.randomUUID().toString().take(10)}",
            seq = repo.nextSeq(projectId), parentEventId = tipId, branch = state.branch,
            at = Instant.now().toString(), actor = "runtime-team", type = "SourcePatched",
            payload = EventPayload.SourcePatched(
                snapshotId = snapshotId,
                changedLine = lineNo,
                changedRawLine = "$old => $newRawLine",
                scopeKey = scopeKey,
                rationale = Rationale(
                    "LOCALIZED_SOURCE_PATCH",
                    "只改动一个直接来源行：$rationaleText；影响应仅限 $scopeKey 依赖范围。",
                    listOf("snapshot=$snapshotId", "line=$lineNo"),
                ),
            ),
        )
        // 用新的不可变快照材料（新 snapshot id）表达“源数据改动”，旧材料保留可回溯。
        val newSnapshotId = "${snapshotId}@L$lineNo"
        repo.appendEventWithSnapshots(
            projectId, event,
            listOf(StoredSnapshot(newSnapshotId, stored.identity, newRaw, event.seq)),
        )
        return event
    }

    private fun rationateSafe(t: String) = t

    fun computeDiff(projectId: String, beforeId: String, afterId: String, analysisEventId: String? = null): ClassGroupDiff {
        val beforeRaw = repo.rawSnapshot(beforeId) ?: error("before snapshot missing")
        val afterRaw = repo.rawSnapshot(afterId) ?: error("after snapshot missing")
        val before = (HspParser().parse(beforeRaw.raw) as HspParser.Result.Ok).snapshot
        val after = when (val r = HspParser().parse(afterRaw.raw)) {
            is HspParser.Result.Ok -> r.snapshot
            is HspParser.Result.SemanticAmbiguity -> r.snapshot
            else -> error("after snapshot unparsable")
        }
        val analysisResult = analysisEventId?.let { repo.analysisResult(it) }
        val diff = ClassDiffEngine.diff(before, after, analysisResult)

        val tipId = repo.tipEventId(projectId)
        val state = Reducer.projectAt(repo.events(projectId), tipId)
        val event = StoredEvent(
            eventId = "evt-${UUID.randomUUID().toString().take(10)}",
            seq = repo.nextSeq(projectId), parentEventId = tipId, branch = state.branch,
            at = Instant.now().toString(), actor = "reviewer", type = "DiffComputed",
            payload = EventPayload.DiffComputed(
                beforeSnapshotId = beforeId, afterSnapshotId = afterId,
                analysisEventId = analysisEventId,
                rationale = Rationale(
                    "CLASS_IDENTITY_VIA_KEY_OR_DIRECTIVE",
                    "类群身份按显式 CORRESPOND 指令优先，其次稳定 class key；名称变化（改名）不改变身份。",
                    listOf("before=$beforeId", "after=$afterId"),
                ),
            ),
        )
        val diffId = "diff-${UUID.randomUUID().toString().take(8)}"
        repo.appendEventWithSnapshots(projectId, event, emptyList(), diff = Triple(diffId, afterId, diff))
        return diff
    }

    // ---- 导出 / 导入：身份、顺序、来源关系保持 ----
    fun exportProject(projectId: String): ExportEnvelope {
        val project = repo.listProjects().first { it.id == projectId }
        val events = repo.events(projectId)
        val snapshots = repo.listSnapshots(projectId)
        val analysis = repo.allAnalysis(projectId).map { (id, r) ->
            AnalysisBlobEntry(id, r.snapshotIdentity, r.paramsVersion, r)
        }
        return ExportEnvelope(project = project, events = events, snapshots = snapshots, analysisBlobs = analysis, diffBlobs = emptyList())
    }

    fun importProject(envelope: ExportEnvelope): ProjectRow {
        require(envelope.format == "heap-shadow-lab-export/v1") { "不支持的导出格式：${envelope.format}" }
        require(!repo.projectExists(envelope.project.id)) { "项目已存在：${envelope.project.id}" }
        repo.createProject(envelope.project.id, envelope.project.name, envelope.project.casePackage)

        // 严格按原始 seq 顺序重放，保持身份与 parentEventId 关系不变。
        val ordered = envelope.events.sortedBy { it.seq }
        ordered.forEach { e ->
            val snaps = envelope.snapshots.filter { it.importSeq == e.seq }
            val analysis = envelope.analysisBlobs.firstOrNull { it.eventId == e.eventId }
                ?.let { it.eventId to it.result }
            repo.appendEventWithSnapshots(
                envelope.project.id, e, snaps,
                analysis = analysis,
            )
        }
        envelope.project.tipEventId?.let { repo.setTip(envelope.project.id, it) }
        return envelope.project
    }

    /** 在当前 tip 状态上重放全部事件并返回投影。 */
    fun currentState(projectId: String): ProjectState =
        Reducer.projectAt(repo.events(projectId), repo.tipEventId(projectId))

    fun stateAt(projectId: String, eventId: String?): ProjectState =
        Reducer.projectAt(repo.events(projectId), eventId)
}

class BudgetExhaustedApi(val limit: Int, val observed: Int) :
    RuntimeException("资源预算耗尽：limit=$limit observed=$observed")
