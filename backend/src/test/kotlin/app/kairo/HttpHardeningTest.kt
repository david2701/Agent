package app.kairo

import app.kairo.routes.ApiError
import app.kairo.routes.ApiException
import app.kairo.routes.FILE_CSP
import app.kairo.routes.MAX_AUTH_BODY
import app.kairo.routes.receiveBody
import app.kairo.routes.receiveUpload
import app.kairo.routes.respondStoredFile
import app.kairo.util.ApiJson
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
data class Echo(val email: String, val password: String = "")

/** Capped JSON bodies, mandatory Content-Type, image uploads and file headers. */
class HttpHardeningTest {

    private fun ApplicationTestBuilder.app() {
        application {
            install(ContentNegotiation) { json(ApiJson) }
            install(StatusPages) { exception<ApiException> { call, e -> call.respond(e.status, ApiError(e.message)) } }
            routing {
                post("/login") { call.respondText(call.receiveBody<Echo>(MAX_AUTH_BODY).email) }
                post("/upload") {
                    val up = call.receiveUpload(1024 * 1024)
                    call.respondText("${up.contentType}:${up.file?.size}")
                }
                get("/svg") { call.respondStoredFile("<svg onload=alert(1)/>".toByteArray(), "image/svg+xml") }
                get("/png") { call.respondStoredFile(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png") }
            }
        }
    }

    @Test
    fun `login requires JSON and rejects text plain`() = testApplication {
        app()
        val ok = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"ana@example.com","password":"x"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("ana@example.com", ok.bodyAsText())
        // A form from another page (CSRF) cannot send application/json without CORS permission
        val csrf = client.post("/login") {
            contentType(ContentType.Text.Plain)
            setBody("""{"email":"attacker@example.com","password":"x"}""")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, csrf.status)
        val form = client.post("/login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("""{"email":"attacker@example.com","password":"x"}""")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, form.status)
    }

    @Test
    fun `bodies that are too large and parser errors without details`() = testApplication {
        app()
        val big = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"${"a".repeat(20_000)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
        val bad = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"mail":"x"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val msg = ApiJson.decodeFromString(ApiError.serializer(), bad.bodyAsText()).error
        assertTrue(!msg.contains("app.kairo") && !msg.contains("Echo") && !msg.contains("serial"), msg)
    }

    private fun multipart(bytes: ByteArray, mime: String) = MultiPartFormDataContent(formData {
        append("text", "hello")
        append("file", bytes, Headers.build {
            append(HttpHeaders.ContentType, mime)
            append(HttpHeaders.ContentDisposition, "filename=\"capture\"")
        })
    })

    @Test
    fun `image uploads by their bytes`() = testApplication {
        app()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(100)
        // The type comes from the bytes even if the client says otherwise
        val ok = client.post("/upload") { setBody(multipart(jpeg, "image/*")) }
        assertEquals("image/jpeg:104", ok.bodyAsText())
        val svg = client.post("/upload") { setBody(multipart("<svg><script>alert(1)</script></svg>".toByteArray(), "image/svg+xml")) }
        assertEquals(HttpStatusCode.UnsupportedMediaType, svg.status)
        val disguised = client.post("/upload") { setBody(multipart("<html><script>alert(1)</script></html>".toByteArray(), "image/png")) }
        assertEquals(HttpStatusCode.UnsupportedMediaType, disguised.status)
        // Audio passes with its type
        val audio = client.post("/upload") { setBody(multipart(ByteArray(2000) { 1 }, "audio/mp4")) }
        assertEquals("audio/mp4:2000", audio.bodyAsText())
        val tooBig = client.post("/upload") { setBody(multipart(ByteArray(2 * 1024 * 1024), "audio/mp4")) }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
    }

    @Test
    fun `files with sandbox and SVG as an attachment`() = testApplication {
        app()
        val svg = client.get("/svg")
        assertEquals(FILE_CSP, svg.headers["Content-Security-Policy"])
        assertTrue(svg.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment"))
        assertEquals(ContentType.Application.OctetStream, svg.contentType()?.withoutParameters())
        val png = client.get("/png")
        assertEquals(FILE_CSP, png.headers["Content-Security-Policy"])
        assertNull(png.headers[HttpHeaders.ContentDisposition])
        assertEquals(ContentType.Image.PNG, png.contentType()?.withoutParameters())
    }
}
