package app.kairo.android

import android.content.Context
import app.kairo.android.data.AppPrefs
import app.kairo.android.device.DeviceActions
import app.kairo.android.location.LocationProvider
import app.kairo.android.recording.RecorderController
import app.kairo.android.reminders.AlarmScheduler
import app.kairo.android.reminders.GeofenceManager
import app.kairo.android.sync.RealtimeHub
import app.kairo.android.voice.VoiceEngine
import app.kairo.android.voice.WakeModelManager
import app.kairo.core.net.ClientContext
import app.kairo.core.net.KairoApi
import app.kairo.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Contenedor de dependencias de la app (inyección manual, sin reflexión). */
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

    val deviceActions = DeviceActions(app)
    val wakeModel = WakeModelManager(app)
    val voice = VoiceEngine(app, api, prefs, wakeModel, deviceActions, scope)
    val realtime = RealtimeHub(app, api, scope)
    val alarms = AlarmScheduler(app, prefs)
    val geofences = GeofenceManager(app, prefs)
    val recorder = RecorderController(app, api, scope)
}

val Context.graph: AppGraph get() = (applicationContext as KairoApplication).graph
