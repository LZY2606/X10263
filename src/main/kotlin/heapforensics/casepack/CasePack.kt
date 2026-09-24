package heapforensics.casepack

import heapforensics.core.AnalysisParams
import heapforensics.service.ReviewService
import heapforensics.service.ServiceResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 离线案例包：“堆影取证室：内存快照、弱引用边与保留路径候选”。
 * 播种即把完整叙事（导入→核对→边界组合→参数修订→局部源改动→冲突决定）重放为事件流。
 */
object CasePack {

    @Serializable
    data class Step(
        val op: String,
        val file: String? = null,
        val sourceName: String? = null,
        val snapshot: String? = null,
        val paramsVersion: Int? = null,
        val weakKeepAlive: Boolean? = null,
        val internalKeepAlive: Boolean? = null,
        val reason: String? = null,
        val subject: String? = null,
        val verdict: String? = null,
        val rationale: String? = null,
        val reviewer: String? = null,
        val parent: String? = null,
        val patchName: String? = null,
        val description: String? = null,
        val a: String? = null,
        val b: String? = null,
    )

    @Serializable
    data class Pack(val title: String, val version: String, val steps: List<Step>)

    private val json = Json { ignoreUnknownKeys = true }

    fun loadManifest(): Pack =
        json.decodeFromString(Pack.serializer(), resource("casepack.json"))

    fun resource(name: String): String {
        val text = javaClass.getResourceAsStream("/casepack/$name")?.bufferedReader()?.use { it.readText() }
        requireNotNull(text) { "案例包资源缺失: /casepack/$name" }
        return text
    }

    fun expectationsJson(): String = resource("expectations.json")

    data class SeedReport(val alreadySeeded: Boolean, val executed: Int, val notes: List<String>)

    /** 幂等播种：库中已有快照时不重复导入。 */
    fun seed(service: ReviewService): SeedReport {
        if (service.db.listSnapshots().isNotEmpty()) {
            return SeedReport(true, 0, listOf("案例包已存在，跳过播种"))
        }
        val manifest = loadManifest()
        val notes = mutableListOf<String>()
        var executed = 0
        for (step in manifest.steps) {
            when (step.op) {
                "import" -> {
                    val text = resource(step.file!!)
                    val r = service.importSnapshot(step.sourceName ?: step.file, text)
                    check(r is ServiceResult.Ok) { "案例包导入失败 ${step.file}: $r" }
                    notes += "导入 ${step.file}"
                }
                "analyze" -> {
                    when (val r = service.analyze(step.snapshot!!, step.paramsVersion)) {
                        is ServiceResult.Ok ->
                            notes += "分析 ${step.snapshot} @v${step.paramsVersion} => ${r.value.status}"
                        else -> error("案例包分析失败 ${step.snapshot}: $r")
                    }
                }
                "revise" -> {
                    val base = service.currentParams()
                    val next = AnalysisParams(
                        version = base.version + 1,
                        weakKeepAlive = step.weakKeepAlive ?: base.weakKeepAlive,
                        internalKeepAlive = step.internalKeepAlive ?: base.internalKeepAlive,
                        maxVisits = base.maxVisits, maxPathsPerObject = base.maxPathsPerObject,
                        maxPathDepth = base.maxPathDepth, leakThresholdBytes = base.leakThresholdBytes,
                    )
                    val e = service.reviseParams(next, step.reason ?: "参数修订")
                    notes += "参数修订到 ${e.eventId}"
                }
                "decision" -> {
                    service.recordDecision(step.subject!!, step.verdict!!, step.rationale ?: "",
                        step.reviewer ?: "reviewer", step.paramsVersion)
                    notes += "决定 ${step.verdict} ${step.subject}"
                }
                "patch" -> {
                    val text = resource(step.file!!)
                    when (val r = service.applyPatch(step.parent!!, step.patchName!!, text,
                        step.description ?: "")) {
                        is ServiceResult.Ok -> notes += "补丁 ${step.patchName} => ${r.value.snapshotId}"
                        else -> error("案例包补丁失败 ${step.patchName}: $r")
                    }
                }
                "diff" -> {
                    when (val r = service.diff(step.a!!, step.b!!)) {
                        is ServiceResult.Ok -> notes += "类群差异 ${step.a} vs ${step.b}"
                        else -> error("案例包差异计算失败: $r")
                    }
                }
                else -> error("未知案例包步骤: ${step.op}")
            }
            executed++
        }
        return SeedReport(false, executed, notes)
    }
}
