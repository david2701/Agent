package app.kairo.routes

import app.kairo.Services
import app.kairo.auth.AuthException
import app.kairo.auth.TokenPair
import app.kairo.domain.User
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val email: String, val password: String, val deviceName: String = "", val platform: String = "web", val timezone: String? = null)

@Serializable
data class RefreshRequest(val refreshToken: String? = null)

@Serializable
data class AuthResponse(val user: User, val accessToken: String, val refreshToken: String? = null, val expiresIn: Long, val deviceId: String)

@Serializable
data class ProfilePatch(val displayName: String? = null, val timezone: String? = null)

@Serializable
data class PairCodeResponse(val code: String, val expiresIn: Int = 300)

@Serializable
data class PairRequest(val code: String, val deviceName: String = "", val platform: String = "wear")

@Serializable
data class PasswordChange(val currentPassword: String, val newPassword: String)

private const val COOKIE = "kairo_rt"

fun Route.authRoutes(s: Services) {
    route("/auth") {
        rateLimit(RateLimitName("auth")) {
            post("/login") {
                val req = call.receiveBody<LoginRequest>()
                val (user, tokens) = try {
                    s.auth.login(req.email, req.password, req.deviceName, req.platform.lowercase())
                } catch (e: AuthException) {
                    throw ApiException(HttpStatusCode.Unauthorized, e.message ?: "No autorizado")
                }
                val finalUser = if (user.timezone == "UTC" && req.timezone != null && runCatching { java.time.ZoneId.of(req.timezone) }.isSuccess) {
                    s.users.update(user.id, null, req.timezone) ?: user
                } else user
                call.respondTokens(finalUser, tokens, web = req.platform.lowercase() == "web", s.config.publicUrl.startsWith("https"))
            }

            post("/pair") {
                val req = call.receiveBody<PairRequest>()
                val (user, tokens) = try {
                    s.auth.completePairing(req.code, req.deviceName, req.platform.lowercase().take(20))
                } catch (e: AuthException) {
                    throw ApiException(HttpStatusCode.Unauthorized, e.message ?: "Código inválido")
                }
                call.respondTokens(user, tokens, web = false, s.config.publicUrl.startsWith("https"))
            }
        }

        // Límite propio: cada carga de página renueva la sesión y no debe agotar el de inicio de sesión.
        rateLimit(RateLimitName("refresh")) {
            post("/refresh") {
                val body = runCatching { call.receiveBody<RefreshRequest>() }.getOrNull()
                val token = body?.refreshToken ?: call.request.cookies[COOKIE] ?: throw ApiException(HttpStatusCode.Unauthorized, "Sin sesión")
                val fromCookie = body?.refreshToken == null
                val (user, tokens) = try {
                    s.auth.refresh(token)
                } catch (e: AuthException) {
                    if (fromCookie) call.clearCookie(s.config.publicUrl.startsWith("https"))
                    throw ApiException(HttpStatusCode.Unauthorized, e.message ?: "Sesión expirada")
                }
                call.respondTokens(user, tokens, web = fromCookie, s.config.publicUrl.startsWith("https"))
            }
        }

        authenticate("jwt") {
            post("/logout") {
                call.deviceId()?.let { s.users.revokeDevice(call.userId(), it) }
                call.clearCookie(s.config.publicUrl.startsWith("https"))
                call.respond(OkResponse())
            }
            post("/pair-code") {
                call.respond(PairCodeResponse(s.auth.createPairCode(call.userId())))
            }
            post("/password") {
                val req = call.receiveBody<PasswordChange>()
                try {
                    s.auth.changePassword(call.userId(), req.currentPassword, req.newPassword)
                } catch (e: AuthException) {
                    badRequest(e.message ?: "No se pudo cambiar la contraseña")
                }
                call.respond(OkResponse())
            }
        }
    }

    authenticate("jwt") {
        get("/me") {
            call.respond(UserResponse(s.users.byId(call.userId()) ?: notFound("Usuario")))
        }
        patch("/me") {
            val p = call.receiveBody<ProfilePatch>()
            p.timezone?.let { tz -> if (runCatching { java.time.ZoneId.of(tz) }.isFailure) badRequest("Zona horaria inválida") }
            call.respond(UserResponse(s.users.update(call.userId(), p.displayName, p.timezone) ?: notFound("Usuario")))
        }
    }
}

private suspend fun ApplicationCall.respondTokens(user: User, t: TokenPair, web: Boolean, secure: Boolean) {
    if (web) {
        response.cookies.append(
            Cookie(
                name = COOKIE, value = t.refreshToken, encoding = CookieEncoding.RAW, maxAge = 90 * 24 * 3600,
                path = "/api/auth", secure = secure, httpOnly = true, extensions = mapOf("SameSite" to "Strict"),
            ),
        )
    }
    respond(AuthResponse(user, t.accessToken, if (web) null else t.refreshToken, t.expiresIn, t.deviceId.toString()))
}

private fun ApplicationCall.clearCookie(secure: Boolean) {
    response.cookies.append(
        Cookie(name = COOKIE, value = "", maxAge = 0, path = "/api/auth", secure = secure, httpOnly = true, extensions = mapOf("SameSite" to "Strict")),
    )
}
