package heaplab

import heaplab.analysis.AnalysisOutcome
import heaplab.api.CaseBundle
import heaplab.api.Service
import heaplab.model.*
import heaplab.store.ExportBundle
import heaplab.store.Store
import heaplab.web.Web
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RunRequest(val snapshotId: String, val params: AnalysisParams = AnalysisParams())

@Serializable
data class DecisionRequest(val analysisEventId: String, val targetObjectId: String, val verdict: String, val rationale: String = "")

fun main(args: Array<String>) {
    fun arg(name: String, default: String): String {
        val i = args.indexOf(name)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else default
    }
    val host = arg("--host", "127.0.0.1")
    val port = arg("--port", "5366").toInt()
    val dbPath = arg("--db", "data/heaplab.db")
    java.io.File(dbPath).parentFile?.mkdirs()

    val store = Store(dbPath)
    val service = Service(store)
    service.seedCase(service.loadCaseResource())
    // Ensure the landing page already shows a computed baseline analysis.
    if (store.listAnalyses().isEmpty()) {
        service.runAnalysis("snap-2026-09-20-baseline", AnalysisParams())
    }

    embeddedServer(Netty, port = port, host = host) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        routing {
            get("/") {
                val events = store.listEvents()
                val current = store.currentEventId()?.let { store.getEvent(it) }
                    ?: events.lastOrNull()
                val snapshots = store.listSnapshots()
                val baseline = snapshots.firstOrNull { it.snapshotId == "snap-2026-09-20-baseline" } ?: snapshots.first()
                val outcome: AnalysisOutcome? = current?.takeIf { it.type == "analysis.completed" }
                    ?.let { Json { ignoreUnknownKeys = true }.decodeFromString(AnalysisOutcome.serializer(), it.payload) }
                    ?: store.listAnalyses().lastOrNull()
                        ?.let { Json { ignoreUnknownKeys = true }.decodeFromString(AnalysisOutcome.serializer(), it.outcome) }
                val snapForGraph = snapshots.firstOrNull { it.snapshotId == (outcome as? AnalysisOutcome.Success)?.report?.snapshotId } ?: baseline
                val body = buildString {
                    append("<h1>堆影取证室：内存快照、弱引用边与保留路径候选</h1>")
                    append("<div class='box meta'>时间基准: ISO-8601/Asia-Tokyo；大小单位: bytes(B)；坐标: SVG px 原点左上；"
                        + "解析器版本: ${Web.esc(snapForGraph.parserVersion)}；当前事件: <span class='id'>${current?.eventId ?: "—"}</span>（参数版本 ${current?.paramsVersion ?: "—"}）</div>")
                    append("<div class='box'>工作流: <a href='/api/export'>导出</a> · "
                        + "<a href='/replay'>重放案例场景</a> · "
                        + "<a href='/toggle-weak'>切换弱引用策略并重算</a> · "
                        + "<a href='/reset'>删除本地数据库并重新导入案例</a></div>")
                    append("<h2>对象图（强=绿实线 / 弱=紫虚线 / 内部=青点线；节点显示 objectId 与浅层/保留大小）</h2>")
                    append(Web.graphSvg(snapForGraph, (outcome as? AnalysisOutcome.Success)?.report))
                    append(Web.outcomeSection(outcome ?: AnalysisOutcome.SemanticUnresolvable(emptyList())))
                    if (snapshots.size >= 2) append(Web.classDiffSection(snapshots[0], snapshots[1]))
                    append(Web.decisionsSection(store.listDecisions(), store.conflictingDecisions()))
                    append(Web.eventsSection(events, current?.eventId))
                }
                call.respondText(Web.page("堆影取证室", body), ContentType.Text.Html)
            }

            get("/revert/{eventId}") {
                val ok = service.revertTo(call.parameters["eventId"]!!)
                if (ok) call.respondRedirect("/") else call.respond(HttpStatusCode.NotFound, "event not found or not committed")
            }

            get("/toggle-weak") {
                val cur = store.listAnalyses().lastOrNull()?.params ?: AnalysisParams()
                val next = cur.copy(
                    version = if (cur.version == "params/v1") "params/v2" else "params/v1",
                    weakPolicy = if (cur.weakPolicy == WeakPolicy.WEAK_RETAINED) WeakPolicy.WEAK_NOT_RETAINED else WeakPolicy.WEAK_RETAINED)
                service.runAnalysis("snap-2026-09-20-baseline", next)
                call.respondRedirect("/")
            }

            get("/replay") {
                val bundle = service.loadCaseResource()
                replayScenario(service, store, bundle)
                call.respondRedirect("/")
            }

            get("/reset") {
                store.reset()
                service.seedCase(service.loadCaseResource())
                service.runAnalysis("snap-2026-09-20-baseline", AnalysisParams())
                call.respondRedirect("/")
            }

            // ---- JSON API (server-side rules, directly testable) ----
            get("/api/snapshots") { call.respond(store.listSnapshots()) }
            post("/api/snapshots/import") {
                val s = call.receive<HeapSnapshot>()
                val (evt, diags) = service.importSnapshot(s)
                call.respond(mapOf("eventId" to evt.eventId, "diagnostics" to diags))
            }
            post("/api/analysis/run") {
                val req = call.receive<RunRequest>()
                val (outcome, evt) = service.runAnalysis(req.snapshotId, req.params)
                call.respond(mapOf("eventId" to (evt?.eventId ?: ""), "outcome" to outcome))
            }
            get("/api/events") { call.respond(store.listEvents()) }
            post("/api/decisions") {
                val req = call.receive<DecisionRequest>()
                call.respond(service.decide(req.analysisEventId, req.targetObjectId, req.verdict, req.rationale))
            }
            get("/api/export") { call.respond(store.export()) }
            post("/api/import") {
                val bundle = call.receive<ExportBundle>()
                store.import(bundle)
                call.respond(mapOf("imported" to bundle.events.size.toString()))
            }
            post("/api/reset") { store.reset(); call.respond(mapOf("ok" to "true")) }
        }
    }.start(wait = true)
}

/** Replay the case scenario: param revision, local source amendment, conflicting decisions — all as events. */
fun replayScenario(service: Service, store: Store, bundle: CaseBundle) {
    val baseline = store.getSnapshot("snap-2026-09-20-baseline") ?: return
    // step 3: params revision (weak policy flip)
    val (outcome2, evt2) = service.runAnalysis(baseline.snapshotId,
        AnalysisParams(version = "params/v2", weakPolicy = WeakPolicy.WEAK_RETAINED))
    // step 4: local source amendment — add one strong edge o4->o7 (only local dependency affected)
    val amended = baseline.copy(
        snapshotId = "snap-2026-09-20-amended",
        edges = baseline.edges + Edge("o4", "o7", EdgeKind.STRONG, "recovered"))
    service.importSnapshot(amended)
    service.runAnalysis(amended.snapshotId, AnalysisParams(version = "params/v2", weakPolicy = WeakPolicy.WEAK_RETAINED))
    // step 5: conflicting human decisions on o6
    if (evt2 != null && outcome2 is AnalysisOutcome.Success) {
        service.decide(evt2.eventId, "o6", "ACCEPT", "weak edge keeps 256B cache alive under v2 policy")
        service.decide(evt2.eventId, "o6", "REJECT", "cache is intentional; weak ref by design")
    }
}
