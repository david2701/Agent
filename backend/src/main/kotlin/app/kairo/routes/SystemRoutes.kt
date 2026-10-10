@file:UseSerializers(InstantSerializer::class, UUIDSerializer::class)

package app.kairo.routes

import app.kairo.Services
import app.kairo.domain.Activity
import app.kairo.domain.EntityTypes
import app.kairo.domain.Event
import app.kairo.domain.Kinds
import app.kairo.domain.McpServer
import app.kairo.domain.Memory
import app.kairo.domain.Note
import app.kairo.domain.Place
import app.kairo.domain.Project
import app.kairo.domain.Recording
import app.kairo.domain.Reminder
import app.kairo.domain.ShoppingList
import app.kairo.domain.Task
import app.kairo.domain.UserSettings
import app.kairo.integrations.GeoPlace
import app.kairo.integrations.GoogleCalendarInfo
import app.kairo.integrations.GoogleStatus
import app.kairo.realtime.ServerEvent
import app.kairo.util.InstantSerializer
import app.kairo.util.ApiJson
import app.kairo.util.UUIDSerializer
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.Instant
import java.util.UUID

@Serializable data class RecordingCreate(val title: String = "")
@Serializable data class RecordingFinish(val title: String? = null)
@Serializable data class CreatedCount(val created: Int)
@Serializable data class McpCreate(val name: String, val url: String, val headers: Map<String, String> = emptyMap())
@Serializable data class McpPatch(val enabled: Boolean? = null)
@Serializable data class McpTestResult(val tools: List<String>)
@Serializable data class GoogleAuthUrl(val url: String)
@Serializable data class GoogleCalendarSelect(val calendarId: String)
@Serializable data class SyncResult(val pushed: Int, val pulled: Int, val deleted: Int)
@Serializable data class DeviceSync(
    val serverTime: Instant,
    val reminders: List<Reminder>,
    val places: List<Place>,
    val eventAlerts: List<app.kairo.domain.EventAlert>,
    val briefingEnabled: Boolean,
    val briefingTime: String,
)
@Serializable data class ModelsResponse(val models: List<String>)
@Serializable data class HealthResponse(val status: String, val version: String)
@Serializable data class SettingsResponse(val settings: UserSettings, val googleAvailable: Boolean, val ntfyAvailable: Boolean)

@Serializable
data class ExportData(
    val exportedAt: Instant,
    val notes: List<Note>,
    val tasks: List<Task>,
    val projects: List<Project>,
    val reminders: List<Reminder>,
    val places: List<Place>,
    val lists: List<ShoppingList>,
    val events: List<Event>,
    val memories: List<Memory>,
    val recordings: List<Recording>,
)

