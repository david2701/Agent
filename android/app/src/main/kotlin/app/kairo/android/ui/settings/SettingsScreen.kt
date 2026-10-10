package app.kairo.android.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kairo.android.AppGraph
import app.kairo.android.BuildConfig
import app.kairo.android.service.ListeningService
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.android.voice.WakeModelManager
import app.kairo.core.model.Device
import app.kairo.core.model.SettingsResponse
import app.kairo.core.model.VoiceOption
import app.kairo.core.net.KairoApi.Companion.obj
import app.kairo.core.session.Session
import app.kairo.core.util.relativeLabel
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

data class SettingsData(val server: SettingsResponse, val voices: List<VoiceOption>, val devices: List<Device>)

class SettingsViewModel(graph: AppGraph) : ResourceViewModel<SettingsData>(graph, setOf("settings")) {
    override suspend fun load() = SettingsData(graph.api.settings(), runCatching { graph.api.voices().voices }.getOrDefault(emptyList()), graph.api.devices())
    init { refresh() }

    fun patch(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = mutate { graph.api.updateSettings(obj(block)) }
    fun revoke(d: Device) = mutate("Sesión cerrada en ${d.name}") { graph.api.revokeDevice(d.id) }
    fun logout() = viewModelScope.launch {
        ListeningService.stop(graph.app)
        graph.prefs.setWakeWord(false)
        graph.api.logout()
    }

    /** Activa la escucha continua: descarga el modelo si falta y arranca el servicio. */
    fun enableWakeWord() = viewModelScope.launch {
        if (!graph.wakeModel.ensure()) { say("No se pudo descargar el modelo de activación"); return@launch }
        graph.prefs.setWakeWord(true)
        ListeningService.start(graph.app)
    }

    fun disableWakeWord() = viewModelScope.launch {
        graph.prefs.setWakeWord(false)
        ListeningService.stop(graph.app)
    }
}

@Composable
fun SettingsScreen(graph: AppGraph, session: Session) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(graph) }
    val state by vm.state.collectAsState()
    val local by graph.prefs.settings.collectAsState(initial = app.kairo.android.data.LocalSettings())
    val modelStatus by graph.wakeModel.status.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Estado de permisos del sistema (se reevalúa al volver de Ajustes de Android)
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val perms = remember(tick) { SystemStatus.read(context) }

    val wakePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) vm.enableWakeWord()
    }
    val locationPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    var pickTime by remember { mutableStateOf(false) }
    var pickVoice by remember { mutableStateOf(false) }

    val s = state.data?.server?.settings

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner).statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Ajustes", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp)) }
            state.error?.let { item { ErrorBanner(it, onRetry = { vm.refresh() }) } }

            item {
                Group("Escucha") {
                    ToggleRow(
                        "Oye Kairo", "Kairo escucha la frase de activación en el teléfono, sin enviar audio hasta que la dices. Usa batería y muestra el indicador del micrófono.",
                        checked = local.wakeWordEnabled,
                    ) { on ->
                        if (on) {
                            val needed = buildList { add(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }
                            wakePermissions.launch(needed.toTypedArray())
                        } else vm.disableWakeWord()
                    }
                    when (val st = modelStatus) {
                        is WakeModelManager.Status.Downloading -> Column(Modifier.padding(top = 8.dp)) {
                            Text("Descargando modelo de voz offline (40 MB)...", style = MaterialTheme.typography.bodySmall)
                            LinearWavyProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                        is WakeModelManager.Status.Failed -> Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        else -> Unit
                    }
                    Divider()
                    ToggleRow("Seguir la conversación", "Si Kairo te pregunta algo, escucha la respuesta sin repetir \"Oye Kairo\".", local.followUp) { scope.launch { graph.prefs.setFollowUp(it) } }
                    Divider()
                    ToggleRow("Sonidos de escucha", "Un tono breve al empezar y al terminar.", local.earcons) { scope.launch { graph.prefs.setEarcons(it) } }
                    Divider()
                    ToggleRow("Leer respuestas del chat", "Kairo lee en voz alta lo que responde en Conversación.", local.speakChatReplies) { scope.launch { graph.prefs.setSpeakChatReplies(it) } }
                }
            }

            item {
                Group("Para que funcione siempre") {
                    StatusRow("Asistente del sistema", "Elige Kairo como asistente digital para abrirlo manteniendo pulsado el botón de inicio o encendido.", "Configurar") {
                        val intents = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                        intents.firstOrNull { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
                    }
                    Divider()
                    StatusRow(
                        "Batería sin restricciones",
                        if (perms.batteryUnrestricted) "Listo: Android no detendrá a Kairo." else "Samsung y otros fabricantes cierran apps en segundo plano. Permite que Kairo siga activo.",
                        if (perms.batteryUnrestricted) null else "Permitir",
                    ) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                    }
                    Divider()
                    StatusRow(
                        "Alarmas exactas",
                        if (perms.exactAlarms) "Listo: los recordatorios suenan a la hora exacta, incluso sin internet." else "Necesario para que los recordatorios suenen puntuales.",
                        if (perms.exactAlarms) null else "Permitir",
                    ) {
                        if (Build.VERSION.SDK_INT >= 31) runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
                    }
                    Divider()
                    StatusRow(
                        "Ubicación para recordatorios por lugar",
                        when {
                            perms.backgroundLocation -> "Listo: avisos al llegar o salir de tus lugares."
                            perms.fineLocation -> "Falta \"Permitir todo el tiempo\" para avisarte con la app cerrada."
                            else -> "Opcional. Permite avisos al llegar al súper, a casa o al trabajo."
                        },
                        when { perms.backgroundLocation -> null; perms.fineLocation -> "Abrir ajustes"; else -> "Permitir" },
                    ) {
                        if (!perms.fineLocation) locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }

            if (s != null) item {
                Group("Asistente") {
                    val voiceName = state.data?.voices?.firstOrNull { it.id == s.voice }?.name ?: s.voice
                    StatusRow("Voz", "$voiceName · velocidad ${"%.1f".format(s.voiceSpeed)}x", "Cambiar") { pickVoice = true }
                    var speed by remember(s.voiceSpeed) { mutableFloatStateOf(s.voiceSpeed.toFloat()) }
                    Slider(speed, { speed = it }, valueRange = 0.7f..1.4f, steps = 6, onValueChangeFinished = { vm.patch { put("voiceSpeed", speed.toDouble()) } })
                    Divider()
                    ToggleRow("Respuestas rápidas", "Desactiva el razonamiento extendido del modelo para responder antes.", s.fastMode) { v -> vm.patch { put("fastMode", v) } }
                    Divider()
                    ToggleRow("Resumen de la mañana", "A las ${s.briefingTime}, Kairo te resume el día: agenda, pendientes y clima.", s.briefingEnabled) { v -> vm.patch { put("briefingEnabled", v) } }
                    if (s.briefingEnabled) TextButton(onClick = { pickTime = true }) { Text("Cambiar hora: ${s.briefingTime}") }
                    Divider()
                    Text("Modelo: ${s.chatModel} (respaldo ${s.fallbackModel})", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Ciudad para el clima: ${s.homeCity ?: "sin configurar (cámbiala en el dashboard)"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                Group("Cuenta") {
                    Text(session.user.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(session.user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(session.serverUrl, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(1.dp))
                    OutlinedButton(onClick = { vm.logout() }) { Text("Cerrar sesión") }
                }
            }

            state.data?.devices?.takeIf { it.isNotEmpty() }?.let { devices ->
                item {
                    Group("Dispositivos con sesión") {
                        devices.forEachIndexed { i, d ->
                            if (i > 0) Divider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name + if (d.current) " (este)" else "", style = MaterialTheme.typography.bodyLarge)
                                    Text("${d.platform} · ${d.lastSeenAt.toInstantOrNull()?.relativeLabel() ?: ""}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!d.current) TextButton(onClick = { vm.revoke(d) }) { Text("Cerrar") }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Kairo ${BuildConfig.VERSION_NAME}. Tus datos viven en tu servidor; la IA la provee Nan. El audio de \"Oye Kairo\" se procesa en el teléfono.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (pickTime && s != null) {
        val (h, m) = s.briefingTime.split(':').map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 7 }) to (it.getOrElse(1) { 0 }) }
        val tp = rememberTimePickerState(h, m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { vm.patch { put("briefingTime", "%02d:%02d".format(tp.hour, tp.minute)) }; pickTime = false }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancelar") } },
            text = { TimePicker(tp) },
        )
    }
    if (pickVoice && s != null) {
        AlertDialog(
            onDismissRequest = { pickVoice = false },
            title = { Text("Voz de Kairo") },
            text = {
                Column {
                    state.data?.voices.orEmpty().forEach { v ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.voice == v.id, onClick = { vm.patch { put("voice", v.id) } })
                            Column(Modifier.weight(1f)) {
                                Text(v.name, style = MaterialTheme.typography.bodyLarge)
                                Text(v.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { graph.voice.preview(v.id) }) { Text("Probar") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickVoice = false }) { Text("Listo") } },
        )
    }
}

private data class SystemStatus(val batteryUnrestricted: Boolean, val exactAlarms: Boolean, val fineLocation: Boolean, val backgroundLocation: Boolean) {
    companion object {
        fun read(c: Context) = SystemStatus(
            batteryUnrestricted = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName),
            exactAlarms = c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
            fineLocation = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            backgroundLocation = c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 720.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun StatusRow(title: String, body: String, action: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.let { TextButton(onClick = onAction) { Text(it) } }
    }
}
