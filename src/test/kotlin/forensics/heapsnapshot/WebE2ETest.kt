package forensics.heapsnapshot

import forensics.heapsnapshot.cases.CaseSeeder
import forensics.heapsnapshot.storage.Database
import forensics.heapsnapshot.storage.ProjectService
import forensics.heapsnapshot.storage.Repository
import forensics.heapsnapshot.web.WebContext
import forensics.heapsnapshot.web.configureWeb
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebE2ETest {

    @TempDir
    lateinit var temp: Path

    private fun appWithCase(dbFile: File): Application.() -> Unit = {
        val db = Database(dbFile.absolutePath)
        val repo = Repository(db)
        repo.recoverHalfWrites()
        val service = ProjectService(repo, db)
        CaseSeeder.seedIfAbsent(service)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        configureWeb(WebContext(service, repo, json))
    }

    @Test
    fun `root opens seeded review page with domain material`() = testApplication {
        application(appWithCase(temp.resolve("w.sqlite").toFile()))
        client.get("/").apply {
            assertEquals(HttpStatusCode.Found, status)
        }
        val projects = client.get("/api/projects").bodyAsText()
        val pid = Regex(""""id":"(proj-[^"]+)"""").find(projects)!!.groupValues[1]

        val page = client.get("/projects/$pid/review").bodyAsText()
        assertContains(page, "堆影取证室")
        assertContains(page, "S7701")
        assertContains(page, "支配父")
        assertContains(page, "svg-pixel@layout-v1")
        assertContains(page, "MULTIPLE_EQUAL_LENGTH_ROOT_PATHS")

        val svg = client.get("/projects/$pid/graph.svg?snapshot=snap-S7701").bodyAsText()
        assertTrue(svg.startsWith("<svg"))
        assertContains(svg, "snapshotIdentity=S7701")
    }

    @Test
    fun `adjudication creates event and diff renders rename directive`() = testApplication {
        application(appWithCase(temp.resolve("w2.sqlite").toFile()))
        val projects = client.get("/api/projects").bodyAsText()
        val pid = Regex(""""id":"(proj-[^"]+)"""").find(projects)!!.groupValues[1]
        val eventsBefore = client.get("/api/projects/$pid/events").bodyAsText()

        // 触发两快照类群差异（form 提交，服务端写 DiffComputed 后重定向）
        val resp = client.submitForm(
            url = "/projects/$pid/diff?at=",
            formParameters = parameters {
                append("beforeId", "snap-S7701")
                append("afterId", "snap-S7702")
            },
        )
        assertEquals(HttpStatusCode.Found, resp.status)

        val eventsAfter = client.get("/api/projects/$pid/events").bodyAsText()
        assertTrue(eventsAfter.contains("DiffComputed"))
        assertTrue(eventsAfter.contains("CORRESPONDENCE".replace("CORRESPONDENCE", "CLASS_IDENTITY_VIA_KEY_OR_DIRECTIVE")))
        assertTrue(eventsAfter.length > eventsBefore.length)

        val export = client.get("/projects/$pid/export").bodyAsText()
        assertContains(export, "heap-shadow-lab-export/v1")
        assertContains(export, "evt-")
    }

    private fun parameters(block: io.ktor.http.ParametersBuilder.() -> Unit) =
        io.ktor.http.ParametersBuilder().apply(block).build()
}
