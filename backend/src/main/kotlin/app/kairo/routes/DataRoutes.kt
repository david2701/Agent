@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class, UUIDSerializer::class)

package app.kairo.routes

import app.kairo.Services
import app.kairo.domain.EventPatch
import app.kairo.domain.NotePatch
import app.kairo.domain.Recurrence
import app.kairo.domain.ReminderPatch
import app.kairo.domain.TaskPatch
import app.kairo.util.InstantSerializer
import app.kairo.util.LocalDateSerializer
import app.kairo.util.UUIDSerializer
import app.kairo.util.bool
import app.kairo.util.double
import app.kairo.util.intList
import app.kairo.util.parseInstant
import app.kairo.util.parseUserDate
import app.kairo.util.str
import app.kairo.util.strList
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val SRC = "dashboard"

/** Origen de un cambio hecho desde un cliente: "app" para Android/Wear e iPhone/Apple Watch, "dashboard" para la web. */
private fun io.ktor.server.application.ApplicationCall.source(): String =
    when (request.headers["X-Kairo-Client"]?.lowercase()) { "android", "wear", "ios", "watch" -> "app"; else -> SRC }

@Serializable data class NoteCreate(
    val title: String = "", val content: String = "", val tags: List<String> = emptyList(), val pinned: Boolean = false, val shared: Boolean = false,
)
@Serializable data class TaskCreate(
    val title: String, val notes: String = "", val priority: String = "medium", val dueDate: LocalDate? = null, val dueAt: Instant? = null,
    val projectId: UUID? = null, val project: String? = null, val tags: List<String> = emptyList(), val status: String = "todo",
    val shared: Boolean = false, val assigneeId: UUID? = null,
)
@Serializable data class ProjectCreate(val name: String, val color: String? = null)
@Serializable data class ProjectPatch(val name: String? = null, val color: String? = null, val sort: Double? = null)
@Serializable data class ReminderCreate(
    val text: String, val dueAt: Instant? = null, val rrule: String? = null, val placeId: UUID? = null, val placeTrigger: String? = null,
    /** Otro miembro de la familia al que se le recuerda. */
    val forUserId: UUID? = null,
)
@Serializable data class SnoozeRequest(val minutes: Int = 10)
@Serializable data class PlaceCreate(
    val name: String, val latitude: Double, val longitude: Double, val radiusM: Int = 150, val address: String? = null, val shared: Boolean? = null,
)
@Serializable data class PlacePatch(val shared: Boolean)
@Serializable data class ListCreate(val name: String, val icon: String? = null, val shared: Boolean = false)
@Serializable data class ListPatch(val name: String? = null, val icon: String? = null, val shared: Boolean? = null)
@Serializable data class ItemsAdd(val items: List<String>)
@Serializable data class ItemPatch(val text: String? = null, val checked: Boolean? = null, val sort: Double? = null)
@Serializable data class EventCreate(
    val title: String, val startAt: Instant, val endAt: Instant? = null, val allDay: Boolean = false, val location: String = "",
    val description: String = "", val rrule: String? = null, val reminderMinutes: List<Int>? = null, val color: String? = null,
    val shared: Boolean = false,
)
@Serializable data class MemoryCreate(val content: String, val category: String = "general")
@Serializable data class MemoryPatch(val content: String? = null, val category: String? = null)
@Serializable data class TagsResponse(val tags: List<String>)

/** Comprueba que [id] sea alguien de la familia del usuario. */
private suspend fun Services.requireMember(userId: UUID, id: UUID) {
    if (id !in family.memberIds(userId)) badRequest("Esa persona no es de tu familia")
}

