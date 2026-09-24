package heaplab

/**
 * 把离线案例包重放成完整事件链：
 *  导入 -> 校验 -> 双策略计算 -> 参数修订 -> 局部源改动 -> 冲突人工决定 -> 归档。
 */
object Seeder {

    fun seed(store: Store) {
        if (store.listSnapshots().isNotEmpty()) return

        // 1) 基线导入（含地址复用、不完整块等 WARNING；原始表示全部保留）
        val baseline = Cases.baseline()
        val baselineDiags = SnapshotIO.validate(baseline)
        store.importSnapshot(baseline, baselineDiags, "初始导入：原始对象 id/地址/类元数据/强弱内部边/root 分类/不完整块/解析器版本均保留")

        // 2) 不完整对象尝试：带位置诊断拒绝（FORMAT_CORRUPT）
        val broken = Cases.broken()
        val brokenDiags = SnapshotIO.validate(broken)
        store.recordRejectedImport(broken, brokenDiags, "snap-incomplete-attempt")

        // 3) 参数 v1：弱引用不保活
        val runWeakOff = store.recordRun(baseline.snapshotId, Cases.weakOff, Analysis.run(baseline, Cases.weakOff)).first
        store.recordRuleJudgment(
            "dominator-reachable-only",
            "支配树只在可达图上构建；弱引用是否保活由显式策略 weakRefsKeepAlive 决定，不保活时弱边指向的对象不进入可达图",
            baseline.snapshotId, runWeakOff.runId,
            listOf("obj-buffer", "obj-intern"),
        )
        store.recordRuleJudgment(
            "address-not-identity",
            "obj-dup-addr 与 obj-session-1 复用地址 0x1020；地址不是稳定身份，objectId 才是，两个对象都保留",
            baseline.snapshotId, runWeakOff.runId,
            listOf("obj-session-1", "obj-dup-addr"),
        )
        store.recordRuleJudgment(
            "equal-length-root-paths",
            "obj-shared 经 JNI_LOCAL->obj-session-1 与 JNI_GLOBAL->obj-session-2 两条等长路径可达，无法唯一决定，全部合法候选保留",
            baseline.snapshotId, runWeakOff.runId,
            listOf("obj-shared"),
        )

        // 4) 参数修订 v2：弱引用保活；生成新的冻结 run，不覆盖 r1
        val runWeakOn = store.recordRun(baseline.snapshotId, Cases.weakOn, Analysis.run(baseline, Cases.weakOn)).first
        store.recordParamsRevision(runWeakOff.runId, Cases.weakOn, "审阅者要求缓存弱引用在本次排查中视为保活，参数版本 1 -> 2；旧 run 冻结保留")
        store.recordRuleJudgment(
            "weak-policy-explicit",
            "弱引用保活是显式策略参数；未指定时返回 SEMANTIC_UNSOLVABLE，禁止隐式默认",
            baseline.snapshotId, runWeakOn.runId,
            listOf("obj-buffer"),
        )

        // 5) 只影响局部依赖的源数据改动：环回边
        val amended = Cases.cycleAmended()
        store.importSnapshot(amended, SnapshotIO.validate(amended), "源数据改动：仅新增边 e-cb-reg，期望影响限于环内支配关系")
        store.recordSourceAmended(baseline.snapshotId, amended.snapshotId, "edges += e-cb-reg (obj-cycle-b -> obj-registry)", listOf("obj-cycle-a", "obj-cycle-b"))
        val runAmended = store.recordRun(amended.snapshotId, Cases.weakOn, Analysis.run(amended, Cases.weakOn)).first

        // 6) 类改名快照（两快照类群差异用）
        val renamed = Cases.renamed()
        store.importSnapshot(renamed, SnapshotIO.validate(renamed), "第二快照：com.acme.Cache 改名为 com.acme.Cache2")
        store.recordRun(renamed.snapshotId, Cases.weakOn, Analysis.run(renamed, Cases.weakOn))

        // 7) 另外两种失败结果的可重放证据
        val budgetParams = AnalysisParams(paramsVersion = 2, weakRefsKeepAlive = true, maxVisitedNodes = 3)
        store.recordRun(baseline.snapshotId, budgetParams, Analysis.run(baseline, budgetParams))
        val unsolvableParams = AnalysisParams(paramsVersion = 2, weakRefsKeepAlive = null)
        store.recordRun(baseline.snapshotId, unsolvableParams, Analysis.run(baseline, unsolvableParams))

        // 8) 反例档案（采纳与未采纳结果都保留）
        store.saveCase(
            CaseRecord(
                caseId = "case-cycle-graph",
                title = "循环图：支配关系与保留大小不重复计数",
                ruleId = "dominator-reachable-only",
                baselineSnapshotId = baseline.snapshotId,
                mutatedSnapshotId = amended.snapshotId,
                directSourceChange = "新增强边 e-cb-reg（obj-cycle-b -> obj-registry）",
                expectedAffectedScope = listOf("obj-cycle-a", "obj-cycle-b"),
                acceptedRunId = runAmended.runId,
                rejectedRunId = runWeakOff.runId,
                note = "采纳加回边后的局部重算；拒绝沿用旧快照结论",
            )
        )
        store.recordDecision(runAmended.runId, accept = true, rationale = "加回边后环的支配父级按新图重算，影响仅限环内", caseId = "case-cycle-graph")
        store.recordDecision(runWeakOff.runId, accept = false, rationale = "旧快照不含回边，结论不适用于改动后的图", caseId = "case-cycle-graph")

        store.saveCase(
            CaseRecord(
                caseId = "case-weak-policy",
                title = "弱引用策略：4 KiB 缓冲是否保活",
                ruleId = "weak-policy-explicit",
                baselineSnapshotId = baseline.snapshotId,
                directSourceChange = "参数修订 weakRefsKeepAlive: false -> true（单一直接来源：策略参数）",
                expectedAffectedScope = listOf("obj-buffer"),
                acceptedRunId = runWeakOff.runId,
                rejectedRunId = runWeakOn.runId,
                note = "两个审阅者分别采纳互斥策略，case 进入 CONFLICT，两个 run 都作为合法候选保留",
            )
        )
        store.recordDecision(runWeakOff.runId, accept = true, rationale = "运行时默认弱引用不保活：obj-buffer 不在可达图上，Cache 保留大小不计入 4 KiB", caseId = "case-weak-policy")
        store.recordDecision(runWeakOn.runId, accept = true, rationale = "排查策略显式要求弱引用保活：obj-buffer 可达，Cache 保留大小包含 4 KiB", caseId = "case-weak-policy")

        store.saveCase(
            CaseRecord(
                caseId = "case-duplicate-address",
                title = "重复地址：地址不当稳定身份",
                ruleId = "address-not-identity",
                baselineSnapshotId = baseline.snapshotId,
                directSourceChange = "基线即包含复用地址 0x1020（obj-session-1 与 obj-dup-addr）",
                expectedAffectedScope = listOf("obj-session-1", "obj-dup-addr"),
                acceptedRunId = runWeakOff.runId,
                note = "导入 WARNING 保留两个对象，身份以 objectId 为准",
            )
        )
        store.recordDecision(runWeakOff.runId, accept = true, rationale = "地址重复不丢对象，objectId 稳定", caseId = "case-duplicate-address")

        store.saveCase(
            CaseRecord(
                caseId = "case-incomplete-object",
                title = "不完整对象：断裂引用与不可转换值",
                ruleId = "format-corrupt-positions",
                baselineSnapshotId = baseline.snapshotId,
                directSourceChange = "尝试导入 snap-incomplete-attempt：类引用 cls-missing 缺失、地址 0x????、块长度 ?",
                expectedAffectedScope = listOf("obj-broken", "blk-8"),
                rejectedImportDiagnostics = brokenDiags,
                note = "拒绝导入（FORMAT_CORRUPT），诊断带 JSON 路径；未采纳结果原样归档",
            )
        )

        store.saveCase(
            CaseRecord(
                caseId = "case-multi-root-paths",
                title = "多 root 等长路径：全部候选保留",
                ruleId = "equal-length-root-paths",
                baselineSnapshotId = baseline.snapshotId,
                directSourceChange = "基线即含 JNI_LOCAL/JNI_GLOBAL 双 root 到 obj-shared 的等长路径",
                expectedAffectedScope = listOf("obj-shared"),
                acceptedRunId = runWeakOff.runId,
            )
        )
        store.recordDecision(runWeakOff.runId, accept = true, rationale = "两条 2 跳路径都保留，不任意选一条", caseId = "case-multi-root-paths")

        store.saveCase(
            CaseRecord(
                caseId = "case-class-rename",
                title = "类改名与快照身份对应",
                ruleId = "snapshot-scoped-identity",
                baselineSnapshotId = baseline.snapshotId,
                mutatedSnapshotId = renamed.snapshotId,
                directSourceChange = "第二快照 cls-cache 重命名为 cls-cache-v2 / com.acme.Cache2",
                expectedAffectedScope = listOf("cls-cache", "cls-cache-v2"),
                note = "类群差异显示 Cache 消失 / Cache2 新增；跨快照不按地址对应对象",
            )
        )
    }
}
