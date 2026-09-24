package heapforensics.web

import heapforensics.casepack.CasePack
import heapforensics.core.Severity
import heapforensics.service.ReviewService
import heapforensics.service.ServiceResult
import heapforensics.service.reviewJson
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun startServer(service: ReviewService, host: String, port: Int) {
    embeddedServer(Netty, host = host, port = port) {
        install(ContentNegotiation) { json(reviewJson) }
        routing {
            get("/") {
                call.respondText(staticResource("index.html"), ContentType.Text.Html)
            }
            get("/static/app.js") {
                call.respondText(staticResource("app.js"), ContentType.Application.JavaScript)
            }
            get("/static/style.css") {
                call.respondText(staticResource("style.css"), ContentType.Text.CSS)
            }

            get("/api/state") {
                call.respond(buildState(service))
            }

            post("/api/seed") {
                call.respond(CasePack.seed(service))
            }

            post("/api/import") {
                val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                val name = body["sourceName"]!!.jstr()
                val text = body["text"]!!.jstr()
                when (val r = service.importSnapshot(name, text)) {
                    is ServiceResult.Ok -> call.respond(r.value)
                    else -> call.respondFailure(r)
                }
            }

            get("/api/snapshots/{id}/raw") {
                val id = call.parameters["id"]!!
                val raw = service.db.rawSnapshot(id)
                if (raw == null) call.respondText("not found", status = HttpStatusCode.NotFound)
                else call.respondText(raw.first, ContentType.Text.Plain)
            }

            get("/api/snapshots/{id}") {
                val id = call.parameters["id"]!!
                val snap = service.loadSnapshot(id)
                if (snap == null) call.respondText("not found", status = HttpStatusCode.NotFound)
                else call.respond(snap)
            }

            post("/api/params") {
                val b = Json.parseToJsonElement(call.receiveText()).jsonObject
                val cur = service.currentParams()
                val next = cur.copy(
                    weakKeepAlive = b["weakKeepAlive"]?.let { it.jstr().toBoolean() } ?: cur.weakKeepAlive,
                    internalKeepAlive = b["internalKeepAlive"]?.let { it.jstr().toBoolean() } ?: cur.internalKeepAlive,
                )
                call.respond(service.reviseParams(next, b["reason"]?.let { it.jstr() } ?: "界面参数修订"))
            }

            post("/api/patch") {
                val b = Json.parseToJsonElement(call.receiveText()).jsonObject
                when (val r = service.applyPatch(
                    b["parent"]!!.let { it.jstr() },
                    b["patchName"]!!.let { it.jstr() },
                    b["patchText"]!!.let { it.jstr() },
                    b["description"]?.let { it.jstr() } ?: "",
                )) {
                    is ServiceResult.Ok -> call.respond(r.value)
                    else -> call.respondFailure(r)
                }
            }

            post("/api/decision") {
                val b = Json.parseToJsonElement(call.receiveText()).jsonObject
                call.respond(
                    service.recordDecision(
                        subject = b["subject"]!!.jstr(),
                        verdict = b["verdict"]!!.jstr(),
                        rationale = b["rationale"]!!.jstr(),
                        reviewer = b["reviewer"]?.let { it.jstr() } ?: "reviewer",
                        paramsVersion = b["paramsVersion"]?.let { it.jstr().toInt() },
                    )
                )
            }

            get("/api/analyze") {
                val snap = call.request.queryParameters["snapshot"]!!
                val v = call.request.queryParameters["v"]?.toInt()
                when (val r = service.analyze(snap, v)) {
                    is ServiceResult.Ok -> call.respond(r.value)
                    else -> call.respondFailure(r)
                }
            }

            get("/api/graph.svg") {
                val snap = call.request.queryParameters["snapshot"]!!
                val v = call.request.queryParameters["v"]?.toInt()
                when (val r = service.analyze(snap, v)) {
                    is ServiceResult.Ok -> {
                        val s = service.loadSnapshot(snap)!!
                        call.respondText(Svg.render(s, r.value.analysis!!), ContentType.Image.SVG)
                    }
                    else -> call.respondText("analysis failed", status = HttpStatusCode.UnprocessableEntity)
                }
            }

            get("/api/diff") {
                val a = call.request.queryParameters["a"]!!
                val b = call.request.queryParameters["b"]!!
                when (val r = service.diff(a, b)) {
                    is ServiceResult.Ok -> call.respond(r.value)
                    else -> call.respondFailure(r)
                }
            }

            get("/api/events/{seq}") {
                when (val r = service.eventTimeline(call.parameters["seq"]!!.toInt())) {
                    is ServiceResult.Ok -> call.respond(r.value)
                    else -> call.respondFailure(r)
                }
            }

            get("/api/casepack/expectations") {
                call.respondText(CasePack.expectationsJson(), ContentType.Application.Json)
            }

            get("/api/export") {
                val json = service.exportProjectJson()
                call.response.header("Content-Disposition", "attachment; filename=\"heap-forensics-export.json\"")
                call.respondText(json, ContentType.Application.Json)
            }

            post("/api/import-project") {
                val text = call.receiveText()
                when (val r = service.importProject(text)) {
                    is ServiceResult.Ok -> call.respondText(r.value)
                    else -> call.respondFailure(r)
                }
            }
        }
    }.start(wait = true)
}

private fun staticResource(name: String): String =
    object {}.javaClass.getResourceAsStream("/static/$name")!!.bufferedReader().use { it.readText() }

private fun kotlinx.serialization.json.JsonElement.jstr(): String =
    this.jsonPrimitive.content

private suspend fun io.ktor.server.application.ApplicationCall.respondFailure(r: ServiceResult<*>) {
    val (code, body) = when (r) {
        is ServiceResult.BudgetExhausted -> HttpStatusCode.UnprocessableEntity to
            """{"type":"BUDGET_EXHAUSTED","budget":"${r.budget}","detail":"${r.detail}"}"""
        is ServiceResult.FormatCorrupt -> HttpStatusCode.UnprocessableEntity to
            """{"type":"FORMAT_CORRUPT","diagnostics":${reviewJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(heapforensics.core.Diagnostic.serializer()), r.diagnostics)}}"""
        is ServiceResult.SemanticallyUnsolvable -> HttpStatusCode.UnprocessableEntity to
            """{"type":"SEMANTICALLY_UNSOLVABLE","reasons":${reviewJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), r.reasons)}}"""
        else -> error("unreachable")
    }
    respondText(body, ContentType.Application.Json, code)
}

@kotlinx.serialization.Serializable
private data class StateResponse(val dummy: String = "")

private fun buildState(service: ReviewService): Map<String, Any?> {
    return mapOf(
        "currentParamsVersion" to service.currentParamsVersion(),
        "currentParams" to service.currentParams(),
        "snapshots" to service.db.listSnapshots(),
        "events" to service.events(),
        "computations" to service.db.listComputations().map {
            mapOf(
                "computationId" to it.computationId, "kind" to it.kind,
                "snapshotId" to it.snapshotId, "paramsVersion" to it.paramsVersion,
                "inputDigest" to it.inputDigest, "status" to it.status, "eventSeq" to it.eventSeq,
            )
        },
    )
}
