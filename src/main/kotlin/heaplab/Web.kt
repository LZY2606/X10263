package heaplab

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.URLEncoder

fun Application.webModule(store: Store) {
    routing {
        get("/") { call.respondText(renderHome(store), ContentType.Text.Html) }

        get("/snapshot/{id}") {
            val id = call.parameters["id"]!!
            val s = store.getSnapshot(id)
            if (s == null) call.respondText(notFound("快照 $id"), ContentType.Text.Html, HttpStatusCode.NotFound)
            else call.respondText(renderSnapshot(store, s), ContentType.Text.Html)
        }

        post("/snapshot/{id}/analyze") {
            val id = call.parameters["id"]!!
            val s = store.getSnapshot(id)
            if (s == null) { call.respondText(notFound("快照 $id"), ContentType.Text.Html, HttpStatusCode.NotFound); return@post }
            val form = call.receiveParameters()
            val version = (form["paramsVersion"]?.toIntOrNull() ?: 1)
            val weak = when (form["weakPolicy"]) {
                "keep" -> true
                "ignore" -> false
                else -> null
            }
            val params = AnalysisParams(
                paramsVersion = version,
                weakRefsKeepAlive = weak,
                internalEdgesKeepAlive = form["internalKeep"] == "on",
                maxVisitedNodes = form["maxVisited"]?.toIntOrNull() ?: 200_000,
                maxPathsPerObject = form["maxPaths"]?.toIntOrNull() ?: 64,
            )
            val outcome = Analysis.run(s, params)
            val run = store.recordRun(id, params, outcome).first
            call.respondRedirect("/run/${run.runId}")
        }

        get("/run/{id}") {
            val id = call.parameters["id"]!!
            val run = store.getRun(id)
            if (run == null) call.respondText(notFound("运行 $id"), ContentType.Text.Html, HttpStatusCode.NotFound)
            else call.respondText(renderRun(store, run, historical = null), ContentType.Text.Html)
        }

        post("/run/{id}/decide") {
            val id = call.parameters["id"]!!
            val form = call.receiveParameters()
            val accept = form["verdict"] == "accept"
            val rationale = form["rationale"]?.takeIf { it.isNotBlank() } ?: "(无理由记录)"
            store.recordDecision(id, accept, rationale, form["caseId"]?.takeIf { it.isNotBlank() })
            call.respondRedirect(form["back"] ?: "/run/$id")
        }

        get("/diff") {
            val a = call.request.queryParameters["a"]
            val b = call.request.queryParameters["b"]
            call.respondText(renderDiff(store, a, b), ContentType.Text.Html)
        }

        get("/events") { call.respondText(renderEvents(store), ContentType.Text.Html) }

        get("/event/{seq}") {
            val seq = call.parameters["seq"]!!.toLong()
            val view = store.historicalView(seq)
            if (view == null) call.respondText(notFound("事件 #$seq"), ContentType.Text.Html, HttpStatusCode.NotFound)
            else call.respondText(renderHistorical(store, view), ContentType.Text.Html)
        }

        get("/archive") { call.respondText(renderArchive(store), ContentType.Text.Html) }

        get("/export") {
            val doc = store.exportDocument()
            val text = heapJson.encodeToString(ExportDocument.serializer(), doc)
            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "heaplab-export.json").toString())
            call.respondText(text, ContentType.Application.Json)
        }

        get("/import") { call.respondText(renderImport(), ContentType.Text.Html) }

        post("/import") {
            var payload: String? = null
            val mp = call.receiveMultipart()
            mp.forEachPart { part ->
                if (part is io.ktor.http.content.PartData.FileItem) {
                    val t = part.streamProvider().bufferedReader().readText()
                    if (t.isNotBlank()) payload = t
                }
                part.dispose()
            }
            val text = payload ?: call.receiveParameters()["document"] ?: ""
            val message = try {
                val doc = heapJson.decodeFromString(ExportDocument.serializer(), text)
                store.importDocument(doc)
                "导入成功：${doc.snapshots.size} 个快照、${doc.runs.size} 个运行、${doc.events.size} 条事件，身份与顺序已保持"
            } catch (e: Throwable) {
                "导入失败（事务已回滚）：${e.message ?: e::class.simpleName}"
            }
            call.respondText(renderImport(message), ContentType.Text.Html, if (message.startsWith("导入成功")) HttpStatusCode.OK else HttpStatusCode.Conflict)
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun esc(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
