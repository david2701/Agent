package app.kairo.android.sync

import app.kairo.core.model.Notice
import app.kairo.core.model.ServerEvent
import kotlinx.serialization.json.Json

/** Aviso de la familia listo para mostrarse como notificación. */
data class NoticeAlert(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    /** Ruta de la app que abre al tocarlo ("tasks", "calendar", "family"...). */
    val destination: String,
) {
    val notificationId: Int get() = ("notice:$id").hashCode()
}

/**
 * Decodificación compartida de los eventos `{type, data}` del servidor: llegan igual por WebSocket
 * y por push (UnifiedPush entrega el contenido ya descifrado). Sin dependencias de Android.
 */
object ServerEvents {
    const val FAMILY_NOTICE = "family.notice"

    /** Mensaje de push o frame de texto -> evento; null si no es un `{type, data}` válido. */
    fun decode(json: Json, bytes: ByteArray): ServerEvent? = decode(json, bytes.decodeToString())

    fun decode(json: Json, text: String): ServerEvent? =
        runCatching { json.decodeFromString(ServerEvent.serializer(), text.trim()) }.getOrNull()?.takeIf { it.type.isNotBlank() }

    /** `family.notice` -> aviso; null si el evento es de otro tipo o le falta el id. */
    fun notice(json: Json, ev: ServerEvent): Notice? {
        if (ev.type != FAMILY_NOTICE) return null
        return runCatching { json.decodeFromJsonElement(Notice.serializer(), ev.data) }.getOrNull()?.takeIf { it.id.isNotBlank() }
    }

    fun alert(n: Notice): NoticeAlert = NoticeAlert(n.id, n.kind, n.title.ifBlank { "Familia" }, n.body, destinationFor(n.entityType))

    /** Pantalla relacionada con un aviso; lo que no tiene pantalla propia abre Familia. */
    fun destinationFor(entityType: String?): String = when (entityType) {
        "note" -> "notes"
        "task", "project" -> "tasks"
        "reminder", "place" -> "reminders"
        "list" -> "lists"
        "event" -> "calendar"
        else -> "family"
    }
}
