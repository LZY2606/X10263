package forensics.heapsnapshot.cases

import forensics.heapsnapshot.engine.AnalysisParams
import forensics.heapsnapshot.storage.ImportOutcome
import forensics.heapsnapshot.storage.ProjectService
import java.io.File

/**
 * 离线案例包播种：不依赖云端。材料是专门的 HSP 领域文件而非通用表格。
 * 叙事顺序：
 *  1. 导入基线（原始层全保留，诊断含不完整对象/不可转换大小）
 *  2. v1 策略（weak=false）分析 —— 弱引用目标不可达，支配树只含可达对象
 *  3. 参数修订到 v2（weak=true）后重新分析 —— 同一候选出现冲突人工决定
 *  4. 局部源改动：只修正重复地址这一直接来源，重放影响范围检查
 *  5. 导入修订快照、计算类群差异（类改名经 CORRESPOND 对应）
 */
object CaseSeeder {
    const val PROJECT_NAME = "堆影取证室：支付进程泄漏复核（离线案例）"
    const val CASE_PACKAGE = "heap-shadow-forensics-001"

    fun seedIfAbsent(service: ProjectService, resourceDir: File? = null): String {
        val existing = service.repo.listProjects().firstOrNull { it.casePackage == CASE_PACKAGE }
        if (existing != null) return existing.id

        val project = service.createProject(PROJECT_NAME, CASE_PACKAGE)
        val projectId = project.id

        val baseline = readCase("baseline.hsp")
        val revised = readCase("revised.hsp")

        val baseSnapshotId = when (val r = service.importSnapshot(projectId, "baseline.hsp", baseline,
            actor = "runtime-team", snapshotIdOverride = "snap-S7701")) {
            is ImportOutcome.Imported -> r.snapshotId
            else -> error("基线快照必须可导入（允许 WARNING，但结构合法）")
        }

        // v1：弱引用不保活 —— O8 应不可达；O3 有多条等长 root 路径。
        service.computeAnalysis(projectId, baseSnapshotId, actor = "auto-seed",
            paramsOverride = AnalysisParams(version = 1, weakEdgesKeepAlive = false))

        // 参数修订：审阅者显式将弱引用视为保活（v2），并记录冲突决定。
        val v2Params = AnalysisParams(
            version = 2, weakEdgesKeepAlive = true, internalEdgesKeepAlive = true,
            note = "弱引用保活：用于验证弱引用策略变化只影响经弱边可达的对象",
        )
        service.reviseParams(projectId, v2Params,
            rationale = "审阅参数修订：weakEdgesKeepAlive=false -> true，显式决定弱引用保活。")
        val (v2Event, v2Result) = service.computeAnalysis(projectId, baseSnapshotId, actor = "reviewer-a")

        // 冲突人工决定：审阅者 A 接受 O8 候选；稍后审阅者 B 否定同一候选（互斥决定均保留）。
        val o8Candidate = v2Result.leakCandidates.firstOrNull { it.objectId == "O8" }
        if (o8Candidate != null) {
            service.adjudicate(projectId, o8Candidate.id, "ACCEPTED",
                rationaleCode = "REVIEWER_A_ACCEPT_WEAK_TARGET",
                rationaleText = "在 weak=true 策略下 O8 可达，A 认为弱观察者仍构成泄漏证据，接受候选。")
            service.adjudicate(projectId, o8Candidate.id, "REJECTED",
                rationaleCode = "REVIEWER_B_REJECT_WEAK_TARGET_CONFLICT",
                rationaleText = "B 依据规范认为弱引用不保活，否定 A 的接受；两个决定同档保留，时间旅行可见。")
        }

        // 局部源改动：只修正基线中 O2 的重复地址（直接来源单行），旧材料保留，影响应只限身份/地址视图。
        val baseLines = baseline.split("\n")
        val dupLineNo = baseLines.indexOfFirst { it.startsWith("OBJECT O2 ") } + 1
        service.patchSourceLine(
            projectId, baseSnapshotId, dupLineNo,
            "OBJECT O2 C2 0x0000000A00009000 128b  SITE site-queue",
            scopeKey = "duplicate-address-O2",
            rationaleText = "运行时团队确认 O2 实际地址为 0x...9000，基线与 O1 重复为转储错误",
        )

        // 导入修订快照并做两快照类群差异（含类改名对应）。
        val revisedSnapshotId = when (val r = service.importSnapshot(projectId, "revised.hsp", revised,
            actor = "runtime-team", snapshotIdOverride = "snap-S7702")) {
            is ImportOutcome.Imported -> r.snapshotId
            else -> error("修订快照必须可导入")
        }
        service.computeAnalysis(projectId, revisedSnapshotId, actor = "auto-seed", paramsOverride = v2Params)
        service.computeDiff(projectId, baseSnapshotId, revisedSnapshotId, analysisEventId = v2Event.eventId)

        return projectId
    }

    private fun readCase(name: String): String {
        val stream = javaClass.getResourceAsStream("/cases/$CASE_PACKAGE/$name")
            ?: error("案例包资源缺失：/cases/$CASE_PACKAGE/$name")
        return stream.bufferedReader(Charsets.UTF_8).readText()
    }
}
