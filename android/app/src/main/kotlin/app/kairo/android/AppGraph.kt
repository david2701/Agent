package app.kairo.android

import android.content.Context
import app.kairo.android.data.AppPrefs
import app.kairo.android.device.DeviceActions
import app.kairo.android.family.FamilyStore
import app.kairo.android.location.LocationProvider
import app.kairo.android.push.PushManager
import app.kairo.android.recording.RecorderController
import app.kairo.android.reminders.AlarmScheduler
import app.kairo.android.reminders.GeofenceManager
import app.kairo.android.sync.RealtimeHub
import app.kairo.android.sync.ServerEventHandler
import app.kairo.android.voice.VoiceEngine
import app.kairo.android.voice.WakeModelManager
import app.kairo.core.net.ClientContext
import app.kairo.core.net.KairoApi
import app.kairo.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Dependency container of the app (manual injection, no reflection). */
class AppGraph(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val session = SessionStore(app)
    val prefs = AppPrefs(app)
    val location = LocationProvider(app)

    val api = KairoApi(session, object : ClientContext {
        override val clientKind = "android"
        override val capabilities = setOf("timer", "alarm", "location")
        override suspend fun location() = this@AppGraph.location.last()
    })

    val family = FamilyStore(api, prefs, scope)
    /** Single handler for server events (WebSocket and push). */
    val events = ServerEventHandler(app, api.json, family)
    val push = PushManager(app, api, scope)

    val deviceActions = DeviceActions(app)
    val wakeModel = WakeModelManager(app)
    val voice = VoiceEngine(app, api, prefs, wakeModel, deviceActions, scope)
    val realtime = RealtimeHub(api, scope, events)
    val alarms = AlarmScheduler(app, prefs)
    val geofences = GeofenceManager(app, prefs)
    val recorder = RecorderController(app, api, scope)

    init {
        // The family is loaded on opening the app and on sign-in, and reloaded with `entity.changed: family`
        family.follow(events.changes)
        scope.launch { session.session.map { it?.user?.id }.distinctUntilChanged().collect { family.onSession(it) } }
    }
}

val Context.graph: AppGraph get() = (applicationContext as KairoApplication).graph
