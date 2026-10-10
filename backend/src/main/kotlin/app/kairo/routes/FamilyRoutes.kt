@file:UseSerializers(InstantSerializer::class, UUIDSerializer::class)

package app.kairo.routes

import app.kairo.Services
import app.kairo.domain.FamilyInfo
import app.kairo.domain.Household
import app.kairo.domain.Invitation
import app.kairo.domain.Member
import app.kairo.domain.Notice
import app.kairo.domain.NoticeKinds
import app.kairo.realtime.EventTypes
import app.kairo.realtime.ServerEvent
import app.kairo.util.InstantSerializer
import app.kairo.util.UUIDSerializer
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

@Serializable data class HouseholdPatch(val name: String)
@Serializable data class InviteCreate(val name: String = "", val role: String = "member")
@Serializable data class InviteCreated(val invitation: Invitation, val code: String, val link: String)
@Serializable data class MemberPatch(val role: String)
@Serializable data class FamilyMessage(val text: String, val to: List<UUID>? = null)
@Serializable data class FamilyMessageResult(val sent: Int)
@Serializable data class NoticesResponse(val notices: List<Notice>, val unread: Int)
@Serializable data class NoticesRead(val ids: List<UUID>? = null)
@Serializable data class PushConfig(val vapidPublicKey: String, val registeredDevices: Int)
@Serializable data class PushRegister(val endpoint: String, val p256dh: String, val auth: String)
@Serializable data class PushTestResult(val delivered: Int)
@Serializable data class ApnsStatus(val available: Boolean, val registeredDevices: Int)
@Serializable data class ApnsRegister(val token: String, val environment: String, val kind: String = "device", val activityId: String? = null)

