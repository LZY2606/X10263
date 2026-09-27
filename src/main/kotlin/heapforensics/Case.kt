package heapforensics

import heapforensics.analysis.AnalysisParams
import heapforensics.analysis.WeakPolicy
import heapforensics.store.Event
import heapforensics.store.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** 案例包来源：优先文件系统目录，其次 classpath 资源（仓库自带离线案例包）。 */
object CaseSource {
    fun read(caseRoot: String, name: String): String {
        val f = File(caseRoot, name)
        if (f.isFile) return f.readText()
        val resource = "$caseRoot/$name"
        val stream = CaseSource::class.java.classLoader.getResourceAsStream(resource)
            ?: throw IllegalArgumentException("案例文件不存在: $resource")
        return stream.bufferedReader().readText()
    }

    fun exists(caseRoot: String): Boolean =
        File(caseRoot, "case.json").isFile ||
            CaseSource::class.java.classLoader.getResource("$caseRoot/case.json") != null
}

/**
 * 案例回放：按 case.json 的步骤实际执行导入、计算、参数修订、源数据修订、
 * 冲突决定与类群差异，每一步都产生机器可读事件。
 */
class CaseRunner(private val service: AppService) {
    private val store: Store get() = service.store

    fun replay(caseRoot: String): List<Event> {
        val caseJson = Json.parseToJsonElement(CaseSource.read(caseRoot, "case.json")).jsonObject
        val caseId = caseJson.getValue("caseId").jsonPrimitive.content
        val events = mutableListOf<Event>()
        val lastEvents = mutableListOf<String>()

        for (step in caseJson.getValue("steps").jsonArray) {
            val op = step.jsonObject
            when (op.getValue("op").jsonPrimitive.content) {
                "import" -> {
                    when (val r = service.importSnapshot(CaseSource.read(caseRoot, op.getValue("file").jsonPrimitive.content), op.getValue("file").jsonPrimitive.content)) {
                        is ImportSnapOutcome.Ok -> r.eventId?.let { lastEvents += it }
                        is ImportSnapOutcome.Corrupt -> r.eventId?.let { lastEvents += it }
                    }
                "analyze" -> {
                    val snapshotId = op.getValue("snapshot").jsonPrimitive.content
                    val policy = WeakPolicy.valueOf(op.getValue("weakPolicy").jsonPrimitive.content)
                    val version = op.getValue("paramVersion").jsonPrimitive.content
                    val r = service.runAnalysis(snapshotId, null, AnalysisParams(policy, version), lastEvents.takeLast(1))
                    if (r is AnalysisRunOutcome.Done) lastEvents += r.eventId
                }
                "reviseParams" -> {
                    val snapshotId = op.getValue("snapshot").jsonPrimitive.content
                    val policy = WeakPolicy.valueOf(op.getValue("weakPolicy").jsonPrimitive.content)
                    val reason = op.getValue("reason").jsonPrimitive.content
                    val (ev, run) = service.reviseParams(snapshotId, policy, reason)!!
                    lastEvents += ev.id
                    if (run is AnalysisRunOutcome.Done) lastEvents += run.eventId
                }
                "amend" -> {
                    val file = op.getValue("file").jsonPrimitive.content
                    val r = service.importSnapshot(CaseSource.read(caseRoot, file), file, lastEvents.takeLast(1))
                    if (r is ImportSnapOutcome.Ok) r.eventId?.let { lastEvents += it }
                }
                "decide" -> {
                    val row = service.decide(
                        op.getValue("target").jsonPrimitive.content,
                        op.getValue("verdict").jsonPrimitive.content,
                        op.getValue("author").jsonPrimitive.content,
                        op.getValue("rationale").jsonPrimitive.content
                    )
                    lastEvents += row.eventId
                }
                "diff" -> {
                    service.computeDiff(
                        op.getValue("a").jsonPrimitive.content,
                        op.getValue("b").jsonPrimitive.content
                    )
                    lastEvents += store.listEvents().last().id
                }
                else -> throw IllegalArgumentException("未知案例步骤: $op")
            }
        }

        val summary = store.inBatch("case-replayed") { bid ->
            store.appendEvent("case.replayed", buildJsonObject {
                put("caseId", caseId)
                put("title", caseJson.getValue("title").jsonPrimitive.content)
                put("narrative", caseJson["narrative"]?.jsonPrimitive?.content ?: "")
                put("steps", caseJson.getValue("steps").jsonArray.size)
                put("events", JsonArray(store.listEvents().map { JsonPrimitive(it.id) }))
            }, lastEvents, bid)
        }
        events += summary
        return store.listEvents()
    }
}
