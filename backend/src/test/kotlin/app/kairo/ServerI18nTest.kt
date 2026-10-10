package app.kairo

import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.nan.NanClient
import app.kairo.routes.ApiError
import app.kairo.util.ApiJson
import app.kairo.util.Lang
import app.kairo.util.M
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server's own pages and error messages: the dashboard SPA fallback (`/join`, the legacy `/unirse`)
 * and API errors rendered in the language of the request. No database is needed: none of these routes reach it.
 */
class ServerI18nTest {

    private val hex64 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    private fun config(defaultLanguage: Lang = Lang.EN) = AppConfig(
        port = 0, publicUrl = "https://kairo.example.com", dbUrl = "", dbUser = "", dbPassword = "",
        nanBaseUrl = "http://localhost", nanApiKey = "x", jwtSecret = hex64, encryptionKey = ByteArray(32) { 1 },
        ownerEmail = null, ownerPassword = null, ownerName = "You", defaultTimezone = "UTC",
        googleClientId = null, googleClientSecret = null, ntfyUrl = null, ntfyTopic = null, ntfyToken = null,
        corsOrigins = emptyList(), schedulersEnabled = false, defaultLanguage = defaultLanguage,
    )

    private fun ApplicationTestBuilder.server(defaultLanguage: Lang = Lang.EN) {
        val services = Services(config(defaultLanguage), Db(HikariDataSource()), NanClient("http://localhost:1", "x"))
        application { kairoModule(services) }
    }

    private suspend fun HttpResponse.error(): String = ApiJson.decodeFromString(ApiError.serializer(), bodyAsText()).error

    @Test
    fun `dashboard routes fall back to the single page app`() = testApplication {
        server()
        val english = listOf("today", "chat", "tasks", "calendar", "reminders", "notes", "lists", "memory", "recordings", "activity", "family", "settings", "login", "join")
        val legacy = listOf("hoy", "tareas", "calendario", "recordatorios", "notas", "listas", "memoria", "grabaciones", "actividad", "familia", "ajustes", "unirse")
        for (path in listOf("/", "/join?code=ABCD-2345", "/settings?google=connected") + (english + legacy).filter { it != "unirse" }.map { "/$it" }) {
            val r = client.get(path)
            assertEquals(HttpStatusCode.OK, r.status, path)
            assertTrue(r.bodyAsText().contains("<html"), "$path should serve index.html")
        }
    }

    @Test
    fun `legacy invite link is redirected to the canonical join link`() = testApplication {
        server()
        val noFollow = createClient { followRedirects = false }
        val r = noFollow.get("/unirse?codigo=ABCD-2345")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/join?code=ABCD-2345", r.headers[HttpHeaders.Location])
        // Following the redirect lands on the dashboard
        val followed = client.get("/unirse?codigo=ABCD-2345")
        assertEquals(HttpStatusCode.OK, followed.status)
        assertTrue(followed.bodyAsText().contains("<html"))
        assertEquals("/join", noFollow.get("/unirse").headers[HttpHeaders.Location])
        assertEquals("/join?code=WXYZ-9999", noFollow.get("/unirse?code=WXYZ-9999").headers[HttpHeaders.Location])
    }

    @Test
    fun `api errors follow the accept-language header`() = testApplication {
        server()
        val en = client.get("/api/nothing-here")
        assertEquals(HttpStatusCode.NotFound, en.status)
        assertEquals(M.NF_ROUTE.en, en.error())
        val es = client.get("/api/nothing-here") { header(HttpHeaders.AcceptLanguage, "es-CO,es;q=0.9,en;q=0.8") }
        assertEquals(M.NF_ROUTE.es, es.error())
        val other = client.get("/api/nothing-here") { header(HttpHeaders.AcceptLanguage, "fr-FR") }
        assertEquals(M.NF_ROUTE.en, other.error())
    }

    @Test
    fun `server default language is used when the request has no preference`() = testApplication {
        server(defaultLanguage = Lang.ES)
        assertEquals(M.NF_ROUTE.es, client.get("/api/nothing-here").error())
        assertEquals(M.NF_ROUTE.en, client.get("/api/nothing-here") { header(HttpHeaders.AcceptLanguage, "en-US") }.error())
    }

    @Test
    fun `authentication and body errors are localized before sign in`() = testApplication {
        server()
        val unauthorized = client.get("/api/notes")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertEquals(M.INVALID_SESSION.en, unauthorized.error())
        val unauthorizedEs = client.get("/api/notes") { header(HttpHeaders.AcceptLanguage, "es") }
        assertEquals(M.INVALID_SESSION.es, unauthorizedEs.error())

        val badBody = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"mail":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badBody.status)
        assertEquals(M.INVALID_BODY.en, badBody.error())
        val badBodyEs = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.AcceptLanguage, "es-ES")
            setBody("""{"mail":"x"}""")
        }
        assertEquals(M.INVALID_BODY.es, badBodyEs.error())

        val notJson = client.post("/api/auth/login") {
            contentType(ContentType.Text.Plain)
            setBody("hello")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, notJson.status)
        assertEquals(M.BODY_NOT_JSON.en, notJson.error())
    }
}
