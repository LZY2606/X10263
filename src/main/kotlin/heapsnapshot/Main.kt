package heapsnapshot

import heapsnapshot.analysis.AnalysisPolicy
import heapsnapshot.cases.CasePack
import heapsnapshot.export.ProjectExport
import heapsnapshot.server.*
import heapsnapshot.store.HeapStore
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

data class CliArgs(val host: String = "127.0.0.1", val port: Int = 5366, val db: String = "data/heap-shadow.sqlite")

fun main(args: Array<String>) {
    val parsed = parseArgs(args)
    val dbPath = Path(parsed.db)
    val freshBootstrap = Files.notExists(dbPath)
    val store = HeapStore(dbPath)
    store.open()
    val service = ProjectService(store)
    if (freshBootstrap || store.listProjects().none { it.first == "proj-forensics" }) {
        val packJson = readResource("/cases/case-pack.json")
        val pack = Json { ignoreUnknownKeys = true }.decodeFromString(CasePack.serializer(), packJson)
        service.bootstrapCasePack(pack)
    }
    embeddedServer(Netty, host = parsed.host, port = parsed.port) {
        module(service, store)
    }.start(wait = true)
}

fun Application.module(service: ProjectService, store: HeapStore) {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            encodeDefaults = true
        })
    }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "bad request"))) }
        exception<IllegalStateException> { call, cause -> call.respond(HttpStatusCode.UnprocessableEntity, mapOf("error" to (cause.message ?: "illegal state"))) }
    }
    routing {
        get("/") { call.respondText(readResource("/web/index.html"), ContentType.Text.Html) }
        get("/web/app.js") { call.respondText(readResource("/web/app.js"), ContentType.Application.JavaScript) }
        get("/web/styles.css") { call.respondText(readResource("/web/styles.css"), ContentType.Text.CSS) }

        route("/api") {
            get("/project") {
                val projectId = "proj-forensics"
                val snaps = store.listMaterials(projectId)
                val snapMap = snaps.mapNotNull { id ->
                    service.loadSnapshot(id)?.let { id to it.header.parserVersion }
                }.toMap()
                val events = store.events(projectId)
                val policy = events.lastOrNull { it.payload is EventPayload.ParamRevision }
                    ?.let { (it.payload as EventPayload.ParamRevision).newPolicyVersion } ?: "policy-v1"
                call.respond(
                    ProjectSummary(
                        projectId, "堆影取证室：内存快照、弱引用边与保留路径候选",
                        events.map { it.branch }.distinct().sorted(),
                        snaps, store.listAnalyses(projectId), policy, snapMap
                    )
                )
            }
            get("/snapshot/{id}") {
                val id = call.parameters["id"]!!
                val raw = store.rawMaterial(id) ?: return@get call.respondText("no snapshot", status = HttpStatusCode.NotFound)
                val snap = service.loadSnapshot(id)!!
                call.respond(SnapshotView(snap, rawPreserved = true, rawSizeChars = raw.length))
            }
            get("/analysis/{rid}") {
                val rid = call.parameters["rid"]!!
                val result = store.loadAnalysis(rid) ?: return@get call.respondText("no result", status = HttpStatusCode.NotFound)
                val snap = service.loadSnapshot(result.snapshotId)!!
                val policy = service.policies[result.policyVersion]!!
                call.respond(ResultView(result, SvgGraph.render(snap, result, policy), policy.toView()))
            }
            get("/diff") {
                val diff = store.loadDiff("diff:snap-s0:snap-s1")
                    ?: return@get call.respondText("no diff", status = HttpStatusCode.NotFound)
                call.respond(DiffView(diff, legend()))
            }
            get("/events") {
                val branch = call.request.queryParameters["branch"]
                call.respond(mapOf("events" to store.events("proj-forensics", branch)))
            }
            get("/events/{eid}/view") {
                val eid = call.parameters["eid"]!!
                val branch = call.request.queryParameters["branch"] ?: "baseline"
                call.respond(service.viewAt("proj-forensics", branch, eid))
            }
            get("/archive") {
                val branch = call.request.queryParameters["branch"] ?: "boundary-cases"
                val ces = store.events("proj-forensics", branch).mapNotNull { it.payload as? EventPayload.CounterExample }
                call.respond(ArchiveView(branch, ces))
            }
            post("/snapshot/import") {
                val text = call.receiveText()
                val branch = call.request.queryParameters["branch"] ?: "baseline"
                when (val outcome = service.import("proj-forensics", branch, text)) {
                    is ProjectService.ImportOutcome.Failed -> call.respond(ImportResponse(false, outcome.failure))
                    is ProjectService.ImportOutcome.Imported ->
                        call.respond(ImportResponse(true, null, outcome.event.eventId, outcome.snapshot.snapshotId))
                }
            }
            post("/analysis") {
                val req = call.receive<ReanalyzeRequest>()
                val snap = service.loadSnapshot(req.snapshotId)
                    ?: return@post call.respondText("no snapshot", status = HttpStatusCode.NotFound)
                val head = store.events("proj-forensics", req.branch).lastOrNull()?.eventId
                val (_, result) = service.runAnalysis("proj-forensics", req.branch, snap, req.policyVersion,
                    parentEventId = head, dependencyIds = listOf("material:${snap.snapshotId}"))
                call.respond(mapOf("resultId" to result.resultId))
            }
            post("/decision") {
                val req = call.receive<DecisionRequest>()
                val head = store.events("proj-forensics", "baseline").lastOrNull()?.eventId
                val ev = service.recordDecision("proj-forensics", "baseline", req.resultId, req.verdict,
                    req.policyVersion, req.rationale, req.conflictsWithEventId, head)
                call.respond(mapOf("eventId" to ev.eventId))
            }
            post("/policy/revise") {
                val req = call.receive<ReviseRequest>()
                val patch = kotlinx.serialization.json.buildJsonObject {
                    put("weakKeepsAlive", kotlinx.serialization.json.JsonPrimitive(req.weakKeepsAlive))
                }
                val ev = service.revisePolicy("proj-forensics", "baseline", req.oldVersion, req.newVersion, patch, req.note)
                call.respond(mapOf("eventId" to ev.eventId))
            }
            get("/export") {
                val bytes = ProjectExport.exportZip(store, "proj-forensics")
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"heap-shadow-export.zip\"")
                call.respondBytes(bytes, ContentType("application", "zip"))
            }
            post("/import-export") {
                val bytes = call.receive<ByteArray>()
                val report = ProjectExport.importInto(store, bytes)
                call.respond(report)
            }
        }
    }
}

private fun legend() =
    """<svg xmlns="http://www.w3.org/2000/svg" width="640" height="60"><text x="8" y="20">SAME=同身份同名称 RENAMED=改名对应 ADDED/REMOVED=单侧存在 UNMATCHED=对应不唯一</text></svg>"""

private fun AnalysisPolicy.toView() = AnalysisPolicyView(
    policyVersion, weakKeepsAlive, internalKeepsAlive, unknownKeepsAlive,
    leakRetainedMinBytes, maxRootPathsPerObject, note
)

private fun parseArgs(args: Array<String>): CliArgs {
    var host = "127.0.0.1"; var port = 5366; var db = "data/heap-shadow.sqlite"
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { db = args[++i] }
        }
        i++
    }
    return CliArgs(host, port, db)
}

private fun readResource(path: String): String =
    object {}.javaClass.getResourceAsStream(path)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: error("missing resource $path")
