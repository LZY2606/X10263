package heapforensics

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

fun main(args: Array<String>) {
    fun argOf(name: String, def: String): String {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else def
    }
    val host = argOf("--host", "127.0.0.1")
    val port = argOf("--port", "5366").toInt()
    val dbPath = System.getenv("HEAP_DB") ?: "heap-forensics.db"
    val store = Store(dbPath)
    seedArchive(store)
    embeddedServer(Netty, port = port, host = host) { appModule(store) }.start(wait = true)
}

fun Application.appModule(store: Store) {
    suspend fun ApplicationCall.html(s: String) = respondText(s, ContentType.Text.Html)

    routing {
        get("/") {
            val snaps = store.listSnapshots()
            val events = store.listEvents()
            val rows = snaps.joinToString("") { (id, digest) ->
                val analyses = store.listAnalyses(id)
                "<tr><td><code>$id</code></td><td><code>${digest.take(16)}…</code></td>" +
                "<td><a href='/snapshots/$id'>材料</a> · <a href='/snapshots/$id/graph.svg'>对象图</a> · <a href='/snapshots/$id/analysis'>分析</a></td>" +
                "<td>${analyses.size} 份</td></tr>"
            }
            call.html(layout("审阅页", """
<div class="card"><h2>运行时团队 · 堆快照审阅工作台</h2>
<p>工作流：导入 → 计算（支配树/保留大小/root 路径）→ 记录决定 → 导出 → 删除本地库再导入，身份与来源关系保持。</p>
<p class="muted">单位：字节 B · 地址：hex · 时间基准：epoch ms (UTC) · 有效区间与失效原因随各分析结果标注。</p></div>
<div class="card"><h3>快照（${snaps.size}）</h3>
<table><tr><th>snapshotId</th><th>输入摘要</th><th>查看</th><th>已存分析</th></tr>$rows</table>
<p><a href="/diff">两个快照的类群差异</a> · <a href="/events">事件流（${events.size}）</a> · <a href="/export">导出档案 JSON</a></p></div>
<div class="card"><h3>导入堆快照 / 档案</h3>
<form method="post" action="/import"><textarea name="payload" rows="6" style="width:100%" placeholder="粘贴 RawSnapshot JSON 或导出档案 JSON"></textarea><br>
<button class="btn">导入</button></form></div>
"""))
        }

        get("/snapshots/{id}") {
            val raw = store.getSnapshot(call.parameters["id"]!!)
            if (raw == null) call.respondText("snapshot not found", status = HttpStatusCode.NotFound)
            else call.html(snapshotDetailPage(raw))
        }

        get("/snapshots/{id}/graph.svg") {
            val raw = store.getSnapshot(call.parameters["id"]!!)
            if (raw == null) { call.respondText("not found", status = HttpStatusCode.NotFound); return@get }
            val latest = store.listAnalyses(raw.snapshotId).lastOrNull()
                ?.let { store.getAnalysis(it.first) }
                ?.takeIf { it.second == "OK" }
                ?.let { snapshotJson.decodeFromString(AnalysisResult.serializer(), it.third!!) }
            call.respondText(renderGraphSvg(raw, latest), ContentType.Image.SVG)
        }

        get("/snapshots/{id}/analysis") {
            val raw = store.getSnapshot(call.parameters["id"]!!)
            if (raw == null) { call.respondText("not found", status = HttpStatusCode.NotFound); return@get }
            val weak = call.request.queryParameters["weakPolicy"] ?: WeakPolicy.WEAK_IGNORE.id
            val threshold = call.request.queryParameters["leakThresholdBytes"]?.toLongOrNull() ?: 200L
            val maxPaths = call.request.queryParameters["maxPathsPerObject"]?.toIntOrNull() ?: 8
            val params = AnalysisParams(weakPolicy = weak, leakThresholdBytes = threshold, maxPathsPerObject = maxPaths)
            val outcome = analyze(raw, params)
            val analysisId = "ana-${raw.snapshotId}-${weak}-${threshold}-${sha256(raw.snapshotId + weak + threshold).take(8)}"
            store.putAnalysis(analysisId, raw.snapshotId, outcome, System.currentTimeMillis())
            val decisions = store.listDecisions(analysisId)
            call.html(analysisPage(raw, analysisId, outcome, decisions))
        }

        get("/diff") {
            val a = call.request.queryParameters["a"] ?: "snap-baseline"
            val b = call.request.queryParameters["b"] ?: "snap-renamed"
            val ra = store.getSnapshot(a); val rb = store.getSnapshot(b)
            call.html(diffPage(if (ra != null && rb != null) diffSnapshots(ra, rb) else null))
        }

        get("/events") { call.html(eventsPage(store.listEvents())) }

        get("/events/{seq}") {
            val e = store.getEvent(call.parameters["seq"]!!.toLong())
            if (e == null) call.respondText("event not found", status = HttpStatusCode.NotFound)
            else call.html(eventDetailPage(e))
        }

        post("/decisions") {
            val p = call.receiveParameters()
            val analysisId = p["analysisId"] ?: return@post call.respondText("analysisId required", status = HttpStatusCode.BadRequest)
            val verdict = p["verdict"] ?: "ACCEPTED"
            val note = p["note"] ?: ""
            val objectId = p["objectId"]?.ifEmpty { null }
            val now = System.currentTimeMillis()
            val prior = store.listDecisions(analysisId).filter { it.objectId == objectId }.lastOrNull()
            store.addDecision(Decision("dec-$now", analysisId, objectId, verdict, note, now, prior?.decisionId))
            call.respondRedirect("/events")
        }

        post("/import") {
            val payload = call.receiveParameters()["payload"] ?: call.receiveText()
            val trimmed = payload.trim()
            when {
                trimmed.isEmpty() -> call.respondText("empty payload", status = HttpStatusCode.BadRequest)
                trimmed.contains("\"hsf-export/1\"") || trimmed.contains("\"format\":\"hsf-export/1\"") -> {
                    val bundle = Json.decodeFromString(ExportBundle.serializer(), trimmed)
                    store.importBundle(bundle, System.currentTimeMillis())
                    call.respondRedirect("/")
                }
                else -> when (val po = parseSnapshot(trimmed)) {
                    is ParseOutcome.FormatCorrupt ->
                        call.respondText("FORMAT_CORRUPT at ${po.position}: ${po.message}", status = HttpStatusCode.UnprocessableEntity)
                    is ParseOutcome.Ok -> {
                        store.putSnapshot(po.snapshot, System.currentTimeMillis())
                        call.respondRedirect("/snapshots/${po.snapshot.snapshotId}")
                    }
                }
            }
        }

        get("/export") {
            val bundle = store.export()
            call.respondText(Json { prettyPrint = true }.encodeToString(ExportBundle.serializer(), bundle),
                ContentType.Application.Json)
        }
    }
}
