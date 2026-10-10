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
import app.kairo.domain.FilesRepo
import app.kairo.domain.ListsRepo
import app.kairo.domain.MemoriesRepo
import app.kairo.domain.NotesRepo
import app.kairo.domain.PlacesRepo
import app.kairo.domain.RecordingsRepo
import app.kairo.domain.RemindersRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.TasksRepo
import app.kairo.domain.UsersRepo
import app.kairo.integrations.GoogleCalendarService
import app.kairo.integrations.McpClient
import app.kairo.integrations.NtfyNotifier
import app.kairo.integrations.WeatherService
import app.kairo.nan.NanClient
import app.kairo.realtime.EventBus
import app.kairo.services.BriefingService
import app.kairo.services.CaptureService
import app.kairo.services.RecordingService
import app.kairo.services.Schedulers
import app.kairo.services.VoiceService
import app.kairo.util.Crypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Inyección de dependencias manual: un solo lugar donde se construye todo. */
class Services(val config: AppConfig, val db: Db, val nan: NanClient) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val crypto = Crypto(config.encryptionKey)
    val bus = EventBus()

    val users = UsersRepo(db)
    val settings = SettingsService(users)
    val auth = AuthService(config, users)
    val activity = ActivityRepo(db, bus)
    val files = FilesRepo(db, crypto)
    val embedder = Embedder(nan, db, scope)
    val notes = NotesRepo(db, activity, embedder, files, nan)
    val tasks = TasksRepo(db, activity)
    val places = PlacesRepo(db, activity)
    val reminders = RemindersRepo(db, activity)
    val lists = ListsRepo(db, activity)
    val events = EventsRepo(db, activity)
    val memories = MemoriesRepo(db, activity, embedder)
    val conversations = ConversationsRepo(db)
    val recordingsRepo = RecordingsRepo(db, activity)

    val weather = WeatherService()
    val ntfy = NtfyNotifier(config)
    val mcpClient = McpClient()
    val mcp = McpRegistry(db, crypto, mcpClient)
    val google = GoogleCalendarService(config, db, crypto, events, bus)

    val briefing = BriefingService(events, tasks, reminders, notes, lists, weather, settings, nan)
    val voice = VoiceService(nan, settings)
    val tools = BuiltinTools(notes, tasks, reminders, places, lists, events, memories, briefing, weather, files, nan)
    val agent = Agent(nan, tools, mcp, conversations, memories, places, lists, settings)
    val capture = CaptureService(agent, nan, files, settings)
    val recordings = RecordingService(recordingsRepo, voice, notes, tasks, settings, nan, bus, scope)
    val schedulers = Schedulers(scope, reminders, users, settings, briefing, google, embedder, activity, bus, ntfy, config.googleEnabled)

    fun close() {
        scope.cancel()
        nan.close()
        db.close()
    }
}
