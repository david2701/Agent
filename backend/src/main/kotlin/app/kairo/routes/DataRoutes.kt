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
import app.kairo.util.M
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

/** Source of a change made from a client: "app" for Android/Wear and iPhone/Apple Watch, "dashboard" for the web. */
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
    /** Another family member who is being reminded. */
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

/** Checks that [id] is someone in the user's family. */
private suspend fun Services.requireMember(userId: UUID, id: UUID) {
    if (id !in family.memberIds(userId)) badRequest(M.NOT_IN_FAMILY_PERSON)
}

fun Route.dataRoutes(s: Services) {

    // ------------------------------------------------------------ Notes
    route("/notes") {
        get {
            val q = call.request.queryParameters
            call.respond(s.notes.list(call.userId(), q["q"], q["tag"], q["limit"]?.toIntOrNull() ?: 100, q["offset"]?.toIntOrNull() ?: 0))
        }
        get("/search") {
            val q = call.request.queryParameters["q"] ?: badRequest(M.MISSING_FIELD, "q")
            call.respond(s.notes.search(call.userId(), q, call.request.queryParameters["limit"]?.toIntOrNull() ?: 10))
        }
        get("/tags") { call.respond(TagsResponse(s.notes.allTags(call.userId()))) }
        post {
            val b = call.receiveBody<NoteCreate>()
            if (b.content.isBlank() && b.title.isBlank()) badRequest(M.NOTE_IS_EMPTY)
            call.respond(HttpStatusCode.Created, s.notes.create(call.userId(), b.title, b.content, b.tags, b.pinned, call.source(), shared = b.shared))
        }
        get("/{id}") { call.respond(s.notes.get(call.userId(), call.uuidParam()) ?: notFound(M.NF_NOTE)) }
        patch("/{id}") {
            val b = call.receiveJsonObject()
            val patch = NotePatch(b.rawStr("title"), b.rawStr("content"), b.strList("tags"), b.bool("pinned"), shared = b.bool("shared"))
            call.respond(s.notes.update(call.userId(), call.uuidParam(), patch, call.source()) ?: notFound(M.NF_NOTE))
        }
        delete("/{id}") { s.notes.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound(M.NF_NOTE); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ Tasks and projects
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
            if (b.title.isBlank()) badRequest(M.TASK_NEEDS_TITLE)
            val projectId = b.projectId ?: b.project?.takeIf { it.isNotBlank() }?.let { s.tasks.findOrCreateProject(call.userId(), it).id }
            b.assigneeId?.let { s.requireMember(call.userId(), it) }
            call.respond(
                HttpStatusCode.Created,
                s.tasks.create(call.userId(), b.title, b.notes, b.priority, b.dueDate, b.dueAt, projectId, b.tags, b.status, call.source(), b.shared, b.assigneeId),
            )
        }
        get("/{id}") { call.respond(s.tasks.get(call.userId(), call.uuidParam()) ?: notFound(M.NF_TASK)) }
        patch("/{id}") {
            val b = call.receiveJsonObject()
            val patch = TaskPatch(
                title = b.str("title"), notes = b.rawStr("notes"), status = b.str("status"), priority = b.str("priority"),
                dueDate = b.str("dueDate")?.let { parseUserDate(it) }, clearDueDate = b.isNull("dueDate"),
                dueAt = b.str("dueAt")?.let { parseInstant(it) }, clearDueAt = b.isNull("dueAt"),
                // The repository checks that the project is the user's
                projectId = b.str("projectId")?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: badRequest(M.INVALID_FIELD, "projectId") },
                clearProject = b.isNull("projectId"),
                tags = b.strList("tags"), sort = b.double("sort"),
                shared = b.bool("shared"),
                assigneeId = b.str("assigneeId")?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: badRequest(M.INVALID_FIELD, "assigneeId") },
                clearAssignee = b.isNull("assigneeId"),
            )
            patch.assigneeId?.let { s.requireMember(call.userId(), it) }
            call.respond(s.tasks.update(call.userId(), call.uuidParam(), patch, call.source()) ?: notFound(M.NF_TASK))
        }
        delete("/{id}") { s.tasks.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound(M.NF_TASK); call.respond(OkResponse()) }
    }
    route("/projects") {
        get { call.respond(s.tasks.projects(call.userId())) }
        post {
            val b = call.receiveBody<ProjectCreate>()
            if (b.name.isBlank()) badRequest(M.EMPTY_NAME)
            call.respond(HttpStatusCode.Created, s.tasks.createProject(call.userId(), b.name, b.color))
        }
        patch("/{id}") {
            val b = call.receiveBody<ProjectPatch>()
            call.respond(s.tasks.updateProject(call.userId(), call.uuidParam(), b.name, b.color, b.sort) ?: notFound(M.NF_PROJECT))
        }
        delete("/{id}") { if (!s.tasks.deleteProject(call.userId(), call.uuidParam())) notFound(M.NF_PROJECT); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ Reminders and places
    route("/reminders") {
        get {
            val statuses = call.request.queryParameters["status"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            call.respond(s.reminders.list(call.userId(), statuses))
        }
        post {
            val b = call.receiveBody<ReminderCreate>()
            if (b.text.isBlank()) badRequest(M.EMPTY_TEXT)
            if (b.dueAt == null && b.placeId == null) badRequest(M.NEED_DATE_OR_PLACE)
            if (b.rrule != null && Recurrence.normalize(b.rrule) == null) badRequest(M.INVALID_RRULE)
            val me = call.userId()
            b.forUserId?.let { s.requireMember(me, it) }
            val target = s.users.byId(b.forUserId ?: me)!!
            if (b.placeId != null && s.places.get(target.id, b.placeId) == null) badRequest(M.PLACE_NOT_AVAILABLE)
            call.respond(
                HttpStatusCode.Created,
                s.reminders.create(target.id, b.text, b.dueAt, b.rrule, target.timezone, b.placeId, b.placeTrigger, source = call.source(), createdBy = me),
            )
        }
        get("/{id}") { call.respond(s.reminders.get(call.userId(), call.uuidParam()) ?: notFound(M.NF_REMINDER)) }
        patch("/{id}") {
            val b = call.receiveJsonObject()
            val patch = ReminderPatch(
                text = b.str("text"), dueAt = b.str("dueAt")?.let { parseInstant(it) },
                rrule = b.str("rrule"), clearRrule = b.isNull("rrule"),
                // The repository checks, as on create, that the place is available to whoever receives the reminder
                placeId = b.str("placeId")?.let { runCatching { UUID.fromString(it) }.getOrNull() ?: badRequest(M.INVALID_FIELD, "placeId") },
                placeTrigger = b.str("placeTrigger"), clearPlace = b.isNull("placeId"),
            )
            call.respond(s.reminders.update(call.userId(), call.uuidParam(), patch, call.source()) ?: notFound(M.NF_REMINDER))
        }
        post("/{id}/done") { call.respond(s.reminders.complete(call.userId(), call.uuidParam(), call.source()) ?: notFound(M.NF_REMINDER)) }
        post("/{id}/snooze") {
            val b = runCatching { call.receiveBody<SnoozeRequest>() }.getOrDefault(SnoozeRequest())
            call.respond(s.reminders.snooze(call.userId(), call.uuidParam(), b.minutes, call.source()) ?: notFound(M.NF_REMINDER))
        }
        post("/{id}/cancel") { call.respond(s.reminders.cancel(call.userId(), call.uuidParam(), call.source()) ?: notFound(M.NF_REMINDER)) }
        delete("/{id}") { s.reminders.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound(M.NF_REMINDER); call.respond(OkResponse()) }
    }
    route("/places") {
        get { call.respond(s.places.list(call.userId())) }
        post {
            val b = call.receiveBody<PlaceCreate>()
            if (b.name.isBlank()) badRequest(M.EMPTY_NAME)
            if (b.latitude !in -90.0..90.0 || b.longitude !in -180.0..180.0) badRequest(M.INVALID_COORDINATES)
            call.respond(HttpStatusCode.Created, s.places.upsert(call.userId(), b.name, b.latitude, b.longitude, b.radiusM.coerceIn(50, 5000), b.address, call.source(), b.shared))
        }
        patch("/{id}") {
            val b = call.receiveBody<PlacePatch>()
            call.respond(s.places.setShared(call.userId(), call.uuidParam(), b.shared) ?: notFound(M.NF_PLACE))
        }
        delete("/{id}") { if (!s.places.delete(call.userId(), call.uuidParam(), call.source())) notFound(M.NF_PLACE); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ Lists
    route("/lists") {
        get { call.respond(s.lists.all(call.userId())) }
        post {
            val b = call.receiveBody<ListCreate>()
            if (b.name.isBlank()) badRequest(M.EMPTY_NAME)
            call.respond(HttpStatusCode.Created, s.lists.create(call.userId(), b.name, b.icon, call.source(), b.shared))
        }
        get("/{id}") { call.respond(s.lists.get(call.userId(), call.uuidParam()) ?: notFound(M.NF_LIST)) }
        patch("/{id}") {
            val b = call.receiveBody<ListPatch>()
            call.respond(s.lists.rename(call.userId(), call.uuidParam(), b.name, b.icon, b.shared) ?: notFound(M.NF_LIST))
        }
        delete("/{id}") { s.lists.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound(M.NF_LIST); call.respond(OkResponse()) }
        post("/{id}/items") {
            val b = call.receiveBody<ItemsAdd>()
            call.respond(s.lists.addItems(call.userId(), call.uuidParam(), b.items, call.source()) ?: notFound(M.NF_LIST))
        }
        post("/{id}/clear-checked") { call.respond(s.lists.clearChecked(call.userId(), call.uuidParam(), call.source()) ?: notFound(M.NF_LIST)) }
        patch("/{id}/items/{itemId}") {
            val b = call.receiveBody<ItemPatch>()
            call.respond(s.lists.updateItem(call.userId(), call.uuidParam(), call.uuidParam("itemId"), b.text, b.checked, b.sort) ?: notFound(M.NF_LIST))
        }
        delete("/{id}/items/{itemId}") {
            call.respond(s.lists.deleteItem(call.userId(), call.uuidParam(), call.uuidParam("itemId")) ?: notFound(M.NF_LIST))
        }
    }

    // ------------------------------------------------------------ Calendar
    route("/events") {
        get {
            val from = call.instantQuery("from") ?: Instant.now().minusSeconds(7L * 86400)
            val to = call.instantQuery("to") ?: from.plusSeconds(42L * 86400)
            if (to.isBefore(from)) badRequest(M.INVALID_RANGE)
            call.respond(s.events.range(call.userId(), from, to))
        }
        post {
            val b = call.receiveBody<EventCreate>()
            if (b.title.isBlank()) badRequest(M.EMPTY_TITLE)
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
        get("/{id}") { call.respond(s.events.get(call.userId(), call.uuidParam()) ?: notFound(M.NF_EVENT)) }
        patch("/{id}") {
            val b = call.receiveJsonObject()
            val patch = EventPatch(
                title = b.str("title"), description = b.rawStr("description"), location = b.rawStr("location"),
                startAt = b.str("startAt")?.let { parseInstant(it) }, endAt = b.str("endAt")?.let { parseInstant(it) },
                allDay = b.bool("allDay"), rrule = b.str("rrule"), clearRrule = b.isNull("rrule"),
                reminderMinutes = b.intList("reminderMinutes"), color = b.str("color"), shared = b.bool("shared"),
            )
            call.respond(s.events.update(call.userId(), call.uuidParam(), patch, call.source()) ?: notFound(M.NF_EVENT))
        }
        delete("/{id}") { s.events.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound(M.NF_EVENT); call.respond(OkResponse()) }
    }

    // ------------------------------------------------------------ Memory
    route("/memories") {
        get { call.respond(s.memories.list(call.userId())) }
        post {
            val b = call.receiveBody<MemoryCreate>()
            if (b.content.isBlank()) badRequest(M.EMPTY_CONTENT)
            call.respond(HttpStatusCode.Created, s.memories.create(call.userId(), b.content, b.category, call.source()))
        }
        patch("/{id}") {
            val b = call.receiveBody<MemoryPatch>()
            call.respond(s.memories.update(call.userId(), call.uuidParam(), b.content, b.category, call.source()) ?: notFound(M.NF_MEMORY))
        }
        delete("/{id}") { s.memories.delete(call.userId(), call.uuidParam(), call.source()) ?: notFound(M.NF_MEMORY); call.respond(OkResponse()) }
    }
}

/** Text as is (allows empty, unlike `str`). */
private fun JsonObject.rawStr(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is JsonNull }?.content

private fun JsonObject.isNull(key: String): Boolean = containsKey(key) && this[key] is JsonNull