fun Route.systemRoutes(s: Services) {

    // ------------------------------------------------------------ Actividad y deshacer
    get("/activity") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
        call.respond(s.activity.list(call.userId(), limit, call.instantQuery("before")))
    }
    post("/activity/{id}/undo") {
        val userId = call.userId()
        val a: Activity = s.activity.get(userId, call.uuidParam()) ?: notFound("Actividad")
        if (!a.undoable || a.entityId == null) badRequest("Esta acción no se puede deshacer")
        val id = a.entityId
        val ok = when (a.kind) {
            Kinds.CREATED -> when (a.entityType) {
                EntityTypes.NOTE -> s.notes.delete(userId, id, "undo") != null
                EntityTypes.TASK -> s.tasks.delete(userId, id, "undo") != null
                EntityTypes.REMINDER -> s.reminders.delete(userId, id, "undo") != null
                EntityTypes.LIST -> s.lists.delete(userId, id, "undo") != null
                EntityTypes.EVENT -> s.events.delete(userId, id, "undo") != null
                EntityTypes.MEMORY -> s.memories.delete(userId, id, "undo") != null
                EntityTypes.PLACE -> s.places.delete(userId, id, "undo")
                else -> false
            }
            Kinds.DELETED -> when (a.entityType) {
                EntityTypes.NOTE -> s.notes.restore(userId, id)
                EntityTypes.TASK -> s.tasks.restore(userId, id)
                EntityTypes.REMINDER -> s.reminders.restore(userId, id)
                EntityTypes.LIST -> s.lists.restore(userId, id)
                EntityTypes.EVENT -> s.events.restore(userId, id)
                EntityTypes.MEMORY -> s.memories.restore(userId, id)
                EntityTypes.PLACE -> s.places.restore(userId, id)
                else -> false
            }.also { if (it) s.activity.record(userId, Kinds.RESTORED, a.entityType, id, "Restaurado: ${a.summary.substringAfter(": ")}", "undo") }
            else -> false
        }
        if (!ok) badRequest("No se pudo deshacer (puede que ya no exista)")
        s.activity.markUndone(a.id)
        call.respond(OkResponse())
    }

    // ------------------------------------------------------------ Grabaciones
    route("/recordings") {
        get { call.respond(s.recordingsRepo.list(call.userId())) }
        post {
            val b = runCatching { call.receiveBody<RecordingCreate>() }.getOrDefault(RecordingCreate())
            call.respond(HttpStatusCode.Created, s.recordingsRepo.create(call.userId(), b.title, if (call.request.headers["X-Kairo-Client"]?.lowercase() in setOf("android", "wear")) "app" else "dashboard"))
        }
        get("/{id}") { call.respond(s.recordingsRepo.get(call.userId(), call.uuidParam()) ?: notFound("Grabación")) }
        post("/{id}/segments") {
            val up = call.receiveUpload()
            val audio = up.file ?: badRequest("Falta el audio")
            val seq = up.fields["seq"]?.toIntOrNull() ?: badRequest("Falta seq")
            val user = s.users.byId(call.userId())!!
            call.respond(s.recordings.addSegment(user, call.uuidParam(), seq, up.fields["duration"]?.toIntOrNull() ?: 0, audio, up.fileName ?: "seg.m4a", up.contentType ?: "audio/mp4"))
        }
        post("/{id}/finish") {
            val b = runCatching { call.receiveBody<RecordingFinish>() }.getOrDefault(RecordingFinish())
            val user = s.users.byId(call.userId())!!
            val id = call.uuidParam()
            s.recordingsRepo.get(user.id, id) ?: notFound("Grabación")
            s.recordings.finish(user, id, b.title)
            call.respond(HttpStatusCode.Accepted, OkResponse())
        }
        post("/{id}/tasks") {
            val user = s.users.byId(call.userId())!!
            call.respond(CreatedCount(s.recordings.createTasks(user, call.uuidParam())))
        }
        delete("/{id}") { if (!s.recordingsRepo.delete(call.userId(), call.uuidParam())) notFound("Grabación"); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ Ajustes, dispositivos, modelos
    get("/settings") { call.respond(SettingsResponse(s.settings.get(call.userId()), s.config.googleEnabled, s.ntfy.enabled)) }
    patch("/settings") {
        val patch = call.receiveJsonObject()
        val updated = runCatching { s.settings.patch(call.userId(), patch) }.getOrElse { badRequest("Ajustes inválidos: ${it.message?.take(120)}") }
        call.respond(SettingsResponse(updated, s.config.googleEnabled, s.ntfy.enabled))
    }
    get("/geocode") {
        val q = call.request.queryParameters["q"] ?: badRequest("Falta q")
        call.respond<List<GeoPlace>>(s.weather.geocode(q))
    }
    get("/models") { call.respond(ModelsResponse(runCatching { s.nan.models() }.getOrDefault(emptyList()))) }
    get("/devices") {
        val current = call.deviceId()
        call.respond(s.users.devices(call.userId()).map { it.copy(current = it.id == current) })
    }
    delete("/devices/{id}") { if (!s.users.revokeDevice(call.userId(), call.uuidParam())) notFound("Dispositivo"); call.respond(OkResponse()) }

    /** Lo que el teléfono necesita para programar alarmas locales y geovallas sin depender de push. */
    get("/sync/device") {
        val userId = call.userId()
        call.deviceId()?.let { s.users.touchDevice(it) }
        val now = Instant.now()
        val st = s.settings.get(userId)
        call.respond(
            DeviceSync(
                now, s.reminders.forDevice(userId), s.places.list(userId),
                s.events.alerts(userId, now, now.plusSeconds(7L * 86400)), st.briefingEnabled, st.briefingTime,
            ),
        )
    }

    // ------------------------------------------------------------ Archivos (URL firmada, sin cabecera de auth)
    // Se registra fuera de authenticate; ver Application.kt

    // ------------------------------------------------------------ Google Calendar
    route("/integrations/google") {
        get { call.respond<GoogleStatus>(s.google.status(call.userId())) }
        post("/auth-url") {
            if (!s.config.googleEnabled) badRequest("Configura GOOGLE_CLIENT_ID y GOOGLE_CLIENT_SECRET en el servidor")
            call.respond(GoogleAuthUrl(s.google.authUrl(call.userId())))
        }
        post("/sync") {
            val r = runCatching { s.google.sync(call.userId()) }.getOrElse { badRequest(it.message ?: "Fallo de sincronización") }
            call.respond(SyncResult(r.pushed, r.pulled, r.deleted))
        }
        get("/calendars") { call.respond<List<GoogleCalendarInfo>>(s.google.calendars(call.userId())) }
        post("/calendar") {
            val b = call.receiveBody<GoogleCalendarSelect>()
            s.google.setCalendar(call.userId(), b.calendarId)
            call.respond(s.google.status(call.userId()))
        }
        delete { s.google.disconnect(call.userId()); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ MCP
    route("/mcp-servers") {
        get { call.respond<List<McpServer>>(s.mcp.list(call.userId())) }
        post {
            val b = call.receiveBody<McpCreate>()
            if (b.name.isBlank()) badRequest("Nombre vacío")
            val server = runCatching { s.mcp.create(call.userId(), b.name, b.url, b.headers) }.getOrElse { badRequest(it.message ?: "Datos inválidos") }
            call.respond(HttpStatusCode.Created, server)
        }
        patch("/{id}") { call.respond(s.mcp.update(call.userId(), call.uuidParam(), call.receiveBody<McpPatch>().enabled) ?: notFound("Servidor")) }
        delete("/{id}") { if (!s.mcp.delete(call.userId(), call.uuidParam())) notFound("Servidor"); call.respond(OkResponse()) }
        post("/{id}/test") {
            val tools = runCatching { s.mcp.test(call.userId(), call.uuidParam()) }.getOrElse { badRequest("No se pudo conectar: ${it.message?.take(160)}") }
            call.respond(McpTestResult(tools))
        }
    }

    // ------------------------------------------------------------ Exportar todo
    get("/export") {
        val u = call.userId()
        val now = Instant.now()
        val data = ExportData(
            exportedAt = now,
            notes = s.notes.list(u, limit = 500) ,
            tasks = s.tasks.list(u, includeDone = true, limit = 5000),
            projects = s.tasks.projects(u),
            reminders = s.reminders.list(u, null, 5000),
            places = s.places.list(u),
            lists = s.lists.all(u),
            events = s.events.range(u, now.minusSeconds(5L * 365 * 86400), now.plusSeconds(2L * 365 * 86400)),
            memories = s.memories.list(u),
            recordings = s.recordingsRepo.list(u).mapNotNull { s.recordingsRepo.get(u, it.id) },
        )
        call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "kairo-export-${now.toString().take(10)}.json").toString())
        call.respondText(ApiJson.encodeToString(ExportData.serializer(), data), ContentType.Application.Json)
    }

    // ------------------------------------------------------------ Tiempo real
    webSocket("/ws") {
        val userId = call.principal<JWTPrincipal>()?.subject?.let(UUID::fromString) ?: return@webSocket
        val pinger = launch {
            while (isActive) {
                delay(25_000)
                send(Frame.Text("""{"type":"ping","data":{}}"""))
            }
        }
        val forward = launch {
            s.bus.flow(userId).collect { ev -> send(Frame.Text(ApiJson.encodeToString(ServerEvent.serializer(), ev))) }
        }
        try {
            incoming.consumeEach { frame -> if (frame is Frame.Text && frame.readText() == "ping") send(Frame.Text("""{"type":"pong","data":{}}""")) }
        } finally {
            pinger.cancel()
            forward.cancel()
        }
    }
}

/** Rutas públicas: salud, archivos firmados y callback OAuth. */
fun Route.publicRoutes(s: Services) {
    get("/health") { call.respond(HealthResponse("ok", "1.0.0")) }

    get("/files/{id}") {
        val id = call.uuidParam()
        val exp = call.request.queryParameters["exp"]?.toLongOrNull() ?: throw ApiException(HttpStatusCode.Forbidden, "Enlace inválido")
        val sig = call.request.queryParameters["sig"] ?: throw ApiException(HttpStatusCode.Forbidden, "Enlace inválido")
        if (!s.files.verify(id, exp, sig)) throw ApiException(HttpStatusCode.Forbidden, "Enlace expirado")
        val f = s.files.get(id) ?: notFound("Archivo")
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.respondBytes(f.bytes, ContentType.parse(f.contentType))
    }

    get("/integrations/google/callback") {
        val state = call.request.queryParameters["state"] ?: badRequest("Falta state")
        val code = call.request.queryParameters["code"]
        val userId = s.google.verifyState(state) ?: badRequest("Estado OAuth inválido o expirado")
        if (code == null) {
            call.respondRedirect("/ajustes?google=cancelado")
            return@get
        }
        val ok = runCatching { s.google.handleCallback(userId, code) }
        if (ok.isSuccess) {
            s.scope.launch { runCatching { s.google.sync(userId) } }
            call.respondRedirect("/ajustes?google=conectado")
        } else {
            call.respondRedirect("/ajustes?google=error")
        }
    }
}
