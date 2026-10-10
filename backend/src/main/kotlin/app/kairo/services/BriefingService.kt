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
import app.kairo.util.Lang
import app.kairo.util.M
import app.kairo.util.humanDate
import app.kairo.util.humanTime
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

/** Builds the day overview and writes the morning summary. */
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
        // Family tasks assigned to someone else are not this user's pending items
        val allTasks = tasks.list(user.id, includeDone = false).filter { it.assigneeId == null || it.assigneeId == user.id }
        val open = allTasks.filter { it.status != "done" }
        val rem = reminders.list(user.id, listOf("scheduled", "fired"))
        val lang = settings.langOf(s)
        val w = if (s.homeLatitude != null && s.homeLongitude != null) {
            weather.forecast(s.homeLatitude, s.homeLongitude, s.homeCity ?: M.DEFAULT_HOME_NAME.t(lang), lang = lang)
        } else null
        val hour = now.atZone(zone).hour
        return TodayData(
            date = day.toString(),
            dateLabel = humanDate(day, lang = lang).replaceFirstChar { it.uppercase() },
            greeting = when (hour) { in 5..11 -> M.GREETING_MORNING.t(lang); in 12..18 -> M.GREETING_AFTERNOON.t(lang); else -> M.GREETING_EVENING.t(lang) },
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

    /** Short text, fit to be read aloud, written in the user's language. */
    suspend fun generate(user: User): String {
        val data = today(user)
        val s = settings.get(user.id)
        val lang = settings.langOf(s)
        val zone = zoneOf(user.timezone)
        // The data block is for the model (English labels); dates and times inside it follow the user's language
        val compact = buildString {
            appendLine("Date: ${data.dateLabel}")
            data.weather?.let { w ->
                val d = w.days.firstOrNull()
                appendLine("Weather in ${w.place}: ${w.description}, ${w.temperature.toInt()} degrees" + (d?.let { ", high ${it.max.toInt()}, low ${it.min.toInt()}, rain ${it.rainChance ?: 0}%" } ?: ""))
            }
            appendLine("Events: " + data.events.joinToString("; ") { e -> if (e.allDay) "${e.title} (all day)" else "${e.title} at ${humanTime(e.startAt, zone, lang)}" }.ifEmpty { "none" })
            appendLine("Tasks due today: " + data.tasksDueToday.joinToString("; ") { it.title }.ifEmpty { "none" })
            appendLine("Overdue tasks: " + data.tasksOverdue.joinToString("; ") { it.title }.ifEmpty { "none" })
            appendLine("In progress: " + data.tasksInProgress.joinToString("; ") { it.title }.ifEmpty { "none" })
            appendLine("Today's reminders: " + data.reminders.joinToString("; ") { r -> r.text + (r.nextFireAt?.let { " at " + humanTime(it, zone, lang) } ?: "") }.ifEmpty { "none" })
        }
        val result = nan.chat(
            listOf(
                ChatMessage.system(
                    "You are Kairo, ${user.displayName}'s personal assistant. Write the morning summary in ${lang.englishName}, " +
                        "in a single paragraph of at most 90 words, warm and direct, with no markdown, no lists and no emojis. " +
                        "Start with a short greeting. Mention the most important things first (timed events and overdue tasks). " +
                        "If there is nothing, say so naturally.",
                ),
                ChatMessage.user(compact),
            ),
            opts = ChatOptions(model = s.chatModel, maxTokens = 400, reasoningEffort = "none"),
        )
        return result.message.text.trim().ifEmpty { M.BRIEFING_NOTHING.t(lang, data.greeting) }
    }
}
