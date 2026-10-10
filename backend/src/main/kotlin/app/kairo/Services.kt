package app.kairo

import app.kairo.agent.Agent
import app.kairo.agent.BuiltinTools
import app.kairo.agent.McpRegistry
import app.kairo.auth.AuthService
import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.domain.ActivityRepo
import app.kairo.domain.ConversationsRepo
import app.kairo.domain.Embedder
import app.kairo.domain.EventsRepo
import app.kairo.domain.FamilyRepo
import app.kairo.domain.FilesRepo
import app.kairo.domain.ListsRepo
import app.kairo.domain.MemoriesRepo
import app.kairo.domain.NoticesRepo
import app.kairo.domain.NotesRepo
import app.kairo.domain.PlacesRepo
import app.kairo.domain.RecordingsRepo
import app.kairo.domain.RemindersRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.TasksRepo
import app.kairo.domain.UsersRepo
import app.kairo.integrations.ApnsService
import app.kairo.integrations.GoogleCalendarService
import app.kairo.integrations.McpClient
import app.kairo.integrations.NtfyNotifier
import app.kairo.integrations.PushService
import app.kairo.integrations.WeatherService
import app.kairo.nan.NanClient
import app.kairo.realtime.EventBus
import app.kairo.services.BriefingService
import app.kairo.services.CaptureService
import app.kairo.services.Notifier
import app.kairo.services.RecordingService
import app.kairo.services.Schedulers
import app.kairo.services.VoiceService
import app.kairo.util.Crypto
import app.kairo.util.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.UUID

/** Manual dependency injection: a single place where everything is built. */
class Services(val config: AppConfig, val db: Db, val nan: NanClient) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val crypto = Crypto(config.encryptionKey)
    val bus = EventBus()

    val users = UsersRepo(db)
    val settings = SettingsService(users, config.defaultLanguage)
    val family = FamilyRepo(db)
    val auth = AuthService(config, users, family)

    /** Language of a user, for the texts the server writes on their behalf (activity, default names). */
    private val langOf: suspend (UUID) -> Lang = { settings.lang(it) }
    val activity = ActivityRepo(db, bus, family, langOf)
    val files = FilesRepo(db, crypto)
    val embedder = Embedder(nan, db, scope)

    // Notices without Firebase: WebSocket + Web Push (UnifiedPush) + APNs (iPhone, optional) + ntfy
    val ntfy = NtfyNotifier(config)
    val push = PushService(db, crypto, config)
    val apns = ApnsService(db, config)
    val notices = NoticesRepo(db)
    val notifier = Notifier(scope, config, bus, push, apns, ntfy, notices, users, family, settings)

    val notes = NotesRepo(db, activity, embedder, files, nan, notifier)
    val tasks = TasksRepo(db, activity, notifier)
    val places = PlacesRepo(db, activity, langOf)
    val reminders = RemindersRepo(db, activity, notifier)
    val lists = ListsRepo(db, activity, notifier, langOf)
    val events = EventsRepo(db, activity, notifier)
    val memories = MemoriesRepo(db, activity, embedder)
    val conversations = ConversationsRepo(db)
    val recordingsRepo = RecordingsRepo(db, activity)

    val weather = WeatherService()
    val mcpClient = McpClient()
    val mcp = McpRegistry(db, crypto, mcpClient)
    val google = GoogleCalendarService(config, db, crypto, events, bus, langOf)

    val briefing = BriefingService(events, tasks, reminders, notes, lists, weather, settings, nan)
    val voice = VoiceService(nan, settings)
    val tools = BuiltinTools(notes, tasks, reminders, places, lists, events, memories, briefing, weather, files, nan, users, notifier)
    val agent = Agent(nan, tools, mcp, conversations, memories, places, lists, settings, family)
    val capture = CaptureService(agent, nan, files, settings)
    val recordings = RecordingService(recordingsRepo, voice, notes, tasks, settings, nan, bus, scope)
    val schedulers = Schedulers(scope, reminders, events, users, settings, briefing, google, embedder, activity, notifier, notices, config.googleEnabled)

    fun close() {
        scope.cancel()
        nan.close()
        db.close()
    }
}
