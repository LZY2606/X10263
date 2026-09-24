package heaproom

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

class HeapApp(val storage: Storage) {

    private fun at(call: ApplicationCall): Int? = call.parameters["at"]?.toIntOrNull()
        ?: call.request.queryParameters["at"]?.toIntOrNull()

    private fun module(app: Application) {
        app.routing {
            get("/") {
                val st = ReviewService.state(storage, null)
                call.respondText(Views.home(storage.listSnapshots(), st), ContentType.Text.Html)
            }

            get("/snapshot/{id}/{view}") {
                val id = call.parameters["id"]!!
                val view = call.parameters["view"]!!
                val atSeq = at(call)
                val snap = storage.listSnapshots().firstOrNull { it.doc.snapshotId == id }
                if (snap == null) {
                    call.respondText(Views.errorPage("未找到", "NOT_FOUND", "快照身份 $id 不存在"),
                        ContentType.Text.Html, HttpStatusCode.NotFound)
                    return@get
                }
                if (view == "diagnostics") {
                    call.respondText(
                        Views.diagnosticsPage(snap, storage.diagnostics(id), null),
                        ContentType.Text.Html)
                    return@get
                }
                val st = ReviewService.state(storage, atSeq)
                val (outcome, _) = ReviewService.compute(storage, id, atSeq)
                when (outcome) {
                    is ComputeOutcome.Ok -> call.respondText(
                        when (view) {
                            "graph" -> Views.graphPage(snap, outcome.value, st)
                            "dominators" -> Views.dominatorsPage(snap, outcome.value, st)
                            "retained" -> Views.retainedPage(snap, outcome.value, st)
                            "paths" -> Views.pathsPage(snap, outcome.value, st)
                            else -> return@get call.respondText("unknown view $view", HttpStatusCode.BadRequest)
                        }, ContentType.Text.Html)
                    is ComputeOutcome.BudgetExhausted -> call.respondText(
                        Views.errorPage("资源预算耗尽", "BUDGET_EXHAUSTED",
                            "scope=${outcome.scope}，limit=${outcome.limit}，consumed=${outcome.consumed}",
                            "analysis:$id"), ContentType.Text.Html, HttpStatusCode.UnprocessableEntity)
                    is ComputeOutcome.SemanticUnsolvable -> call.respondText(
                        Views.errorPage("语义无解", "SEMANTIC_UNSOLVABLE",
                            outcome.reason + if (outcome.candidates.isNotEmpty())
                                "\n全部合法候选:\n" + outcome.candidates.joinToString("\n") else "",
                            "analysis:$id"), ContentType.Text.Html, HttpStatusCode.Conflict)
                }
            }

            get("/diff/{a}/{b}") {
                val aId = call.parameters["a"]!!; val bId = call.parameters["b"]!!
                val snaps = storage.listSnapshots()
                val a = snaps.firstOrNull { it.doc.snapshotId == aId }
                val b = snaps.firstOrNull { it.doc.snapshotId == bId }
                if (a == null || b == null) {
                    call.respondText(Views.errorPage("未找到", "NOT_FOUND", "快照身份缺失"),
                        ContentType.Text.Html, HttpStatusCode.NotFound); return@get
                }
                val ra = (ReviewService.compute(storage, aId, at(call)).first as? ComputeOutcome.Ok)?.value
                val rb = (ReviewService.compute(storage, bId, at(call)).first as? ComputeOutcome.Ok)?.value
                if (ra == null || rb == null) {
                    call.respondText(Views.errorPage("语义无解 / 预算耗尽", "CANNOT_DIFF",
                        "其中至少一个快照当前参数下无法完成计算，请先看各自页面的失败类型"),
                        ContentType.Text.Html, HttpStatusCode.Conflict); return@get
                }
                call.respondText(Views.diffPage(a, b, Engine.classDiff(a.doc, b.doc, ra.retainedBytes, rb.retainedBytes)),
                    ContentType.Text.Html)
            }

            get("/events") {
                val atSeq = call.request.queryParameters["at"]?.toIntOrNull()
                call.respondText(Views.eventsPage(storage.events(), ReviewService.state(storage, atSeq)),
                    ContentType.Text.Html)
            }

            get("/export") {
                val text = ExportImport.export(storage)
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"heaproom-export.json\"")
                call.respondText(text, ContentType.Application.Json)
            }

            post("/reset") {
                storage.closeAndDelete()
                storage.init()
                CasePack.ensureLoaded(storage, File("casepack"))
                call.respondRedirect("/")
            }

            post("/param") {
                val form = call.receiveParameters()
                val policy = WeakPolicy.valueOf(form["weakPolicy"]!!)
                val budget = form["nodeBudget"]?.toIntOrNull() ?: 10000
                val st0 = ReviewService.state(storage, null)
                val n = st0.param?.version?.removePrefix("param-v")?.toIntOrNull() ?: 0
                val version = "param-v${n + 1}"
                storage.tx { c ->
                    val seq = storage.nextEventSeq(c)
                    storage.putParamSet(c, ParamRow(version, policy, budget, seq))
                    storage.appendEvent(c, "PARAM_REVISION",
                        kotlinx.serialization.json.JsonObject(mapOf(
                            "version" to kotlinx.serialization.json.JsonPrimitive(version),
                            "weakPolicy" to kotlinx.serialization.json.JsonPrimitive(policy.name),
                            "nodeBudget" to kotlinx.serialization.json.JsonPrimitive(budget)
                        )).toString(), st0.param?.let { listOf(it.eventSeq) } ?: emptyList())
                }
                call.respondRedirect("/events")
            }

            post("/decision") {
                val form = call.receiveParameters()
                val snapshotId = form["snapshotId"]!!
                val objectId = form["objectId"]!!
                val action = form["action"]!!
                val rationale = form["rationale"] ?: ""
                val candidate = "leak-cand:$snapshotId:$objectId"
                storage.tx { c ->
                    val seq = storage.nextEventSeq(c)
                    val decisionId = "dec-${action.lowercase()}-${objectId.removePrefix("obj-")}-$seq"
                    storage.putDecision(c, DecisionRow(
                        decisionId, candidate, snapshotId, action, rationale, seq, null))
                    storage.appendEvent(c, "DECISION",
                        kotlinx.serialization.json.JsonObject(mapOf(
                            "decisionId" to kotlinx.serialization.json.JsonPrimitive(decisionId),
                            "candidateId" to kotlinx.serialization.json.JsonPrimitive(candidate),
                            "action" to kotlinx.serialization.json.JsonPrimitive(action),
                            "rationale" to kotlinx.serialization.json.JsonPrimitive(rationale)
                        )).toString(), emptyList())
                }
                call.respondRedirect("/snapshot/$snapshotId/retained")
            }

            post("/decision/{id}/retract") {
                val decisionId = call.parameters["id"]!!
                storage.tx { c ->
                    val seq = storage.nextEventSeq(c)
                    storage.retractDecision(c, decisionId, seq)
                    storage.appendEvent(c, "DECISION_RETRACTED",
                        kotlinx.serialization.json.JsonObject(mapOf(
                            "decisionId" to kotlinx.serialization.json.JsonPrimitive(decisionId)
                        )).toString(), emptyList())
                }
                call.respondRedirect("/events")
            }

            post("/import") {
                var text = ""
                var sourceLabel = "manual-upload"
                run {
                    val mp = call.receiveMultipart()
                    while (true) {
                        val part = mp.readPart() ?: break
                        try {
                            when {
                                part is PartData.FileItem && part.name == "snapshot" ->
                                    text = part.streamProvider().readBytes().toString(Charsets.UTF_8)
                                part is PartData.FormItem && part.name == "sourceLabel" ->
                                    sourceLabel = part.value
                            }
                        } finally {
                            if (part is PartData.FileItem) part.dispose()
                        }
                    }
                }
                val parsed: SnapshotDoc = try {
                    Json { ignoreUnknownKeys = true }.decodeFromString(SnapshotDoc.serializer(), text)
                } catch (t: Throwable) {
                    call.respondText(Views.errorPage("格式损坏", "CORRUPT_FORMAT",
                        "快照解析失败：${t.message}", "document"),
                        ContentType.Text.Html, HttpStatusCode.BadRequest); return@post
                }
                val outcome = storage.tx { c ->
                    val r = storage.importSnapshot(c, parsed, sourceLabel)
                    if (r is ImportOutcome.Ok) storage.appendEvent(c, "IMPORT_SNAPSHOT",
                        kotlinx.serialization.json.JsonObject(mapOf(
                            "snapshotId" to kotlinx.serialization.json.JsonPrimitive(r.snapshotId),
                            "source" to kotlinx.serialization.json.JsonPrimitive(sourceLabel),
                            "parserVersion" to kotlinx.serialization.json.JsonPrimitive(parsed.parserVersion)
                        )).toString())
                    r
                }
                when (outcome) {
                    is ImportOutcome.Ok -> call.respondRedirect("/snapshot/${outcome.snapshotId}/diagnostics")
                    is ImportOutcome.CorruptFormat -> call.respondText(
                        Views.errorPage("格式损坏", "CORRUPT_FORMAT", outcome.message, outcome.position),
                        ContentType.Text.Html, HttpStatusCode.BadRequest)
                }
            }

            post("/restore") {
                val text = readMultipartText(call, "exportFile")
                when (val r = ExportImport.importText(storage, text)) {
                    is ImportOutcome.Ok -> call.respondRedirect("/")
                    is ImportOutcome.CorruptFormat -> call.respondText(
                        Views.errorPage("格式损坏 / 半写恢复", "CORRUPT_FORMAT", r.message, r.position),
                        ContentType.Text.Html, HttpStatusCode.BadRequest)
                }
            }
        }
    }

    private suspend fun readMultipartText(call: ApplicationCall, name: String): String {
        val mp = call.receiveMultipart()
        while (true) {
            val part = mp.readPart() ?: return ""
            try {
                if (part is PartData.FileItem && part.name == name)
                    return part.streamProvider().readBytes().toString(Charsets.UTF_8)
            } finally {
                if (part is PartData.FileItem) part.dispose()
            }
        }
    }

    private suspend fun readMultipartField(call: ApplicationCall, name: String): String? {
        val mp = call.receiveMultipart()
        while (true) {
            val part = mp.readPart() ?: return null
            if (part is PartData.FormItem && part.name == name) return part.value
        }
    }

    fun start(host: String, port: Int) {
        embeddedServer(Netty, host = host, port = port, module = { module(this) }).start(wait = true)
    }
}
