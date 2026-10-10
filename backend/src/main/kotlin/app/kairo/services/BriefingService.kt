package app.kairo.services

import app.kairo.domain.Event
import app.kairo.domain.EventsRepo
import app.kairo.domain.Note
import app.kairo.domain.NotesRepo
import app.kairo.domain.Reminder
import app.kairo.domain.RemindersRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.ShoppingList
import app.kairo.domain.ListsRepo
import app.kairo.domain.Task
import app.kairo.domain.TasksRepo
import app.kairo.domain.User
import app.kairo.integrations.WeatherReport
import app.kairo.integrations.WeatherService
import app.kairo.nan.ChatMessage
import app.kairo.nan.ChatOptions
import app.kairo.nan.NanClient
import app.kairo.util.humanDate
import app.kairo.util.zoneOf
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

@Serializable
data class TodayData(
    val date: String,
    val dateLabel: String,
    val greeting: String,
    val events: List<Event>,
    val tasksDueToday: List<Task>,
    val tasksOverdue: List<Task>,
    val tasksInProgress: List<Task>,
    val reminders: List<Reminder>,
    val firedReminders: List<Reminder>,
    val recentNotes: List<Note>,
    val lists: List<ShoppingList>,
    val weather: WeatherReport?,
)

/** Arma el panorama del día y redacta el resumen matutino. */
class BriefingService(
    private val events: EventsRepo,
    private val tasks: TasksRepo,
    private val reminders: RemindersRepo,
    private val notes: NotesRepo,
    private val lists: ListsRepo,
    private val weather: WeatherService,
    private val settings: SettingsService,
    private val nan: NanClient,
) {

    suspend fun today(user: User, date: LocalDate? = null): TodayData {
        val zone = zoneOf(user.timezone)
        val now = Instant.now()
        val day = date ?: now.atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        val s = settings.get(user.id)
        // Las tareas familiares asignadas a otra persona no son pendientes de este usuario
        val allTasks = tasks.list(user.id, includeDone = false).filter { it.assigneeId == null || it.assigneeId == user.id }
        val open = allTasks.filter { it.status != "done" }
        val rem = reminders.list(user.id, listOf("scheduled", "fired"))
        val w = if (s.homeLatitude != null && s.homeLongitude != null) {
            weather.forecast(s.homeLatitude, s.homeLongitude, s.homeCity ?: "Casa")
        } else null
        val hour = now.atZone(zone).hour
        return TodayData(
            date = day.toString(),
            dateLabel = humanDate(day).replaceFirstChar { it.uppercase() },
            greeting = when (hour) { in 5..11 -> "Buenos días"; in 12..18 -> "Buenas tardes"; else -> "Buenas noches" },
            events = events.range(user.id, start, end),
            tasksDueToday = open.filter { it.dueDate == day || (it.dueAt != null && it.dueAt >= start && it.dueAt < end) },
            tasksOverdue = open.filter { (it.dueDate != null && it.dueDate < day) || (it.dueAt != null && it.dueAt < start) },
            tasksInProgress = open.filter { it.status == "doing" },
            reminders = rem.filter { it.status == "scheduled" && it.nextFireAt != null && it.nextFireAt < end }.take(20),
            firedReminders = rem.filter { it.status == "fired" }.take(10),
            recentNotes = notes.list(user.id, limit = 6),
            lists = lists.all(user.id).filter { l -> l.items.any { !it.checked } }.take(4),
            weather = w,
        )
    }

    /** Texto breve, apto para leerse en voz alta. */
    suspend fun generate(user: User): String {
        val data = today(user)
        val s = settings.get(user.id)
        val compact = buildString {
            appendLine("Fecha: ${data.dateLabel}")
            data.weather?.let { w ->
                val d = w.days.firstOrNull()
                appendLine("Clima en ${w.place}: ${w.description}, ${w.temperature.toInt()} grados" + (d?.let { ", máxima ${it.max.toInt()}, mínima ${it.min.toInt()}, lluvia ${it.rainChance ?: 0}%" } ?: ""))
            }
            appendLine("Eventos: " + data.events.joinToString("; ") { e -> if (e.allDay) "${e.title} (todo el día)" else "${e.title} a las ${app.kairo.util.humanTime(e.startAt, zoneOf(user.timezone))}" }.ifEmpty { "ninguno" })
            appendLine("Tareas para hoy: " + data.tasksDueToday.joinToString("; ") { it.title }.ifEmpty { "ninguna" })
            appendLine("Tareas vencidas: " + data.tasksOverdue.joinToString("; ") { it.title }.ifEmpty { "ninguna" })
            appendLine("En curso: " + data.tasksInProgress.joinToString("; ") { it.title }.ifEmpty { "ninguna" })
            appendLine("Recordatorios de hoy: " + data.reminders.joinToString("; ") { r -> r.text + (r.nextFireAt?.let { " a las " + app.kairo.util.humanTime(it, zoneOf(user.timezone)) } ?: "") }.ifEmpty { "ninguno" })
        }
        val result = nan.chat(
            listOf(
                ChatMessage.system(
                    "Eres Kairo, asistente personal de ${user.displayName}. Redacta el resumen de la mañana en español, " +
                        "en un solo párrafo de máximo 90 palabras, cálido y directo, sin markdown, sin listas y sin emojis. " +
                        "Empieza con un saludo breve. Menciona primero lo más importante (eventos con hora y tareas vencidas). " +
                        "Si no hay nada, dilo con naturalidad.",
                ),
                ChatMessage.user(compact),
            ),
            opts = ChatOptions(model = s.chatModel, maxTokens = 400, reasoningEffort = "none"),
        )
        return result.message.text.trim().ifEmpty { "${data.greeting}. Hoy no tienes nada programado." }
    }
}