fun Route.familyRoutes(s: Services) {

    // ------------------------------------------------------------ Familia
    route("/family") {
        get { call.respond<FamilyInfo>(s.family.info(call.userId())) }
        patch {
            val b = call.receiveBody<HouseholdPatch>()
            call.respond<Household>(s.family.rename(call.userId(), b.name))
        }

        get("/invites") { call.respond<List<Invitation>>(s.family.invites(call.userId())) }
        post("/invites") {
            val b = runCatching { call.receiveBody<InviteCreate>() }.getOrDefault(InviteCreate())
            val (inv, code) = s.family.createInvite(call.userId(), b.name, b.role, null)
            call.respond(HttpStatusCode.Created, InviteCreated(inv, code, joinLink(s, code)))
        }
        delete("/invites/{id}") {
            if (!s.family.revokeInvite(call.userId(), call.uuidParam())) notFound("Invitación")
            call.respond(OkResponse())
        }

        /**
         * Código para que un miembro que olvidó su contraseña defina una nueva (vence en 24 horas).
         * Quien tenga el código puede entrar a esa cuenta, así que se avisa al miembro y a toda la familia:
         * un administrador no puede usarlo en silencio para leer lo personal de otro.
         */
        post("/members/{id}/access-code") {
            val me = call.userId()
            val target = call.uuidParam()
            val (inv, code) = s.family.createInvite(me, "", "member", target)
            val admin = s.notifier.firstName(me)
            val member = s.users.byId(target)?.displayName ?: "un miembro"
            s.notifier.everyone(
                me, NoticeKinds.MESSAGE,
                "$admin generó un código de acceso para $member",
                "Con ese código se define una contraseña nueva para la cuenta de $member y se cierran sus sesiones. " +
                    "Vale 24 horas. Si $member no lo pidió, hablen en familia.",
                "family", target,
            )
            call.respond(HttpStatusCode.Created, InviteCreated(inv, code, joinLink(s, code)))
        }
        patch("/members/{id}") {
            val b = call.receiveBody<MemberPatch>()
            call.respond<Member>(s.family.setRole(call.userId(), call.uuidParam(), b.role))
        }
        delete("/members/{id}") {
            s.family.removeMember(call.userId(), call.uuidParam())
            s.auth.forgetSessions()
            s.bus.entityChanged(s.family.memberIds(call.userId()), "family", null, "updated")
            call.respond(OkResponse())
        }

        /** Mensaje a la familia ("la cena está lista"): llega como aviso a cada uno. */
        post("/message") {
            val b = call.receiveBody<FamilyMessage>()
            val text = b.text.trim().take(500).ifEmpty { badRequest("Mensaje vacío") }
            val me = call.userId()
            val members = s.family.memberIds(me)
            val to = (b.to?.filter { it in members } ?: members) - me
            if (to.isEmpty()) badRequest("No hay a quién enviarlo")
            s.notifier.family(me, to, NoticeKinds.MESSAGE, s.notifier.firstName(me), text)
            call.respond(FamilyMessageResult(to.size))
        }
    }

    // ------------------------------------------------------------ Avisos
    route("/notices") {
        get {
            val q = call.request.queryParameters
            val me = call.userId()
            call.respond(
                NoticesResponse(
                    s.notices.list(me, q["limit"]?.toIntOrNull() ?: 50, q["unread"]?.toBooleanStrictOrNull() ?: false),
                    s.notices.unreadCount(me),
                ),
            )
        }
        post("/read") {
            val b = runCatching { call.receiveBody<NoticesRead>() }.getOrDefault(NoticesRead())
            s.notices.markRead(call.userId(), b.ids)
            s.bus.entityChanged(call.userId(), "notice", null, "read")
            call.respond(OkResponse())
        }
    }

    // ------------------------------------------------------------ Push propio (UnifiedPush / Web Push y APNs)
    route("/push") {
        get { call.respond(PushConfig(s.push.vapidPublicKey(), s.push.registeredDevices(call.userId()))) }
        post("/register") {
            val b = call.receiveBody<PushRegister>()
            runCatching { s.push.register(call.userId(), call.deviceId(), b.endpoint, b.p256dh, b.auth) }
                .getOrElse { badRequest(publicMessage(it, "Datos de push inválidos")) }
            call.respond(PushConfig(s.push.vapidPublicKey(), s.push.registeredDevices(call.userId())))
        }
        delete("/register") {
            s.push.unregister(call.userId(), call.deviceId())
            call.respond(OkResponse())
        }
        /** Envía un aviso de prueba sólo por push (Web Push y APNs), para comprobar que llega con la app cerrada. */
        post("/test") {
            val me = call.userId()
            val title = "Push de Kairo funcionando"
            val body = "Este aviso llegó sin Firebase."
            val event = ServerEvent(EventTypes.FAMILY_NOTICE, buildJsonObject {
                put("id", UUID.randomUUID().toString())
                put("kind", "test")
                put("title", title)
                put("body", body)
                put("createdAt", Instant.now().toString())
            })
            val delivered = coroutineScope {
                val web = async { s.push.send(me, event) }
                val apple = async { s.apns.send(me, event, title, body) }
                web.await() + apple.await()
            }
            call.respond(PushTestResult(delivered))
        }

        // -------------------------------------------------------- Push nativo de Apple (APNs)
        route("/apns") {
            /** available: el servidor tiene credenciales de APNs; registeredDevices: tokens de APNs de este usuario. */
            get { call.respond(ApnsStatus(s.apns.available, s.apns.registeredDevices(call.userId()))) }
            /** Se guarda aunque APNs no esté configurado: empieza a funcionar en cuanto se configure. */
            post("/register") {
                val b = call.receiveBody<ApnsRegister>()
                try {
                    s.apns.register(call.userId(), call.deviceId(), b.token, b.environment, b.kind, b.activityId)
                } catch (e: IllegalArgumentException) {
                    badRequest(publicMessage(e, "Datos de APNs inválidos"))
                }
                call.respond(ApnsStatus(s.apns.available, s.apns.registeredDevices(call.userId())))
            }
            delete("/register") {
                s.apns.unregister(call.userId(), call.deviceId())
                call.respond(OkResponse())
            }
        }
    }
}

fun joinLink(s: Services, code: String): String = "${s.config.publicUrl}/unirse?codigo=$code"
