package app.kairo.android

import app.kairo.core.model.Activity
import app.kairo.core.model.Conversation
import app.kairo.core.model.Device
import app.kairo.core.model.Event
import app.kairo.core.model.FamilyInfo
import app.kairo.core.model.Household
import app.kairo.core.model.Invitation
import app.kairo.core.model.InviteCreated
import app.kairo.core.model.InvitePreview
import app.kairo.core.model.ListItem
import app.kairo.core.model.Member
import app.kairo.core.model.Memory
import app.kairo.core.model.Note
import app.kairo.core.model.Notice
import app.kairo.core.model.NoticesResponse
import app.kairo.core.model.Place
import app.kairo.core.model.Project
import app.kairo.core.model.PushConfig
import app.kairo.core.model.Reminder
import app.kairo.core.model.SettingsResponse
import app.kairo.core.model.ShoppingList
import app.kairo.core.model.Task
import app.kairo.core.model.TodayData
import app.kairo.core.model.User
import app.kairo.core.model.UserSettings
import app.kairo.core.model.VoiceOption
import app.kairo.core.model.VoicesResponse
import app.kairo.core.model.WeatherDay
import app.kairo.core.model.WeatherReport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Sample data for the screenshots, in English, served by a tiny in-process HTTP server so the
 * screenshots do not need a real backend. Dates are relative to "today" so the images always look current.
 */
