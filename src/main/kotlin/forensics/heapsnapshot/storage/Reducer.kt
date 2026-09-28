package forensics.heapsnapshot.storage

import forensics.heapsnapshot.engine.AnalysisParams

/**
 * 纯函数事件折叠：给定事件序列（含分支），投影出任意时点的状态。
 * 回到任意旧事件时，恢复的是当时的依赖版本与可见结论，
 * 不会拿最新计算结果覆盖历史。
 */
object Reducer {
    fun initial(): ProjectState = ProjectState(
        seq = 0,
        snapshotIds = emptyList(),
        currentParams = AnalysisParams(),
        latestAnalysisEventIdBySnapshot = emptyMap(),
        decisions = emptyMap(),
        branch = "main",
    )

    fun apply(state: ProjectState, event: StoredEvent): ProjectState {
        val nextSnapshots = state.snapshotIds.toMutableList()
        val nextAnalysis = state.latestAnalysisEventIdBySnapshot.toMutableMap()
        val nextDecisions = state.decisions.toMutableMap()
        var params = state.currentParams

        when (val p = event.payload) {
            is EventPayload.SnapshotImported -> {
                if (p.snapshotId !in nextSnapshots) nextSnapshots.add(p.snapshotId)
            }
            is EventPayload.ParamsRevised -> params = p.params
            is EventPayload.AnalysisComputed -> nextAnalysis[p.snapshotId] = p.analysisEventId
            is EventPayload.CandidateAdjudicated -> {
                nextDecisions[p.candidateId] = CandidateDecisionRecord(
                    candidateId = p.candidateId,
                    decision = p.decision,
                    eventSeq = event.seq,
                    paramsVersion = p.overridesParamsVersion,
                )
            }
            is EventPayload.SourcePatched -> Unit
            is EventPayload.DiffComputed -> Unit
        }
        return state.copy(
            seq = event.seq,
            snapshotIds = nextSnapshots,
            currentParams = params,
            latestAnalysisEventIdBySnapshot = nextAnalysis,
            decisions = nextDecisions,
            branch = event.branch,
        )
    }

    /** 沿 parentEventId 指针回溯一条分支的祖先事件（含自身），按 seq 升序。 */
    fun lineage(events: List<StoredEvent>, tipEventId: String?): List<StoredEvent> {
        if (tipEventId == null) return emptyList()
        val byId = events.associateBy { it.eventId }
        val chain = mutableListOf<StoredEvent>()
        var cur: String? = tipEventId
        val guard = HashSet<String>()
        while (cur != null && guard.add(cur)) {
            val e = byId[cur] ?: break
            chain.add(e)
            cur = e.parentEventId
        }
        return chain.asReversed()
    }

    fun projectAt(events: List<StoredEvent>, tipEventId: String?): ProjectState =
        lineage(events, tipEventId).fold(initial()) { acc, e -> apply(acc, e) }
}
