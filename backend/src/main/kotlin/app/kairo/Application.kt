package app.kairo

import app.kairo.auth.AuthService
import app.kairo.auth.rateLimitKey
import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.nan.NanClient
import app.kairo.nan.NanException
import app.kairo.routes.IA_LIMIT
import app.kairo.routes.IA_HOURLY_LIMIT
import app.kairo.routes.RECORDING_LIMIT
import app.kairo.routes.userLimitKey
import app.kairo.routes.ApiError
import app.kairo.routes.ApiException
import app.kairo.routes.agentRoutes
import app.kairo.routes.authRoutes
import app.kairo.routes.dataRoutes
import app.kairo.routes.familyRoutes
import app.kairo.routes.acceptedLang
import app.kairo.routes.lang
import app.kairo.routes.publicError
import app.kairo.routes.publicRoutes
import app.kairo.routes.systemRoutes
import app.kairo.util.ApiJson
import app.kairo.util.M
import app.kairo.util.Say
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
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("app.kairo")

/** The only route that accepts the access token in the URL (?access_token=). */
const val WS_PATH = "/api/ws"

fun main() {
    val cfg = AppConfig.fromEnv()
    val db = Db.connect(cfg.dbUrl, cfg.dbUser, cfg.dbPassword)
    val nan = NanClient(cfg.nanBaseUrl, cfg.nanApiKey)
    val services = Services(cfg, db, nan)
    runBlocking { services.auth.bootstrapOwner() }
    if (cfg.schedulersEnabled) services.schedulers.start()
    log.info("Kairo listening on :{} ({})", cfg.port, cfg.publicUrl)
    embeddedServer(Netty, port = cfg.port, host = "0.0.0.0") { kairoModule(services) }.start(wait = true)
}

fun Application.kairoModule(s: Services) {
    monitor.subscribe(ApplicationStopped) { s.close() }

    // Behind Caddy: the client IP is the one the last proxy added. The first entry of X-Forwarded-For
    // is written by the client itself, so anyone could use it to get around the attempt limit.
    install(XForwardedHeaders) { useLastProxy() }
    install(DefaultHeaders) {
        if (s.config.publicUrl.startsWith("https://")) header("Strict-Transport-Security", "max-age=31536000")
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
        // By IP (IPv4) or by /64 prefix (IPv6: every household gets a whole /64 to rotate addresses)
        register(RateLimitName("auth")) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> rateLimitKey(call.request.origin.remoteAddress) }
        }
        register(RateLimitName("refresh")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call -> rateLimitKey(call.request.origin.remoteAddress) }
        }
        // Per person (not per IP: the family shares one): caps what an account or a stolen token can spend
        // on Nan. A person talking nonstop does not reach 20 turns per minute.
        register(IA_LIMIT) {
            rateLimiter(limit = 20, refillPeriod = 1.minutes)
            requestKey { call -> userLimitKey(call) }
        }
        register(IA_HOURLY_LIMIT) {
            rateLimiter(limit = 300, refillPeriod = 60.minutes)
            requestKey { call -> userLimitKey(call) }
        }
        register(RECORDING_LIMIT) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { call -> userLimitKey(call) }
        }
    }
    install(StatusPages) {
        // Error texts are rendered in the language of the person who made the request
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(e.text(call.lang(s)))) }
        exception<NanException> { call, e ->
            log.warn("Nan: {}", e.message)
            call.respond(HttpStatusCode.BadGateway, ApiError(M.AI_SERVICE_DOWN.t(call.lang(s), e.status)))
        }
        // Kairo's own validation messages are shown; library ones (parser, UUID, numbers) are not
        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError((publicError(e) ?: Say(M.INVALID_REQUEST)).text(call.lang(s))))
        }
        exception<app.kairo.domain.FamilyException> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ApiError(e.text(call.lang(s)))) }
        status(HttpStatusCode.TooManyRequests) { call, code -> call.respond(code, ApiError(M.TOO_MANY_REQUESTS.t(call.lang(s)))) }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {}", call.request.path(), e)
            call.respond(HttpStatusCode.InternalServerError, ApiError(M.INTERNAL_ERROR.t(call.lang(s))))
        }
    }

    install(Authentication) {
        jwt("jwt") {
            realm = "kairo"
            verifier(s.auth.verifier)
            // The browser WebSocket cannot send headers: ?access_token= is accepted only there
            // (on any other route the token would end up in histories, proxy logs and the Referer)
            authHeader { call ->
                call.request.parseAuthorizationHeader()
                    ?: call.request.queryParameters["access_token"]
                        ?.takeIf { call.request.path() == WS_PATH }
                        ?.let { HttpAuthHeader.Single("Bearer", it) }
            }
            validate { cred ->
                val did = cred.payload.getClaim("did").asString() ?: return@validate null
                val sub = cred.payload.subject ?: return@validate null
                if (cred.payload.audience?.contains(AuthService.AUDIENCE) != true) return@validate null
                // The device is still active and belongs to who the token says (short cache, cleared on revoke)
                if (s.auth.deviceActive(did, sub)) JWTPrincipal(cred.payload) else null
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ApiError(M.INVALID_SESSION.t(call.acceptedLang(s.config.defaultLanguage)))) }
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
                familyRoutes(s)
            }
            route("{...}") {
                handle { call.respond(HttpStatusCode.NotFound, ApiError(M.NF_ROUTE.t(call.lang(s)))) }
            }
        }
        // Legacy Spanish invite link (/unirse?codigo=XXXX-XXXX): keep it working by sending it to /join?code=...
        get("/unirse") {
            val code = call.request.queryParameters["codigo"] ?: call.request.queryParameters["code"]
            call.respondRedirect(if (code.isNullOrBlank()) "/join" else "/join?code=" + java.net.URLEncoder.encode(code, Charsets.UTF_8))
        }
        // Web dashboard (built into resources/static); unknown paths such as /join or /settings fall back to index.html
        singlePageApplication {
            useResources = true
            filesPath = "static"
            defaultPage = "index.html"
        }
    }
}
