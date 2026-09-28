package forensics.heapsnapshot

import forensics.heapsnapshot.cases.CaseSeeder
import forensics.heapsnapshot.storage.Database
import forensics.heapsnapshot.storage.ProjectService
import forensics.heapsnapshot.storage.Repository
import forensics.heapsnapshot.web.WebContext
import forensics.heapsnapshot.web.configureWeb
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import java.io.File

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5366
    var dbPath = File(System.getProperty("user.dir"), "heap-shadow-lab.sqlite").absolutePath
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> host = args[++i]
            "--port" -> { port = args[i + 1].toInt(); i++ }
            "--db" -> dbPath = args[++i]
        }
        i++
    }

    val db = Database(dbPath)
    val repo = Repository(db)
    val recovered = repo.recoverHalfWrites()
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val service = ProjectService(repo, db)
    CaseSeeder.seedIfAbsent(service)

    embeddedServer(Netty, port = port, host = host) {
        install(ContentNegotiation) { json(json) }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respondText(
                    "500 ${cause.javaClass.simpleName}: ${cause.message}",
                    io.ktor.http.ContentType.Text.Plain,
                    HttpStatusCode.InternalServerError,
                )
            }
        }
        configureWeb(WebContext(service, repo, json))
    }.start(wait = true)
}
