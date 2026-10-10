@file:UseSerializers(InstantSerializer::class, LocalDateSerializer::class, UUIDSerializer::class)

package app.kairo.domain

import app.kairo.util.InstantSerializer
import app.kairo.util.LocalDateSerializer
import app.kairo.util.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Serializable
data class User(
    val id: UUID,
    val email: String,
    val displayName: String,
    val timezone: String,
    val createdAt: Instant,
)

@Serializable
data class Note(
    val id: UUID,
    val title: String,
    val content: String,
    val tags: List<String>,
    val pinned: Boolean,
    val source: String,
    val attachments: List<FileRef> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val score: Double? = null,
)

@Serializable
data class FileRef(val id: UUID, val url: String, val contentType: String? = null)

@Serializable
data class Project(val id: UUID, val name: String, val color: String?, val sort: Double, val createdAt: Instant)

@Serializable
data class Task(
    val id: UUID,
    val title: String,
    val notes: String,
    val status: String,
    val priority: String,
    val dueDate: LocalDate?,
    val dueAt: Instant?,
    val projectId: UUID?,
    val projectName: String?,
    val tags: List<String>,
    val sort: Double,
    val source: String,
    val completedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class Place(
    val id: UUID,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int,
    val address: String?,
    val createdAt: Instant,
)

@Serializable
data class Reminder(
    val id: UUID,
    val text: String,
    val dueAt: Instant?,
    val rrule: String?,
    val timezone: String,
    val placeId: UUID?,
    val placeName: String?,
    val placeTrigger: String?,
    val status: String,
    val nextFireAt: Instant?,
    val lastFiredAt: Instant?,
    val taskId: UUID?,
    val source: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class ShoppingList(
    val id: UUID,
    val name: String,
    val icon: String?,
    val items: List<ListItem>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class ListItem(
    val id: UUID,
    val text: String,
    val checked: Boolean,
    val sort: Double,
    val createdAt: Instant,
)

@Serializable
data class Event(
    val id: UUID,
    val title: String,
    val description: String,
    val location: String,
    val startAt: Instant,
    val endAt: Instant,
    val allDay: Boolean,
    val rrule: String?,
    val timezone: String,
    val reminderMinutes: List<Int>,
    val color: String?,
    val source: String,
    val googleEventId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Aviso previo de un evento (según sus reminderMinutes). */
@Serializable
data class EventAlert(val eventId: UUID, val title: String, val startAt: Instant, val alertAt: Instant, val minutes: Int, val location: String)

@Serializable
data class Memory(
    val id: UUID,
    val content: String,
    val category: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val score: Double? = null,
)

@Serializable
data class Conversation(val id: UUID, val title: String, val createdAt: Instant, val updatedAt: Instant, val preview: String? = null)

@Serializable
data class StoredMessage(
    val id: UUID,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val meta: JsonObject? = null,
    val createdAt: Instant,
)

@Serializable
data class Recording(
    val id: UUID,
    val title: String,
    val status: String,
    val durationS: Int,
    val transcript: String,
    val summary: String,
    val keyPoints: List<String>,
    val actionItems: List<String>,
    val noteId: UUID?,
    val error: String?,
    val segments: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Serializable
data class Activity(
    val id: UUID,
    val kind: String,
    val entityType: String,
    val entityId: UUID?,
    val summary: String,
    val source: String,
    val undoable: Boolean,
    val undoneAt: Instant?,
    val createdAt: Instant,
)

@Serializable
data class Device(
    val id: UUID,
    val name: String,
    val platform: String,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val current: Boolean = false,
)

@Serializable
data class McpServer(val id: UUID, val name: String, val url: String, val enabled: Boolean, val hasHeaders: Boolean, val createdAt: Instant)

/** Referencia a una entidad afectada por una acción (para tarjetas en el chat y el log de actividad). */
@Serializable
data class EntityRef(val type: String, val id: UUID, val title: String)

object EntityTypes {
    const val NOTE = "note"
    const val TASK = "task"
    const val REMINDER = "reminder"
    const val LIST = "list"
    const val EVENT = "event"
    const val MEMORY = "memory"
    const val PLACE = "place"
    const val PROJECT = "project"
    const val RECORDING = "recording"
    const val CONVERSATION = "conversation"
}