fun Route.dataRoutes(s: Services) {

    // ------------------------------------------------------------ Notas
    route("/notes") {
        get {
            val q = call.request.queryParameters
            call.respond(s.notes.list(call.userId(), q["q"], q["tag"], q["limit"]?.toIntOrNull() ?: 100, q["offset"]?.toIntOrNull() ?: 0))
        }
        get("/search") {
            val q = call.request.queryParameters["q"] ?: badRequest("Falta q")
            call.respond(s.notes.search(call.userId(), q, call.request.queryParameters["limit"]?.toIntOrNull() ?: 10))
        }
        get("/tags") { call.respond(TagsResponse(s.notes.allTags(call.userId()))) }
        post {
            val b = call.receiveBody<NoteCreate>()
            if (b.content.isBlank() && b.title.isBlank()) badRequest("La nota está vacía")
            call.respond(HttpStatusCode.Created, s.notes.create(call.userId(), b.title, b.content, b.tags, b.pinned, call.source(), shared = b.shared))
        }
        get("/{id}") { call.respond(s.notes.get(call.userId(), call.uuidParam()) ?: notFound("Nota")) }
        patch("/{id}") {
            val b = call.receiveJsonObject()
            val patch = NotePatch(b.rawStr("title"), b.rawStr("content"), b.strList("tags"), b.bool("pinned"), shared = b.bool("shared"))
            call.respond(s.notes.update(call.userId(), call.uuidParam(), patch, call.source()) ?: notFound("Nota"))
        }
        delete("/{id}") { s.notes.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound("Nota"); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ Tareas y proyectos
    route("/tasks") {
        get {
            val q = call.request.queryParameters
            call.respond(
                s.tasks.list(
                    call.userId(), status = q["status"], projectId = q["projectId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                    includeDone = q["includeDone"]?.toBooleanStrictOrNull() ?: true,
                    assigneeId = q["assignee"]?.let { a -> if (a == "me") call.userId() else runCatching { UUID.fromString(a) }.getOrNull() },
                ),
            )
        }
        post {
            val b = call.receiveBody<TaskCreate>()
            if (b.title.isBlank()) badRequest("La tarea necesita un título")
            val projectId = b.projectId ?: b.project?.takeIf { it.isNotBlank() }?.let { s.tasks.findOrCreateProject(call.userId(), it).id }
            b.assigneeId?.let { s.requireMember(call.userId(), it) }
            call.respond(
                HttpStatusCode.Created,
                s.tasks.create(call.userId(), b.title, b.notes, b.priority, b.dueDate, b.dueAt, projectId, b.tags, b.status, call.source(), b.shared, b.assigneeId),
            )
        }
        get("/{id}") { call.respond(s.tasks.get(call.userId(), call.uuidParam()) ?: notFound("Tarea")) }
        patch("/{id}") {
            val b = call.receiveJsonObject()
            val patch = TaskPatch(
                title = b.str("title"), notes = b.rawStr("notes"), status = b.str("status"), priority = b.str("priority"),
                dueDate = b.str("dueDate")?.let { parseUserDate(it) }, clearDueDate = b.isNull("dueDate"),
                dueAt = b.str("dueAt")?.let { parseInstant(it) }, clearDueAt = b.isNull("dueAt"),
                // El repositorio comprueba que el proyecto sea del usuario
                projectId = b.str("projectId")?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: badRequest("projectId inválido") },
                clearProject = b.isNull("projectId"),
                tags = b.strList("tags"), sort = b.double("sort"),
                shared = b.bool("shared"),
                assigneeId = b.str("assigneeId")?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: badRequest("assigneeId inválido") },
                clearAssignee = b.isNull("assigneeId"),
            )
            patch.assigneeId?.let { s.requireMember(call.userId(), it) }
            call.respond(s.tasks.update(call.userId(), call.uuidParam(), patch, call.source()) ?: notFound("Tarea"))
        }
        delete("/{id}") { s.tasks.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound("Tarea"); call.respond(OkResponse()) }
    }
    route("/projects") {
        get { call.respond(s.tasks.projects(call.userId())) }
        post {
            val b = call.receiveBody<ProjectCreate>()
            if (b.name.isBlank()) badRequest("Nombre vacío")
            call.respond(HttpStatusCode.Created, s.tasks.createProject(call.userId(), b.name, b.color))
        }
        patch("/{id}") {
            val b = call.receiveBody<ProjectPatch>()
            call.respond(s.tasks.updateProject(call.userId(), call.uuidParam(), b.name, b.color, b.sort) ?: notFound("Proyecto"))
        }
        delete("/{id}") { if (!s.tasks.deleteProject(call.userId(), call.uuidParam())) notFound("Proyecto"); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ Recordatorios y lugares
    route("/reminders") {
        get {
            val statuses = call.request.queryParameters["status"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            call.respond(s.reminders.list(call.userId(), statuses))
        }
        post {
            val b = call.receiveBody<ReminderCreate>()
            if (b.text.isBlank()) badRequest("Texto vacío")
            if (b.dueAt == null && b.placeId == null) badRequest("Indica una fecha o un lugar")
            if (b.rrule != null && Recurrence.normalize(b.rrule) == null) badRequest("Regla de recurrencia inválida")
            val me = call.userId()
            b.forUserId?.let { s.requireMember(me, it) }
            val target = s.users.byId(b.forUserId ?: me)!!
            if (b.placeId != null && s.places.get(target.id, b.placeId) == null) badRequest("Ese lugar no está disponible para esa persona; compártelo con la familia primero")
            call.respond(
                HttpStatusCode.Created,
                s.reminders.create(target.id, b.text, b.dueAt, b.rrule, target.timezone, b.placeId, b.placeTrigger, source = call.source(), createdBy = me),
            )
        }
        get("/{id}") { call.respond(s.reminders.get(call.userId(), call.uuidParam()) ?: notFound("Recordatorio")) }
        patch("/{id}") {
            val b = call.receiveJsonObject()
            val patch = ReminderPatch(
                text = b.str("text"), dueAt = b.str("dueAt")?.let { parseInstant(it) },
                rrule = b.str("rrule"), clearRrule = b.isNull("rrule"),
                // El repositorio comprueba, como al crear, que el lugar esté disponible para quien recibe el aviso
                placeId = b.str("placeId")?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: badRequest("placeId inválido") },
                placeTrigger = b.str("placeTrigger"), clearPlace = b.isNull("placeId"),
            )
            call.respond(s.reminders.update(call.userId(), call.uuidParam(), patch, call.source()) ?: notFound("Recordatorio"))
        }
        post("/{id}/done") { call.respond(s.reminders.complete(call.userId(), call.uuidParam(), call.source()) ?: notFound("Recordatorio")) }
        post("/{id}/snooze") {
            val b = runCatching { call.receiveBody<SnoozeRequest>() }.getOrDefault(SnoozeRequest())
            call.respond(s.reminders.snooze(call.userId(), call.uuidParam(), b.minutes, call.source()) ?: notFound("Recordatorio"))
        }
        post("/{id}/cancel") { call.respond(s.reminders.cancel(call.userId(), call.uuidParam(), call.source()) ?: notFound("Recordatorio")) }
        delete("/{id}") { s.reminders.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound("Recordatorio"); call.respond(OkResponse()) }
    }
    route("/places") {
        get { call.respond(s.places.list(call.userId())) }
        post {
            val b = call.receiveBody<PlaceCreate>()
            if (b.name.isBlank()) badRequest("Nombre vacío")
            if (b.latitude !in -90.0..90.0 || b.longitude !in -180.0..180.0) badRequest("Coordenadas inválidas")
            call.respond(HttpStatusCode.Created, s.places.upsert(call.userId(), b.name, b.latitude, b.longitude, b.radiusM.coerceIn(50, 5000), b.address, call.source(), b.shared))
        }
        patch("/{id}") {
            val b = call.receiveBody<PlacePatch>()
            call.respond(s.places.setShared(call.userId(), call.uuidParam(), b.shared) ?: notFound("Lugar"))
        }
        delete("/{id}") { if (!s.places.delete(call.userId(), call.uuidParam(), call.source())) notFound("Lugar"); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ Listas
    route("/lists") {
        get { call.respond(s.lists.all(call.userId())) }
        post {
            val b = call.receiveBody<ListCreate>()
            if (b.name.isBlank()) badRequest("Nombre vacío")
            call.respond(HttpStatusCode.Created, s.lists.create(call.userId(), b.name, b.icon, call.source(), b.shared))
        }
        get("/{id}") { call.respond(s.lists.get(call.userId(), call.uuidParam()) ?: notFound("Lista")) }
        patch("/{id}") {
            val b = call.receiveBody<ListPatch>()
            call.respond(s.lists.rename(call.userId(), call.uuidParam(), b.name, b.icon, b.shared) ?: notFound("Lista"))
        }
        delete("/{id}") { s.lists.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound("Lista"); call.respond(OkResponse()) }
        post("/{id}/items") {
            val b = call.receiveBody<ItemsAdd>()
            call.respond(s.lists.addItems(call.userId(), call.uuidParam(), b.items, call.source()) ?: notFound("Lista"))
        }
        post("/{id}/clear-checked") { call.respond(s.lists.clearChecked(call.userId(), call.uuidParam(), call.source()) ?: notFound("Lista")) }
        patch("/{id}/items/{itemId}") {
            val b = call.receiveBody<ItemPatch>()
            call.respond(s.lists.updateItem(call.userId(), call.uuidParam(), call.uuidParam("itemId"), b.text, b.checked, b.sort) ?: notFound("Lista"))
        }
        delete("/{id}/items/{itemId}") {
            call.respond(s.lists.deleteItem(call.userId(), call.uuidParam(), call.uuidParam("itemId")) ?: notFound("Lista"))
        }
    }

    // ------------------------------------------------------------ Calendario
    route("/events") {
        get {
            val from = call.instantQuery("from") ?: Instant.now().minusSeconds(7L * 86400)
            val to = call.instantQuery("to") ?: from.plusSeconds(42L * 86400)
            if (to.isBefore(from)) badRequest("Rango inválido")
            call.respond(s.events.range(call.userId(), from, to))
        }
        post {
            val b = call.receiveBody<EventCreate>()
            if (b.title.isBlank()) badRequest("Título vacío")
            val user = s.users.byId(call.userId())!!
            val st = s.settings.get(user.id)
            call.respond(
                HttpStatusCode.Created,
                s.events.create(
                    user.id, b.title, b.startAt, b.endAt ?: b.startAt.plusSeconds(3600), b.allDay, user.timezone, b.description, b.location,
                    b.rrule, b.reminderMinutes ?: listOf(st.defaultEventReminderMinutes), b.color, call.source(), b.shared,
                ),
            )
        }
        get("/{id}") { call.respond(s.events.get(call.userId(), call.uuidParam()) ?: notFound("Evento")) }
        patch("/{id}") {
            val b = call.receiveJsonObject()
            val patch = EventPatch(
                title = b.str("title"), description = b.rawStr("description"), location = b.rawStr("location"),
                startAt = b.str("startAt")?.let { parseInstant(it) }, endAt = b.str("endAt")?.let { parseInstant(it) },
                allDay = b.bool("allDay"), rrule = b.str("rrule"), clearRrule = b.isNull("rrule"),
                reminderMinutes = b.intList("reminderMinutes"), color = b.str("color"), shared = b.bool("shared"),
            )
            call.respond(s.events.update(call.userId(), call.uuidParam(), patch, call.source()) ?: notFound("Evento"))
        }
        delete("/{id}") { s.events.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound("Evento"); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ Memoria
    route("/memories") {
        get { call.respond(s.memories.list(call.userId())) }
        post {
            val b = call.receiveBody<MemoryCreate>()
            if (b.content.isBlank()) badRequest("Contenido vacío")
            call.respond(HttpStatusCode.Created, s.memories.create(call.userId(), b.content, b.category, call.source()))
        }
        patch("/{id}") {
            val b = call.receiveBody<MemoryPatch>()
            call.respond(s.memories.update(call.userId(), call.uuidParam(), b.content, b.category, call.source()) ?: notFound("Recuerdo"))
        }
        delete("/{id}") { s.memories.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound("Recuerdo"); call.respond(OkResponse()) }
    }
}

/** Texto tal cual (permite vacío, a diferencia de `str`). */
private fun JsonObject.rawStr(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.isNull(key: String): Boolean = containsKey(key) && this[key] is JsonNull
