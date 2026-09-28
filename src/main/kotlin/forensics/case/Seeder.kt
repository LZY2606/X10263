package forensics.case

import forensics.graph.ClassCorrespondence
import forensics.ledger.CaseService
import forensics.ledger.CounterexampleHarness
import forensics.model.ImportOutcome

/**
 * 完整叙事播种：导入 -> 参数修订 -> 第二快照 -> 派生 -> 反例档案 -> 冲突决定。
 * 所有阶段都是服务端可测试调用，不依赖浏览器。
 */
object Seeder {
    const val CASE_ID = "heap-shadow-demo"

    fun seed(service: CaseService, pack: CasePack = CasePack.fromClasspath()) {
        if (service.store.caseExists(CASE_ID)) return

        // 阶段 1：初始导入（基线完整保留）
        val c1 = service.createCase(CASE_ID, "堆影取证室：内存快照、弱引用边与保留路径候选",
            "baseline.json", pack.baseline)
        check(c1 is ImportOutcome.Ok) { "基线案例必须成功导入" }

        // 阶段 2：一次参数修订——弱引用显式保活（由 false -> true 的可重放历史先记录 false 基线版本）
        service.reviseParams(CASE_ID, weak = false, internal = true, leakMin = 1_024L,
            correspondence = emptyList(),
            note = "参数修订 v1：弱引用不保活（显式策略），内部边保活；阈值 1024 byte")
        service.compute(CASE_ID, note = "在 v1 参数下首次计算支配树/保留大小/root 路径")

        // 阶段 3：只影响局部依赖的源数据改动——第二快照（类改名、新增实例）
        val c2 = service.importAdditionalSnapshot(CASE_ID, "snapshot-B.json", pack.snapshotB)
        check(c2 is ImportOutcome.Ok)
        service.reviseParams(CASE_ID, weak = false, internal = true, leakMin = 512L,
            correspondence = loadCorrespondence(pack),
            note = "参数修订 v2：加入跨快照类对应 CacheEntry->CacheEntryV2，阈值降到 512 byte")
        val deriv = service.compute(CASE_ID, note = "两快照类群差异（含类改名）")

        // 反例档案：七个情形，采纳/未采纳都保留
        val harness = CounterexampleHarness()
        val variants = pack.allScenarios.associateWith { pack.variant(it) }
        val runs = harness.run(pack.baseline, variants)
        runs.forEach { r ->
            service.recordCounterexample(
                caseId = CASE_ID, scenario = r.scenario,
                inputFragment = r.inputFragment, judgmentStatus = r.judgmentStatus,
                affectedObjectIds = r.affectedObjectIds,
                adoptedResultId = r.adopted.takeIf { it.isNotEmpty() }?.let { "${r.scenario}:accepted" },
                rejectedResultIds = r.rejected.mapIndexed { i, _ -> "${r.scenario}:rejected#$i" },
                rationale = r.rationale,
                note = "单一直接来源改动：${r.changedSource}",
            )
        }

        // 一次互相冲突的人工决定：对同一泄漏候选先 ACCEPT 后 REJECT
        val targetCandidate = service.projectAt(CASE_ID, deriv.seq).analyses
            .flatMap { it.leakCandidates }.maxByOrNull { it.retainedBytes }
        checkNotNull(targetCandidate) { "基线应至少有一个泄漏候选" }
        service.decide(CASE_ID, targetCandidate.nodeId, "ACCEPT",
            "初步审阅：保留大小最大，疑似缓存泄漏", basedOnEventId = deriv.eventId, adopted = true,
            note = "人工决定 #1：接受泄漏假设")
        service.decide(CASE_ID, targetCandidate.nodeId, "REJECT",
            "复核发现该链路由运行时线程本地缓存持有，属预期生命周期", basedOnEventId = deriv.eventId, adopted = false,
            note = "人工决定 #2：与 #1 冲突，两个事件均保留；当前状态按最新投影，历史可回溯")
    }

    private fun loadCorrespondence(pack: CasePack): List<ClassCorrespondence> {
        val node = pack.manifest().path("classCorrespondence")
        return node.map { n ->
            val members = n.path("members").fields().asSequence()
                .associate { it.key to it.value.asText() }
            ClassCorrespondence(n.path("canonicalId").asText(), members)
        }
    }
}
