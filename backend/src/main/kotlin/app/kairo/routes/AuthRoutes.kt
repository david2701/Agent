package app.kairo.routes

import app.kairo.Services
import app.kairo.auth.AuthException
import app.kairo.auth.TokenPair
import app.kairo.domain.User
import app.kairo.services.NoticeText
import app.kairo.util.M
import app.kairo.util.Plain
import app.kairo.util.say
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

/** Join the family with an invitation, or set a new password with an access code. */
@Serializable
data class JoinRequest(
    val code: String,
    val email: String? = null,
    val password: String,
    val displayName: String? = null,
    val deviceName: String = "",
    val platform: String = "web",
    val timezone: String? = null,
)

private const val COOKIE = "kairo_rt"

fun Route.authRoutes(s: Services) {
    route("/auth") {
        rateLimit(RateLimitName("auth")) {
            post("/login") {
                val req = call.receiveBody<LoginRequest>(MAX_AUTH_BODY)
                val (user, tokens) = try {
                    s.auth.login(req.email, req.password, req.deviceName, req.platform.lowercase())
                } catch (e: AuthException) {
                    throw ApiException(HttpStatusCode.Unauthorized, e)
                }
                val finalUser = if (user.timezone == "UTC" && req.timezone != null && runCatching { java.time.ZoneId.of(req.timezone) }.isSuccess) {
                    s.users.update(user.id, null, req.timezone) ?: user
                } else user
                call.respondTokens(finalUser, tokens, web = req.platform.lowercase() == "web", s.config.publicUrl.startsWith("https"))
            }

            post("/pair") {
                val req = call.receiveBody<PairRequest>(MAX_AUTH_BODY)
                val (user, tokens) = try {
                    s.auth.completePairing(req.code, req.deviceName, req.platform.lowercase().take(20))
                } catch (e: AuthException) {
                    throw ApiException(HttpStatusCode.Unauthorized, e)
                }
                call.respondTokens(user, tokens, web = false, s.config.publicUrl.startsWith("https"))
            }

            /** What a code opens (to show "You were invited to Our family" before asking for details). */
            get("/invite/{code}") {
                val code = call.parameters["code"] ?: badRequest(M.MISSING_CODE)
                call.respond(s.family.preview(code) ?: notFound(M.NF_CODE))
            }

            post("/join") {
                val req = call.receiveBody<JoinRequest>(MAX_AUTH_BODY)
                val platform = req.platform.lowercase().take(20)
                val (user, tokens, isNew, createdBy) = try {
                    // A new account starts with the language of the request (es* -> Spanish, anything else -> English)
                    s.auth.join(req.code, req.email, req.password, req.displayName, req.deviceName, platform, req.timezone, call.acceptedLang(s.config.defaultLanguage))
                } catch (e: AuthException) {
                    badRequest(e)
                }
                val person = Plain(user.displayName)
                if (isNew) {
                    s.notifier.everyone(
                        user.id, app.kairo.domain.NoticeKinds.MESSAGE,
                        NoticeText(say(M.NOTICE_JOINED_TITLE, person), say(M.NOTICE_JOINED_BODY)), "family", user.id,
                    )
                } else {
                    // Access code used: the account has a new password. The whole family should know,
                    // so nobody can quietly get into someone else's account.
                    val by = createdBy?.takeIf { it != user.id }?.let { s.notifier.firstName(it) }
                    s.notifier.everyone(
                        user.id, app.kairo.domain.NoticeKinds.MESSAGE,
                        NoticeText(
                            say(M.NOTICE_CODE_USED_TITLE, person),
                            if (by != null) say(M.NOTICE_CODE_USED_BODY_BY, person, Plain(by)) else say(M.NOTICE_CODE_USED_BODY, person),
                        ),
                        "family", user.id,
                    )
                }
                // New member or access code used: the family (and the pending invitations) changed
                s.bus.entityChanged(s.family.memberIds(user.id), "family", user.id, "updated")
                call.respondTokens(user, tokens, web = platform == "web", s.config.publicUrl.startsWith("https"))
            }
        }

        // Its own limit: every page load renews the session and must not exhaust the login one.
        rateLimit(RateLimitName("refresh")) {
            post("/refresh") {
                // Always JSON (clients send "{}" or {"refreshToken": ...}): this way another page cannot
                // trigger the renewal with the cookie using a form or a fetch without CORS.
                if (!call.isJsonRequest()) throw ApiException(HttpStatusCode.UnsupportedMediaType, M.BODY_NOT_JSON)
                val body = runCatching { call.receiveBody<RefreshRequest>(MAX_AUTH_BODY) }.getOrNull()
                val token = body?.refreshToken ?: call.request.cookies[COOKIE] ?: throw ApiException(HttpStatusCode.Unauthorized, M.NOT_SIGNED_IN)
                val fromCookie = body?.refreshToken == null
                val (user, tokens) = try {
                    s.auth.refresh(token)
                } catch (e: AuthException) {
                    if (fromCookie) call.clearCookie(s.config.publicUrl.startsWith("https"))
                    throw ApiException(HttpStatusCode.Unauthorized, e)
                }
                call.respondTokens(user, tokens, web = fromCookie, s.config.publicUrl.startsWith("https"))
            }
        }

        authenticate("jwt") {
            post("/logout") {
                call.deviceId()?.let { s.users.revokeDevice(call.userId(), it) }
                s.auth.forgetSessions()
                call.clearCookie(s.config.publicUrl.startsWith("https"))
                call.respond(OkResponse())
            }
            post("/pair-code") {
                val code = try {
                    s.auth.createPairCode(call.userId())
                } catch (e: AuthException) {
                    throw ApiException(HttpStatusCode.ServiceUnavailable, e)
                }
                call.respond(PairCodeResponse(code))
            }
            post("/password") {
                val req = call.receiveBody<PasswordChange>(MAX_AUTH_BODY)
                try {
                    // It closes the other sessions: if someone else knew the previous password, they are locked out
                    s.auth.changePassword(call.userId(), call.deviceId(), req.currentPassword, req.newPassword)
                } catch (e: AuthException) {
                    badRequest(e)
                }
                call.respond(OkResponse())
            }
        }
    }

    authenticate("jwt") {
        get("/me") {
            call.respond(UserResponse(s.users.byId(call.userId()) ?: notFound(M.NF_USER)))
        }
        patch("/me") {
            val p = call.receiveBody<ProfilePatch>(MAX_AUTH_BODY)
            p.timezone?.let { tz -> if (runCatching { java.time.ZoneId.of(tz) }.isFailure) badRequest(M.INVALID_TIMEZONE) }
            // The name is cleaned in UsersRepo.update: no line breaks or control characters, 60 at most
            // (it appears in the assistant prompts of the other members).
            call.respond(UserResponse(s.users.update(call.userId(), p.displayName, p.timezone) ?: notFound(M.NF_USER)))
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