object ScreenshotFixtures {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val today: LocalDate get() = LocalDate.now(zone)
    private val now: Instant get() = Instant.now()

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): String = date.atTime(hour, minute).atZone(zone).toInstant().toString()
    private fun ago(minutes: Long): String = now.minusSeconds(minutes * 60).toString()

    val user = User("u-alex", "alex@example.com", "Alex Rivera", "America/Bogota", ago(60 * 24 * 90), householdId = "h1", role = "admin")
    private const val SAM = "u-sam"
    private const val SOFIA = "u-mia"

    private val family = FamilyInfo(
        Household("h1", "Rivera family", ago(60 * 24 * 90)),
        listOf(
            Member(user.id, "Alex Rivera", user.email, "admin", ago(60 * 24 * 90)),
            Member(SAM, "Sam Rivera", "sam@example.com", "member", ago(60 * 24 * 80)),
            Member(SOFIA, "Mia Rivera", "mia@example.com", "member", ago(60 * 24 * 30)),
        ),
        me = user.id, isAdmin = true,
    )

    private val reminderCall = Reminder("r2", "Call the dentist", at(today.plusDays(1), 9), nextFireAt = at(today.plusDays(1), 9), createdAt = ago(300), updatedAt = ago(300), ownerId = user.id)
    private val reminderPickup = Reminder("r5", "Pick up the dry cleaning", placeId = "p3", placeName = "Supermarket", placeTrigger = "arrive", createdAt = ago(500), updatedAt = ago(500), ownerId = user.id)
    private val reminderFired = Reminder("r1", "Take out the trash", at(today, 8), status = "fired", lastFiredAt = at(today, 8), createdAt = ago(900), updatedAt = ago(30), ownerId = user.id)
    private val reminderToday = Reminder("r6", "Send the invoice before lunch", at(today, 15), nextFireAt = at(today, 15), createdAt = ago(100), updatedAt = ago(100), ownerId = user.id)

    private val reminders = listOf(
        reminderFired,
        reminderToday,
        reminderCall,
        Reminder("r3", "Pay rent", at(today.plusDays(5), 9), rrule = "FREQ=MONTHLY;BYMONTHDAY=${today.plusDays(5).dayOfMonth}", nextFireAt = at(today.plusDays(5), 9), createdAt = ago(2000), updatedAt = ago(2000), ownerId = user.id),
        Reminder("r4", "Water the plants", at(today.plusDays(2), 18), rrule = "FREQ=WEEKLY;BYDAY=MO,WE,FR", nextFireAt = at(today.plusDays(2), 18), createdAt = ago(2000), updatedAt = ago(2000), ownerId = user.id),
        Reminder("r7", "Buy Mia's school supplies", at(today.plusDays(3), 17), nextFireAt = at(today.plusDays(3), 17), createdAt = ago(300), updatedAt = ago(300), ownerId = SOFIA, createdBy = user.id),
        reminderPickup,
        Reminder("r8", "Renew the gym membership", at(today.minusDays(2), 10), status = "done", createdAt = ago(5000), updatedAt = ago(2900), ownerId = user.id),
    )

    private val places = listOf(
        Place("p1", "Home", 6.2442, -75.5812, 150, createdAt = ago(9000), ownerId = user.id, shared = true),
        Place("p2", "Work", 6.2086, -75.5714, 200, createdAt = ago(9000), ownerId = user.id),
        Place("p3", "Supermarket", 6.2301, -75.5902, 150, createdAt = ago(9000), ownerId = SAM, shared = true),
    )

    private val tasks = listOf(
        Task("t1", "Renew car insurance", status = "todo", priority = "high", dueDate = today.minusDays(1).toString(), projectName = "Home", createdAt = ago(4000), updatedAt = ago(4000), ownerId = user.id),
        Task("t2", "Send invoice to the accountant", status = "todo", priority = "urgent", dueDate = today.toString(), projectName = "Work", createdAt = ago(3000), updatedAt = ago(3000), ownerId = user.id),
        Task("t3", "Pick up Mia from school", status = "todo", priority = "medium", dueDate = today.toString(), shared = true, assigneeId = user.id, createdAt = ago(900), updatedAt = ago(900), ownerId = SAM),
        Task("t4", "Book the family trip", status = "todo", priority = "low", dueDate = today.plusDays(6).toString(), projectName = "Home", shared = true, createdAt = ago(1500), updatedAt = ago(1500), ownerId = user.id),
        Task("t5", "Prepare the Q4 budget", status = "doing", priority = "high", dueDate = today.plusDays(2).toString(), projectName = "Work", createdAt = ago(5000), updatedAt = ago(200), ownerId = user.id),
        Task("t6", "Review the pull request", status = "doing", priority = "medium", projectName = "Work", createdAt = ago(2500), updatedAt = ago(120), ownerId = user.id),
        Task("t7", "Call the plumber", status = "done", priority = "medium", projectName = "Home", completedAt = ago(600), createdAt = ago(6000), updatedAt = ago(600), ownerId = user.id),
        Task("t8", "Update the website copy", status = "done", priority = "low", projectName = "Work", completedAt = ago(1200), createdAt = ago(7000), updatedAt = ago(1200), ownerId = user.id),
    )
    private val projects = listOf(Project("pr1", "Home", createdAt = ago(9000)), Project("pr2", "Work", createdAt = ago(9000)))

    private val notes = listOf(
        Note("n1", "Plumber visit", "The plumber comes on Friday between 10 and 12.\n\n- Clear the space under the sink\n- Ask about the water heater", listOf("home"), pinned = true, createdAt = ago(300), updatedAt = ago(12), ownerId = user.id),
        Note("n2", "Family trip ideas", "## Destinations\n- Cartagena\n- Santa Marta\n- Guatape\n\nBudget: **$1,500** for the whole family.", listOf("family", "travel"), shared = true, createdAt = ago(2000), updatedAt = ago(95), ownerId = user.id),
        Note("n3", "Meeting notes: Q4 planning", "Priorities for the quarter:\n1. Launch the new onboarding\n2. Reduce support tickets\n3. Hire a designer", listOf("work"), createdAt = ago(4000), updatedAt = ago(60 * 26), ownerId = user.id),
        Note("n4", "Gift ideas for Sam", "A good book, the blue scarf, tickets to the concert.", listOf("family"), createdAt = ago(8000), updatedAt = ago(60 * 24 * 4), ownerId = user.id),
    )

    private val lists = listOf(
        ShoppingList(
            "l1", "Groceries",
            items = listOf(
                ListItem("i1", "Milk", false, createdAt = ago(200)), ListItem("i2", "Eggs", false, createdAt = ago(200)),
                ListItem("i3", "Bread", false, createdAt = ago(200)), ListItem("i4", "Tomatoes", false, createdAt = ago(200)),
                ListItem("i5", "Coffee", true, createdAt = ago(200)),
            ),
            createdAt = ago(3000), updatedAt = ago(40), shared = true, ownerId = SAM,
        ),
        ShoppingList("l2", "Pharmacy", items = listOf(ListItem("i6", "Vitamin D", false, createdAt = ago(200)), ListItem("i7", "Band-aids", false, createdAt = ago(200))), createdAt = ago(3000), updatedAt = ago(300), ownerId = user.id),
        ShoppingList("l3", "Weekend trip", items = listOf(ListItem("i8", "Sunscreen", true, createdAt = ago(200)), ListItem("i9", "Charger", true, createdAt = ago(200))), createdAt = ago(3000), updatedAt = ago(900), ownerId = user.id),
    )

    private val memories = listOf(
        Memory("m1", "My name is Alex and I live in Denver", "perfil", ago(9000), ago(9000)),
        Memory("m2", "My wife is Sam and my daughter is Mia", "personas", ago(9000), ago(9000)),
        Memory("m3", "I prefer meetings after 10 am", "preferencias", ago(9000), ago(9000)),
        Memory("m4", "Allergic to penicillin", "salud", ago(9000), ago(9000)),
        Memory("m5", "I work on a product team and use the calendar for everything", "trabajo", ago(9000), ago(9000)),
        Memory("m6", "The water heater was serviced in March", "hogar", ago(9000), ago(9000)),
    )

    private val conversations = listOf(
        Conversation("c1", "Note that the plumber comes Friday", ago(240), ago(15), "Done, I saved the note."),
        Conversation("c2", "What is on my schedule tomorrow?", ago(2000), ago(60 * 5), "Tomorrow you have a stand-up at 9:30 and lunch with Sam."),
        Conversation("c3", "Add milk and eggs to the shopping list", ago(3000), ago(60 * 26), "I added 2 items to Groceries."),
        Conversation("c4", "Remind me to call the dentist", ago(5000), ago(60 * 24 * 3), "Reminder set for tomorrow at 9:00 AM."),
    )

    private val activity = listOf(
        Activity("a1", "create", "note", "n1", "Created note: Plumber visit", "voice", true, createdAt = ago(12)),
        Activity("a2", "create", "reminder", "r2", "Created reminder: Call the dentist", "chat", true, createdAt = ago(300)),
        Activity("a3", "update", "task", "t3", "Completed task: Call the plumber", "app", true, createdAt = ago(600)),
        Activity("a4", "delete", "list", "l9", "Deleted list: Old errands", "app", true, undoneAt = ago(500), createdAt = ago(700)),
        Activity("a5", "create", "event", "e2", "Created event: Lunch with Sam", "dashboard", true, createdAt = ago(60 * 26)),
        Activity("a6", "create", "memory", "m4", "Remembered: Allergic to penicillin", "chat", false, createdAt = ago(60 * 27)),
    )

    private val settings = SettingsResponse(
        UserSettings(language = "en", wakeWord = "hey kairo", voice = "af_heart", voiceSpeed = 1.0, briefingTime = "07:00", homeCity = "Denver"),
        googleAvailable = true, ntfyAvailable = true,
    )
    private val voices = VoicesResponse(
        listOf(
            VoiceOption("af_heart", "Heart", "Warm and friendly"),
            VoiceOption("af_bella", "Bella", "Clear and expressive"),
            VoiceOption("am_michael", "Michael", "Calm and low"),
        ),
    )
    private val devices = listOf(
        Device("d1", "Google Pixel 10 Pro Fold", "android", ago(60 * 24 * 40), ago(1), current = true),
        Device("d2", "Pixel Watch 4", "wear", ago(60 * 24 * 20), ago(60 * 3)),
        Device("d3", "Dashboard (Firefox)", "web", ago(60 * 24 * 60), ago(60 * 24 * 2)),
    )

    private fun events(): List<Event> {
        val parents = if (today.dayOfMonth <= 24) today.plusDays(4) else today.minusDays(4)
        return listOf(
            Event("e1", "Team stand-up", startAt = at(today, 9, 30), endAt = at(today, 10), createdAt = ago(9000), updatedAt = ago(9000), ownerId = user.id),
            Event("e2", "Lunch with Sam", location = "Cafe Central", startAt = at(today, 13), endAt = at(today, 14), shared = true, createdAt = ago(9000), updatedAt = ago(9000), ownerId = user.id),
            Event("e3", "Parent-teacher meeting", location = "Mia's school", startAt = at(parents, 17, 30), endAt = at(parents, 18, 30), shared = true, createdAt = ago(9000), updatedAt = ago(9000), ownerId = SAM),
            Event("e4", "Dentist", startAt = at(today.plusDays(1), 9), endAt = at(today.plusDays(1), 10), createdAt = ago(9000), updatedAt = ago(9000), ownerId = user.id, source = "google"),
            Event("e5", "Birthday party", startAt = at(today.plusDays(7), 19), endAt = at(today.plusDays(7), 22), shared = true, createdAt = ago(9000), updatedAt = ago(9000), ownerId = user.id),
            Event("e6", "Yoga class", startAt = at(today.plusDays(2), 7), endAt = at(today.plusDays(2), 8), createdAt = ago(9000), updatedAt = ago(9000), ownerId = user.id),
        )
    }

    /** Day that has the family event scheduled by another member (used by the calendar screenshot). */
    val parentsMeetingDay: LocalDate get() = if (today.dayOfMonth <= 24) today.plusDays(4) else today.minusDays(4)

    private fun todayData(): TodayData {
        return TodayData(
            date = today.toString(), dateLabel = "", greeting = "",
            events = events().filter { runCatching { Instant.parse(it.startAt).atZone(zone).toLocalDate() == today }.getOrDefault(false) },
            tasksDueToday = tasks.filter { it.dueDate == today.toString() && it.status != "done" },
            tasksOverdue = tasks.filter { it.dueDate == today.minusDays(1).toString() && it.status != "done" },
            tasksInProgress = tasks.filter { it.status == "doing" },
            reminders = listOf(reminderToday, reminderCall, reminderPickup),
            firedReminders = listOf(reminderFired),
            recentNotes = notes.take(2),
            lists = lists.take(2),
            weather = WeatherReport("Denver", 24.0, 25.0, "Partly cloudy", listOf(WeatherDay(today.toString(), 18.0, 27.0, 30, "Partly cloudy"))),
        )
    }

    private val notices = listOf(
        Notice("nt1", "assigned", "Sam assigned you a task", "Pick up Mia from school", SAM, "task", "t3", ago(8)),
        Notice("nt2", "message", "Mia Rivera", "Dinner is ready", SOFIA, null, null, ago(35)),
        Notice("nt3", "shared", "Sam shared a list", "Groceries", SAM, "list", "l1", ago(60 * 5), readAt = ago(60 * 4)),
        Notice("nt4", "reminder", "Reminder for you", "Buy Mia's school supplies", user.id, "reminder", "r7", ago(60 * 26), readAt = ago(60 * 25)),
    )

    private val invitations = mutableListOf<Invitation>()
    val inviteCode = "K7QX-4M2P"

    // ------------------------------------------------------------ Server

    private fun <T> enc(serializer: KSerializer<T>, value: T) = json.encodeToString(serializer, value)

    private fun route(method: String, path: String, query: String?): String? = when {
        path == "/api/today" -> enc(TodayData.serializer(), todayData())
        path == "/api/notices" -> {
            val unreadOnly = query?.contains("unread=true") == true
            val list = if (unreadOnly) notices.filter { it.readAt == null } else notices
            enc(NoticesResponse.serializer(), NoticesResponse(list.take(if (unreadOnly) 3 else 50), notices.count { it.readAt == null }))
        }
        path == "/api/tasks" -> enc(ListSerializer(Task.serializer()), if (query?.contains("assignee=me") == true) tasks.filter { it.assigneeId == user.id } else tasks)
        path == "/api/projects" -> enc(ListSerializer(Project.serializer()), projects)
        path == "/api/notes" -> enc(ListSerializer(Note.serializer()), notes)
        path == "/api/conversations" -> enc(ListSerializer(Conversation.serializer()), conversations)
        path == "/api/reminders" -> enc(ListSerializer(Reminder.serializer()), reminders)
        path == "/api/places" -> enc(ListSerializer(Place.serializer()), places)
        path == "/api/events" -> enc(ListSerializer(Event.serializer()), events())
        path == "/api/lists" -> enc(ListSerializer(ShoppingList.serializer()), lists)
        path == "/api/memories" -> enc(ListSerializer(Memory.serializer()), memories)
        path == "/api/activity" -> enc(ListSerializer(Activity.serializer()), activity)
        path == "/api/settings" -> enc(SettingsResponse.serializer(), settings)
        path == "/api/voice/voices" -> enc(VoicesResponse.serializer(), voices)
        path == "/api/devices" -> enc(ListSerializer(Device.serializer()), devices)
        path == "/api/push" -> enc(PushConfig.serializer(), PushConfig("BEl6-sample-vapid-public-key", 1))
        path == "/api/family" -> enc(FamilyInfo.serializer(), family)
        path == "/api/family/invites" && method == "GET" -> enc(ListSerializer(Invitation.serializer()), invitations.toList())
        path == "/api/family/invites" && method == "POST" -> {
            val inv = Invitation("inv${invitations.size + 1}", "invite", "Mia", "member", null, now.toString(), now.plusSeconds(7 * 86_400).toString())
            invitations += inv
            enc(InviteCreated.serializer(), InviteCreated(inv, inviteCode, "https://kairo.example.com/join?code=$inviteCode"))
        }
        path.startsWith("/api/auth/invite/") -> enc(InvitePreview.serializer(), InvitePreview("invite", "Rivera family", "Mia", null))
        path == "/api/recordings" -> "[]"
        else -> null
    }

    private var server: HttpServer? = null

    /** Starts the server (once per JVM) and returns its base URL. */
    @Synchronized
    fun start(): String {
        server?.let { return "http://127.0.0.1:${it.address.port}" }
        val s = runCatching { HttpServer.create(InetSocketAddress("127.0.0.1", 8088), 0) }.getOrElse { HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0) }
        s.createContext("/") { ex: HttpExchange ->
            val body = runCatching { route(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery) }.getOrNull()
            val bytes = (body ?: """{"error":"Not found"}""").toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(if (body != null) 200 else 404, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    fun resetInvites() = invitations.clear()
}
