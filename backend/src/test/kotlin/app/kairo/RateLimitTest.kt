package app.kairo

import app.kairo.routes.ApiError
import app.kairo.routes.IA_HOURLY_LIMIT
import app.kairo.routes.IA_LIMIT
import app.kairo.routes.iaLimited
import app.kairo.routes.userLimitKey
import app.kairo.util.ApiJson
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Los límites de las rutas que llaman a Nan son por persona, no por IP (la familia comparte la conexión). */
class RateLimitTest {

    private val algorithm = Algorithm.HMAC256("prueba-de-limites")
    private fun token(user: UUID) = JWT.create().withSubject(user.toString()).sign(algorithm)

    @Test
    fun `el tope es por persona y responde 429 en espanol`() = testApplication {
        application {
            install(ContentNegotiation) { json(ApiJson) }
            install(Authentication) {
                jwt("jwt") {
                    verifier(JWT.require(algorithm).build())
                    validate { JWTPrincipal(it.payload) }
                }
            }
            install(RateLimit) {
                register(IA_LIMIT) { rateLimiter(limit = 3, refillPeriod = 10.minutes); requestKey { userLimitKey(it) } }
                register(IA_HOURLY_LIMIT) { rateLimiter(limit = 100, refillPeriod = 60.minutes); requestKey { userLimitKey(it) } }
            }
            install(StatusPages) {
                status(HttpStatusCode.TooManyRequests) { call, code -> call.respond(code, ApiError("Demasiadas peticiones seguidas")) }
            }
            routing {
                authenticate("jwt") { iaLimited { post("/chat") { call.respondText("ok") } } }
            }
        }
        val ana = token(UUID.randomUUID())
        val luis = token(UUID.randomUUID())
        repeat(3) {
            assertEquals(HttpStatusCode.OK, client.post("/chat") { header(HttpHeaders.Authorization, "Bearer $ana") }.status)
        }
        val blocked = client.post("/chat") { header(HttpHeaders.Authorization, "Bearer $ana") }
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertTrue(blocked.bodyAsText().contains("Demasiadas peticiones"))
        // Otra persona desde la misma IP no se ve afectada
        assertEquals(HttpStatusCode.OK, client.post("/chat") { header(HttpHeaders.Authorization, "Bearer $luis") }.status)
        // Sin sesión no se llega al límite: primero responde la autenticación
        assertEquals(HttpStatusCode.Unauthorized, client.post("/chat").status)
    }
}
