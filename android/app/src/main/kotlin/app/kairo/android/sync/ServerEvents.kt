package app.kairo.android.sync

import app.kairo.core.model.Notice
import app.kairo.core.model.ServerEvent
import kotlinx.serialization.json.Json

/** Family notice ready to be shown as a notification. */
data class NoticeAlert(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    /** App route it opens when tapped ("tasks", "calendar", "family"...). */
    val destination: String,
) {
    val notificationId: Int get() = ("notice:$id").hashCode()
}

/**
 * Shared decoding of the server's `{type, data}` events: they arrive the same way over WebSocket
 * and push (UnifiedPush delivers the already decrypted content). No Android dependencies.
 */
object ServerEvents {
    const val FAMILY_NOTICE = "family.notice"

    /** Push message or text frame -> event; null if it is not a valid `{type, data}`. */
    fun decode(json: Json, bytes: ByteArray): ServerEvent? = decode(json, bytes.decodeToString())

    fun decode(json: Json, text: String): ServerEvent? =
        runCatching { json.decodeFromString(ServerEvent.serializer(), text.trim()) }.getOrNull()?.takeIf { it.type.isNotBlank() }

    /** `family.notice` -> notice; null if the event is another type or lacks the id. */
    fun notice(json: Json, ev: ServerEvent): Notice? {
        if (ev.type != FAMILY_NOTICE) return null
        return runCatching { json.decodeFromJsonElement(Notice.serializer(), ev.data) }.getOrNull()?.takeIf { it.id.isNotBlank() }
    }

    fun alert(n: Notice): NoticeAlert = NoticeAlert(n.id, n.kind, n.title, n.body, destinationFor(n.entityType))

    /** Screen related to a notice; anything without its own screen opens Family. */
    fun destinationFor(entityType: String?): String = when (entityType) {
        "note" -> "notes"
        "task", "project" -> "tasks"
        "reminder", "place" -> "reminders"
        "list" -> "lists"
        "event" -> "calendar"
        else -> "family"
    }
}
