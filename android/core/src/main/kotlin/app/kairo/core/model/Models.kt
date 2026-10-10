package app.kairo.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Modelos que reflejan docs/API.md. Las fechas viajan como texto ISO-8601 y se convierten
// con las extensiones de Time.kt cuando la UI las necesita.

@Serializable
data class User(
    val id: String,
    val email: String,
    val displayName: String,
    val timezone: String,
    val createdAt: String,
)

@Serializable
data class AuthResponse(
    val user: User,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresIn: Long,
    val deviceId: String,
)

@Serializable
data class FileRef(val id: String, val url: String, val contentType: String? = null)

@Serializable
data class Note(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val source: String = "",
    val attachments: List<FileRef> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val score: Double? = null,
)

@Serializable
data class Project(val id: String, val name: String, val color: String? = null, val sort: Double = 0.0, val createdAt: String)

@Serializable
data class Task(
    val id: String,
    val title: String,
    val notes: String = "",
    val status: String = "todo",
    val priority: String = "medium",
    val dueDate: String? = null,
    val dueAt: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    val tags: List<String> = emptyList(),
    val sort: Double = 0.0,
    val source: String = "",
    val completedAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class Place(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Int = 150,
    val address: String? = null,
    val createdAt: String,
)

@Serializable
data class Reminder(
    val id: String,
    val text: String,
    val dueAt: String? = null,
    val rrule: String? = null,
    val timezone: String = "UTC",
    val placeId: String? = null,
    val placeName: String? = null,
    val placeTrigger: String? = null,
    val status: String = "scheduled",
    val nextFireAt: String? = null,
    val lastFiredAt: String? = null,
    val taskId: String? = null,
    val source: String = "",
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ListItem(val id: String, val text: String, val checked: Boolean, val sort: Double = 0.0, val createdAt: String)

@Serializable
data class ShoppingList(
    val id: String,
    val name: String,
    val icon: String? = null,
    val items: List<ListItem> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class Event(
    val id: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startAt: String,
    val endAt: String,
    val allDay: Boolean = false,
    val rrule: String? = null,
    val timezone: String = "UTC",
    val reminderMinutes: List<Int> = emptyList(),
    val color: String? = null,
    val source: String = "kairo",
    val googleEventId: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class Memory(val id: String, val content: String, val category: String, val createdAt: String, val updatedAt: String)

@Serializable
data class Conversation(val id: String, val title: String, val createdAt: String, val updatedAt: String, val preview: String? = null)

@Serializable
data class StoredMessage(
    val id: String,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val meta: JsonObject? = null,
    val createdAt: String,
)

@Serializable
data class Recording(
    val id: String,
    val title: String,
    val status: String,
    val durationS: Int = 0,
    val transcript: String = "",
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val noteId: String? = null,
    val error: String? = null,
    val segments: Int = 0,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class Activity(
    val id: String,
    val kind: String,
    val entityType: String,
    val entityId: String? = null,
    val summary: String,
    val source: String,
    val undoable: Boolean,
    val undoneAt: String? = null,
    val createdAt: String,
)

@Serializable
data class Device(val id: String, val name: String, val platform: String, val createdAt: String, val lastSeenAt: String, val current: Boolean = false)

@Serializable
data class EntityRef(val type: String, val id: String, val title: String)

@Serializable
data class WeatherDay(val date: String, val min: Double, val max: Double, val rainChance: Int? = null, val description: String)

@Serializable
data class WeatherReport(val place: String, val temperature: Double, val feelsLike: Double? = null, val description: String, val days: List<WeatherDay> = emptyList())

@Serializable
data class TodayData(
    val date: String,
    val dateLabel: String,
    val greeting: String,
    val events: List<Event> = emptyList(),
    val tasksDueToday: List<Task> = emptyList(),
    val tasksOverdue: List<Task> = emptyList(),
    val tasksInProgress: List<Task> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val firedReminders: List<Reminder> = emptyList(),
    val recentNotes: List<Note> = emptyList(),
    val lists: List<ShoppingList> = emptyList(),
    val weather: WeatherReport? = null,
)

@Serializable
data class UserSettings(
    val chatModel: String = "deepseek-v4-flash",
    val fallbackModel: String = "qwen3.8-flash",
    val visionModel: String = "gemma4",
    val imageModel: String = "flux-2-klein",
    val fastMode: Boolean = true,
    val voice: String = "ef_dora",
    val voiceSpeed: Double = 1.0,
    val sttModel: String = "whisper",
    val language: String = "es",
    val wakeWord: String = "oye kairo",
    val briefingEnabled: Boolean = true,
    val briefingTime: String = "07:00",
    val lastBriefingDate: String? = null,
    val homeCity: String? = null,
    val homeLatitude: Double? = null,
    val homeLongitude: Double? = null,
    val ntfyTopic: String? = null,
    val googleCalendarId: String = "primary",
    val defaultEventReminderMinutes: Int = 15,
    val keepRecordingAudio: Boolean = false,
)

@Serializable
data class SettingsResponse(val settings: UserSettings, val googleAvailable: Boolean = false, val ntfyAvailable: Boolean = false)

@Serializable
data class VoiceOption(val id: String, val name: String, val description: String)

@Serializable
data class VoicesResponse(val voices: List<VoiceOption>)

@Serializable
data class DeviceSync(val serverTime: String, val reminders: List<Reminder> = emptyList(), val places: List<Place> = emptyList())

@Serializable
data class BriefingResponse(val text: String)

@Serializable
data class ApiError(val error: String)

/** Evento del WebSocket. */
@Serializable
data class ServerEvent(val type: String, val data: JsonObject = JsonObject(emptyMap()))

/** Eventos SSE del agente (ver docs/API.md). */
@Serializable
sealed class AgentEvent {
    @Serializable @SerialName("start") data class Start(val conversationId: String) : AgentEvent()
    @Serializable @SerialName("transcript") data class Transcript(val text: String) : AgentEvent()
    @Serializable @SerialName("delta") data class Delta(val text: String, val step: Int = 0) : AgentEvent()
    @Serializable @SerialName("tool_start") data class ToolStart(val callId: String, val name: String, val label: String) : AgentEvent()
    @Serializable @SerialName("tool_end") data class ToolEnd(val callId: String, val name: String, val ok: Boolean, val summary: String, val entity: EntityRef? = null) : AgentEvent()
    @Serializable @SerialName("device_action") data class DeviceAction(val action: JsonObject) : AgentEvent()
    @Serializable @SerialName("attachment") data class Attachment(val file: FileRef) : AgentEvent()
    @Serializable @SerialName("done") data class Done(val conversationId: String, val messageId: String, val text: String, val spoken: String) : AgentEvent()
    @Serializable @SerialName("error") data class Error(val message: String) : AgentEvent()
}
