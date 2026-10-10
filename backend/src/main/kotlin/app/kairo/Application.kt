package app.kairo

import app.kairo.auth.AuthService
import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.nan.NanClient
import app.kairo.nan.NanException
import app.kairo.routes.ApiError
import app.kairo.routes.ApiException
import app.kairo.routes.agentRoutes
import app.kairo.routes.authRoutes
import app.kairo.routes.dataRoutes
import app.kairo.routes.publicRoutes
import app.kairo.routes.systemRoutes
import app.kairo.util.ApiJson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("app.kairo")

fun main() {
    val cfg = AppConfig.fromEnv()
    val db = Db.connect(cfg.dbUrl, cfg.dbUser, cfg.dbPassword)
    val nan = NanClient(cfg.nanBaseUrl, cfg.nanApiKey)
    val services = Services(cfg, db, nan)
    runBlocking { services.auth.bootstrapOwner() }
    if (cfg.schedulersEnabled) services.schedulers.start()
    log.info("Kairo escuchando en :{} ({})", cfg.port, cfg.publicUrl)
    embeddedServer(Netty, port = cfg.port, host = "0.0.0.0") { kairoModule(services) }.start(wait = true)
}

fun Application.kairoModule(s: Services) {
    monitor.subscribe(ApplicationStopped) { s.close() }

    install(XForwardedHeaders)
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "strict-origin-when-cross-origin")
        header("Permissions-Policy", "microphone=(self), camera=(), geolocation=(self)")
        header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; " +
                "script-src 'self'; connect-src 'self' ws: wss:; font-src 'self'; frame-ancestors 'none'; base-uri 'self'",
        )
    }
    install(Compression) {
        gzip { excludeContentType(io.ktor.http.ContentType.Text.EventStream) }
    }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api") && it.request.path() != "/api/health" }
        format { call -> "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    install(ContentNegotiation) { json(ApiJson) }
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 60.seconds
        maxFrameSize = 1_000_000
    }
    if (s.config.corsOrigins.isNotEmpty()) {
        install(CORS) {
            s.config.corsOrigins.forEach { allowHost(it.removePrefix("https://").removePrefix("http://"), schemes = listOf("https", "http")) }
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowHeader("X-Kairo-Location")
            allowHeader("X-Kairo-Capabilities")
            allowHeader("X-Kairo-Client")
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
            allowCredentials = true
        }
    }
    install(RateLimit) {
        register(RateLimitName("auth")) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(e.message)) }
        exception<NanException> { call, e ->
            log.warn("Nan: {}", e.message)
            call.respond(HttpStatusCode.BadGateway, ApiError("El servicio de IA no respondió (${e.status})"))
        }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Solicitud inválida")) }
        exception<Throwable> { call, e ->
            log.error("Error no controlado en {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("Error interno del servidor"))
        }
    }

    val deviceCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    install(Authentication) {
        jwt("jwt") {
            realm = "kairo"
            verifier(s.auth.verifier)
            // El WebSocket del navegador no puede enviar cabeceras: se acepta ?access_token=
            authHeader { call ->
                call.request.parseAuthorizationHeader()
                    ?: call.request.queryParameters["access_token"]?.let { HttpAuthHeader.Single("Bearer", it) }
            }
            validate { cred ->
                val did = cred.payload.getClaim("did").asString() ?: return@validate null
                if (cred.payload.audience?.contains(AuthService.AUDIENCE) != true) return@validate null
                // Comprueba que el dispositivo no fue revocado (caché de 60 s)
                val cached = deviceCache[did]
                val active = if (cached != null && System.currentTimeMillis() - cached.first < 60_000) cached.second else {
                    val a = runCatching { s.users.isDeviceActive(java.util.UUID.fromString(did)) }.getOrDefault(false)
                    deviceCache[did] = System.currentTimeMillis() to a
                    a
                }
                if (active) JWTPrincipal(cred.payload) else null
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ApiError("Sesión inválida o expirada")) }
        }
    }

    routing {
        route("/api") {
            publicRoutes(s)
            authRoutes(s)
            authenticate("jwt") {
                dataRoutes(s)
                agentRoutes(s)
                systemRoutes(s)
            }
            route("{...}") {
                handle { call.respond(HttpStatusCode.NotFound, ApiError("Ruta no encontrada")) }
            }
        }
        // Dashboard web (compilado en resources/static)
        singlePageApplication {
            useResources = true
            filesPath = "static"
            defaultPage = "index.html"
        }
    }
}
