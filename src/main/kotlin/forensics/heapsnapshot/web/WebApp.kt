package forensics.heapsnapshot.web

import forensics.heapsnapshot.cases.CaseSeeder
import forensics.heapsnapshot.engine.AnalysisParams
import forensics.heapsnapshot.storage.BudgetExhaustedApi
import forensics.heapsnapshot.storage.ExportEnvelope
import forensics.heapsnapshot.storage.ImportOutcome
import forensics.heapsnapshot.storage.ProjectService
import forensics.heapsnapshot.storage.ProjectRow
import forensics.heapsnapshot.storage.Repository
import forensics.heapsnapshot.storage.StoredEvent
import forensics.heapsnapshot.storage.ProjectState
import forensics.heapsnapshot.engine.ClassGroupDiff
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLQueryComponent
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WebContext(val service: ProjectService, val repo: Repository, val json: Json)

fun Application.configureWeb(ctx: WebContext) {
    routing {
        get("/") {
            val existing = ctx.repo.listProjects().firstOrNull { it.casePackage == CaseSeeder.CASE_PACKAGE }
            val pid = existing?.id ?: ctx.service.createProject(CaseSeeder.PROJECT_NAME, CaseSeeder.CASE_PACKAGE).id
            call.respondRedirect("/projects/$pid/review")
        }

        get("/projects/{id}/review") {
            val id = call.parameters["id"]!!
            call.respondText(Pages.reviewPage(ctx, id, call.request.queryParameters["at"]), ContentType.Text.Html)
        }

        get("/projects/{id}/graph.svg") {
            val id = call.parameters["id"]!!
            val q = call.request.queryParameters
            val result = Pages.latestResult(ctx, id, q["snapshot"], q["at"])
            call.respondText(SvgRender.graph(result, q["obj"]), ContentType("image", "svg+xml"))
        }

        get("/api/projects") {
            call.respondText(ctx.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ProjectRow.serializer()), ctx.repo.listProjects()), ContentType.Application.Json)
        }

        get("/api/projects/{id}/events") {
            val rows = ctx.repo.events(call.parameters["id"]!!)
            call.respondText(ctx.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(StoredEvent.serializer()), rows), ContentType.Application.Json)
        }

        get("/api/projects/{id}/state") {
            val id = call.parameters["id"]!!
            val state: ProjectState = ctx.service.stateAt(id, call.request.queryParameters["at"])
            call.respondText(ctx.json.encodeToString(ProjectState.serializer(), state), ContentType.Application.Json)
        }

        get("/api/projects/{id}/snapshot/{snap}") {
            val stored = ctx.repo.rawSnapshot(call.parameters["snap"]!!)
                ?: return@get call.respond(HttpStatusCode.NotFound, "snapshot not found")
            call.respondText(stored.raw, ContentType.Text.Plain)
        }

        post("/api/projects/{id}/import") {
            val id = call.parameters["id"]!!
            val name = call.request.queryParameters["name"] ?: "manual-import.hsp"
            when (val r = ctx.service.importSnapshot(id, name, call.receiveText())) {
                is ImportOutcome.Imported -> call.respond(mapOf("outcome" to "OK", "snapshotId" to r.snapshotId, "warnings" to r.warnings))
                is ImportOutcome.SemanticAmbiguity -> call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    mapOf("outcome" to "SEMANTIC_AMBIGUITY", "diagnostics" to r.diagnostics),
                )
                is ImportOutcome.Corrupt -> call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("outcome" to "CORRUPT_FORMAT", "diagnostics" to r.diagnostics),
                )
                is ImportOutcome.BudgetExhausted -> call.respond(
                    HttpStatusCode.InsufficientStorage,
                    mapOf("outcome" to "BUDGET_EXHAUSTED", "limit" to r.limit, "observed" to r.observed),
                )
            }
        }

        post("/api/projects/{id}/analyze") {
            val id = call.parameters["id"]!!
            val req: JsonObject = ctx.json.parseToJsonElement(call.receiveText()).jsonObject
            val params = req["params"]?.let { ctx.json.decodeFromJsonElement(AnalysisParams.serializer(), it) }
            try {
                val (event, result) = ctx.service.computeAnalysis(
                    id, req["snapshotId"]!!.jsonPrimitive.content, paramsOverride = params,
                )
                call.respond(mapOf("eventId" to event.eventId, "reachable" to result.reachableCount, "candidates" to result.leakCandidates.size))
            } catch (e: BudgetExhaustedApi) {
                call.respond(HttpStatusCode.InsufficientStorage,
                    mapOf("outcome" to "BUDGET_EXHAUSTED", "limit" to e.limit, "observed" to e.observed))
            }
        }

        post("/api/projects/{id}/adjudicate") {
            val id = call.parameters["id"]!!
            val req = ctx.json.parseToJsonElement(call.receiveText()).jsonObject
            val params = req["params"]?.let { ctx.json.decodeFromJsonElement(AnalysisParams.serializer(), it) }
            val event = ctx.service.adjudicate(
                projectId = id,
                candidateId = req["candidateId"]!!.jsonPrimitive.content,
                decision = req["decision"]!!.jsonPrimitive.content,
                rationaleCode = req["rationaleCode"]!!.jsonPrimitive.content,
                rationaleText = req["rationaleText"]?.jsonPrimitive?.content ?: "",
                params = params,
            )
            call.respond(mapOf("eventId" to event.eventId))
        }

        post("/projects/{id}/diff") {
            val id = call.parameters["id"]!!
            val form = parseSimpleForm(call.receiveText())
            ctx.service.computeDiff(id, form.getValue("beforeId"), form.getValue("afterId"))
            call.respondRedirect("/projects/$id/review#diff")
        }

        get("/projects/{id}/export") {
            val envelope = ctx.service.exportProject(call.parameters["id"]!!)
            call.response.headers.append("Content-Disposition", "attachment; filename=\"heap-shadow-export.json\"")
            call.respondText(ctx.json.encodeToString(ExportEnvelope.serializer(), envelope), ContentType.Application.Json)
        }

        post("/api/projects/import-export") {
            val envelope = ctx.json.decodeFromString(ExportEnvelope.serializer(), call.receiveText())
            val row = ctx.service.importProject(envelope)
            call.respond(mapOf("projectId" to row.id))
        }
    }
}

private fun parseSimpleForm(body: String): Map<String, String> =
    body.split("&").filter { it.contains("=") }.associate {
        val idx = it.indexOf("=")
        it.substring(0, idx).decodeURLQueryComponent() to it.substring(idx + 1).decodeURLQueryComponent()
    }
