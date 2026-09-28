package forensics.web

import com.fasterxml.jackson.databind.SerializationFeature
import forensics.case.CasePack
import forensics.case.Seeder
import forensics.graph.AnalysisResult
import forensics.graph.ClassCorrespondence
import forensics.graph.SnapshotDiff
import forensics.ledger.CaseService
import forensics.ledger.ExportBundle
import forensics.model.ImportOutcome
import forensics.parse.SnapshotParser
import forensics.store.Store
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

class App(val store: Store) {
    val service = CaseService(store)

    fun module(): Application.() -> Unit = {
        install(ContentNegotiation) {
            jackson { enable(SerializationFeature.INDENT_OUTPUT) }
        }
        routing {
            get("/health") { call.respondText("ok") }

            get("/") {
                val page = App::class.java.classLoader.getResourceAsStream("web/index.html")!!
                    .bufferedReader().use { it.readText() }
                call.respondText(page, ContentType.Text.Html)
            }

            get("/api/cases") {
                call.respond(ApiResponse(true, mapOf("cases" to store.listCases())))
            }

            get("/api/cases/{caseId}/timeline") {
                val caseId = call.parameters["caseId"]!!
                val at = call.request.queryParameters["seq"]?.toIntOrNull()
                    ?: (store.events(caseId).size - 1).coerceAtLeast(0)
                val view = service.projectAt(caseId, at)
                val graphs = view.analyses.associate { it.snapshotId to SvgGraph.render(it) }
                call.respond(
                    ApiResponse(
                        true, TimelinePayload(
                            view = view, graphs = graphs, currentSeq = at,
                            decisions = store.decisions(caseId),
                            pendingWrites = store.pendingWrites().map {
                                mapOf("txId" to it.first, "stage" to it.second, "payload" to it.third.take(200))
                            },
                        )
                    )
                )
            }

            get("/api/cases/{caseId}/graph/{snapshotId}.svg") {
                val caseId = call.parameters["caseId"]!!
                val snapshotId = call.parameters["snapshotId"]!!
                val view = service.projectAt(caseId, (store.events(caseId).size - 1).coerceAtLeast(0))
                val a = view.analyses.firstOrNull { it.snapshotId == snapshotId }
                    ?: return@get call.respond(HttpStatusCode.NotFound, "无该快照的派生结果")
                call.respondText(SvgGraph.render(a), ContentType("image", "svg+xml"))
            }

            post("/api/cases/{caseId}/params") {
                val caseId = call.parameters["caseId"]!!
                val body = call.receive<Map<String, Any?>>()
                @Suppress("UNCHECKED_CAST")
                val corr = (body["classCorrespondence"] as? List<Map<String, Any?>>).orEmpty().map {
                    ClassCorrespondence(it["canonicalId"].toString(),
                        (it["members"] as Map<String, String>))
                }
                val ev = service.reviseParams(
                    caseId,
                    weak = body["weakKeepsAlive"]?.toString()?.toBoolean() ?: false,
                    internal = body["internalKeepsAlive"]?.toString()?.toBoolean() ?: true,
                    leakMin = body["leakRetainedMinBytes"]?.toString()?.toLong() ?: 1024L,
                    correspondence = corr,
                    note = body["note"]?.toString(),
                )
                call.respond(ApiResponse(true, ev))
            }

            post("/api/cases/{caseId}/compute") {
                val caseId = call.parameters["caseId"]!!
                val note = call.receiveText().ifBlank { null }
                call.respond(ApiResponse(true, service.compute(caseId, note)))
            }

            data class DecisionBody(
                val targetStableId: String = "", val decision: String = "",
                val rationale: String = "", val basedOnEventId: String = "", val adopted: Boolean = true,
                val note: String? = null,
            )

            post("/api/cases/{caseId}/decisions") {
                val caseId = call.parameters["caseId"]!!
                val body = call.receive<DecisionBody>()
                val ev = service.decide(caseId, body.targetStableId, body.decision.uppercase(),
                    body.rationale, body.basedOnEventId, body.adopted, body.note)
                call.respond(ApiResponse(true, ev))
            }

            post("/api/import-snapshot/{caseId}") {
                val caseId = call.parameters["caseId"]!!
                val text = call.receiveText()
                when (val out = service.importAdditionalSnapshot(caseId, "uploaded.json", text)) {
                    is ImportOutcome.Ok -> call.respond(ApiResponse(true, out.snapshot.snapshotId))
                    is ImportOutcome.FormatCorrupt -> call.respond(HttpStatusCode.UnprocessableEntity,
                        ApiResponse(false, errors = out.diagnostics.map { mapOf("code" to it.code, "position" to it.position, "message" to it.message) }))
                    is ImportOutcome.BudgetExhausted -> call.respond(HttpStatusCode.InsufficientStorage,
                        ApiResponse(false, errors = out.diagnostics.map { mapOf("code" to it.code, "position" to it.position, "message" to it.message) }))
                    is ImportOutcome.SemanticUnresolved -> call.respond(HttpStatusCode.Conflict,
                        ApiResponse(false, errors = out.diagnostics.map { mapOf("code" to it.code, "position" to it.position, "message" to it.message) }))
                }
            }

            get("/api/cases/{caseId}/export") {
                val bundle = service.export(call.parameters["caseId"]!!)
                call.response.header("Content-Disposition", "attachment; filename=\"heap-shadow-bundle.json\"")
                call.respondText(store.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle),
                    ContentType.Application.Json)
            }

            post("/api/import-bundle") {
                val bundle = call.receive<ExportBundle>()
                val cid = service.importBundle(bundle)
                call.respond(ApiResponse(true, mapOf("caseId" to cid)))
            }
        }
    }
}

fun startServer(host: String, port: Int, dbFile: File) {
    Class.forName("org.sqlite.JDBC")
    val store = Store(dbFile)
    val app = App(store)
    val pack = CasePack.fromClasspath()
    Seeder.seed(app.service, pack)
    embeddedServer(Netty, host = host, port = port, module = app.module()).start(wait = true)
}
