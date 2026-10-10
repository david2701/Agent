package app.kairo.android.ui.settings

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.text.format.DateFormat
import app.kairo.android.ui.common.clockLabel
import app.kairo.android.ui.common.formatLocale
import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
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
import app.kairo.android.service.ConnectionService
import app.kairo.android.service.ListeningService
import app.kairo.android.ui.Destination
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.common.friendly
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.rememberFamily
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.android.voice.WakeModelManager
import app.kairo.core.model.Device
import app.kairo.core.model.PushConfig
import app.kairo.core.model.SettingsResponse
import app.kairo.core.model.VoiceOption
import app.kairo.core.net.KairoApi.Companion.obj
import app.kairo.core.session.Session
import app.kairo.android.ui.common.relativeLabel
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

data class SettingsData(val server: SettingsResponse, val voices: List<VoiceOption>, val devices: List<Device>, val push: PushConfig?)

class SettingsViewModel(graph: AppGraph) : ResourceViewModel<SettingsData>(graph, setOf("settings")) {
    override suspend fun load() = SettingsData(
        graph.api.settings(), runCatching { graph.api.voices().voices }.getOrDefault(emptyList()), graph.api.devices(),
        runCatching { graph.api.pushConfig() }.getOrNull(),
    )
    init { refresh() }

    fun patch(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = mutate { graph.api.updateSettings(obj(block)) }
    val pairCode = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun newPairCode() = viewModelScope.launch {
        runCatching { graph.api.pairCode() }.onSuccess { pairCode.value = it }.onFailure { say(R.string.set_pair_failed) }
    }

    fun revoke(d: Device) = mutate(str(R.string.set_session_closed, d.name)) { graph.api.revokeDevice(d.id) }
    fun logout() = viewModelScope.launch {
        ListeningService.stop(graph.app)
        ConnectionService.stop(graph.app)
        graph.prefs.setWakeWord(false)
        graph.prefs.setDirectConnection(false)
        // The server forgets the push endpoint before the session is closed (it needs the token)
        graph.push.unregister()
        graph.api.logout()
    }

    /** "Send test": a notice over push only, to check that it arrives with the app closed. */
    fun testPush() = viewModelScope.launch {
        runCatching { graph.push.test() }
            .onSuccess { n ->
                say(if (n == 0) str(R.string.set_push_test_none) else graph.app.resources.getQuantityString(R.plurals.set_push_test_sent, n, n))
                refresh(silent = true)
            }
            .onFailure { say(it.friendly()) }
    }

    fun setDirectConnection(on: Boolean) = viewModelScope.launch {
        graph.prefs.setDirectConnection(on)
        if (on) ConnectionService.start(graph.app) else ConnectionService.stop(graph.app)
    }

    /** Turns on continuous listening: downloads the model if missing and starts the service. */
    fun enableWakeWord() = viewModelScope.launch {
        if (!graph.wakeModel.ensure()) { say(R.string.set_model_failed); return@launch }
        graph.prefs.setWakeWord(true)
        ListeningService.start(graph.app)
    }

    fun disableWakeWord() = viewModelScope.launch {
        graph.prefs.setWakeWord(false)
        ListeningService.stop(graph.app)
    }
}

@Composable
fun SettingsScreen(graph: AppGraph, session: Session, navigate: (Destination) -> Unit = {}) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(graph) }
    val state by vm.state.collectAsState()
    val local by graph.prefs.settings.collectAsState(initial = app.kairo.android.data.LocalSettings())
    val modelStatus by graph.wakeModel.status.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val family = rememberFamily(graph)
    val push by graph.push.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // System permission state (re-evaluated when coming back from Android Settings)
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++; graph.push.refreshState() }
    val perms = remember(tick) { SystemStatus.read(context) }

    val wakePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) vm.enableWakeWord()
    }
    val locationPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    // The direct connection works without this permission, but without it the fixed notification is not shown
    val connectionPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setDirectConnection(true) }
    var pickTime by remember { mutableStateOf(false) }
    var pickVoice by remember { mutableStateOf(false) }

    val s = state.data?.server?.settings
    val wake = stringResource(R.string.wake_phrase)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner).statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp)) }
            state.error?.let { item { ErrorBanner(it, onRetry = { vm.refresh() }) } }

            item {
                Group(stringResource(R.string.set_listening_group)) {
                    ToggleRow(
                        wake, stringResource(R.string.set_wake_body),
                        checked = local.wakeWordEnabled,
                    ) { on ->
                        if (on) {
                            val needed = buildList { add(Manifest.permission.RECORD_AUDIO); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }
                            wakePermissions.launch(needed.toTypedArray())
                        } else vm.disableWakeWord()
                    }
                    when (val st = modelStatus) {
                        is WakeModelManager.Status.Downloading -> Column(Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.set_model_downloading), style = MaterialTheme.typography.bodySmall)
                            LinearWavyProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                        is WakeModelManager.Status.Failed -> Text(stringResource(R.string.set_model_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        else -> Unit
                    }
                    Divider()
                    ToggleRow(stringResource(R.string.set_follow_title), stringResource(R.string.set_follow_body, wake), local.followUp) { scope.launch { graph.prefs.setFollowUp(it) } }
                    Divider()
                    ToggleRow(stringResource(R.string.set_sounds_title), stringResource(R.string.set_sounds_body), local.earcons) { scope.launch { graph.prefs.setEarcons(it) } }
                    Divider()
                    ToggleRow(stringResource(R.string.set_speak_title), stringResource(R.string.set_speak_body), local.speakChatReplies) { scope.launch { graph.prefs.setSpeakChatReplies(it) } }
                }
            }

            item {
                Group(stringResource(R.string.set_always_group)) {
                    StatusRow(stringResource(R.string.set_assistant_title), stringResource(R.string.set_assistant_body), stringResource(R.string.set_configure)) {
                        val intents = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                        intents.firstOrNull { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
                    }
                    Divider()
                    StatusRow(
                        stringResource(R.string.set_battery_title),
                        if (perms.batteryUnrestricted) stringResource(R.string.set_battery_ok) else stringResource(R.string.set_battery_needed),
                        if (perms.batteryUnrestricted) null else stringResource(R.string.set_allow),
                    ) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }
                    }
                    Divider()
                    StatusRow(
                        stringResource(R.string.set_alarms_title),
                        if (perms.exactAlarms) stringResource(R.string.set_alarms_ok) else stringResource(R.string.set_alarms_needed),
                        if (perms.exactAlarms) null else stringResource(R.string.set_allow),
                    ) {
                        if (Build.VERSION.SDK_INT >= 31) runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
                    }
                    Divider()
                    StatusRow(
                        stringResource(R.string.set_location_title),
                        when {
                            perms.backgroundLocation -> stringResource(R.string.set_location_ok)
                            perms.fineLocation -> stringResource(R.string.set_location_background)
                            else -> stringResource(R.string.set_location_optional)
                        },
                        when { perms.backgroundLocation -> null; perms.fineLocation -> stringResource(R.string.set_open_settings); else -> stringResource(R.string.set_allow) },
                    ) {
                        if (!perms.fineLocation) locationPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }

            item {
                Group(stringResource(R.string.set_push_group)) {
                    val label = push.distributorLabel ?: push.distributor
                    StatusRow(
                        stringResource(R.string.set_push_title),
                        when {
                            label != null && push.registered -> stringResource(R.string.set_push_active, label)
                            label != null && push.working -> stringResource(R.string.set_push_registering, label)
                            label != null -> stringResource(R.string.set_push_using, label)
                            push.installed.isNotEmpty() -> stringResource(R.string.set_push_installed)
                            else -> stringResource(R.string.set_push_none)
                        },
                        if (push.working) null else stringResource(R.string.set_configure),
                    ) { activity?.let(graph.push::configure) }
                    push.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    state.data?.push?.let { p ->
                        Text(
                            if (p.registeredDevices == 0) stringResource(R.string.set_push_devices_none) else pluralStringResource(R.plurals.set_push_devices, p.registeredDevices, p.registeredDevices),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { vm.testPush() }) { Text(stringResource(R.string.set_send_test)) }
                    Text(
                        stringResource(R.string.set_push_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Divider()
                    ToggleRow(
                        stringResource(R.string.set_direct_title),
                        stringResource(R.string.set_direct_body, wake),
                        checked = local.directConnection,
                    ) { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            connectionPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else vm.setDirectConnection(on)
                    }
                }
            }

            if (s != null) item {
                Group(stringResource(R.string.set_assistant_group)) {
                    val voiceName = state.data?.voices?.firstOrNull { it.id == s.voice }?.name ?: s.voice
                    StatusRow(stringResource(R.string.set_voice), stringResource(R.string.set_voice_value, voiceName, String.format(context.formatLocale(), "%.1f", s.voiceSpeed)), stringResource(R.string.set_change)) { pickVoice = true }
                    var speed by remember(s.voiceSpeed) { mutableFloatStateOf(s.voiceSpeed.toFloat()) }
                    Slider(speed, { speed = it }, valueRange = 0.7f..1.4f, steps = 6, onValueChangeFinished = { vm.patch { put("voiceSpeed", speed.toDouble()) } })
                    Divider()
                    ToggleRow(stringResource(R.string.set_fast_title), stringResource(R.string.set_fast_body), s.fastMode) { v -> vm.patch { put("fastMode", v) } }
                    Divider()
                    val briefingTime = runCatching { context.clockLabel(java.time.LocalTime.parse(s.briefingTime)) }.getOrDefault(s.briefingTime)
                    ToggleRow(stringResource(R.string.set_briefing_title), stringResource(R.string.set_briefing_body, briefingTime), s.briefingEnabled) { v -> vm.patch { put("briefingEnabled", v) } }
                    if (s.briefingEnabled) TextButton(onClick = { pickTime = true }) { Text(stringResource(R.string.set_change_time, briefingTime)) }
                    Divider()
                    Text(stringResource(R.string.set_model, s.chatModel, s.fallbackModel), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.set_city, s.homeCity ?: stringResource(R.string.set_city_unset)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                Group(stringResource(R.string.nav_family)) {
                    val subtitle = when {
                        family == null -> stringResource(R.string.set_family_blurb)
                        family.others.isEmpty() && family.isAdmin -> stringResource(R.string.set_family_alone)
                        else -> pluralStringResource(R.plurals.family_members, family.members.size, family.members.size) + if (family.isAdmin) " \u00B7 " + stringResource(R.string.family_admin_note) else ""
                    }
                    StatusRow(family?.household?.name ?: stringResource(R.string.nav_family), subtitle, stringResource(R.string.home_open)) { navigate(Destination.Family) }
                }
            }

            item {
                Group(stringResource(R.string.set_account_group)) {
                    Text(session.user.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(session.user.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(session.serverUrl, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(1.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.newPairCode() }) { Text(stringResource(R.string.set_pair_watch)) }
                        OutlinedButton(onClick = { vm.logout() }) { Text(stringResource(R.string.set_sign_out)) }
                    }
                    // Kairo follows the system language; Android 13+ has a per-app language picker
                    if (Build.VERSION.SDK_INT >= 33) {
                        Divider()
                        StatusRow(stringResource(R.string.set_language_title), stringResource(R.string.set_language_body), stringResource(R.string.set_change)) {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            }

            state.data?.devices?.takeIf { it.isNotEmpty() }?.let { devices ->
                item {
                    Group(stringResource(R.string.set_devices_group)) {
                        devices.forEachIndexed { i, d ->
                            if (i > 0) Divider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name + if (d.current) " " + stringResource(R.string.set_this_device) else "", style = MaterialTheme.typography.bodyLarge)
                                    Text("${d.platform} \u00B7 ${d.lastSeenAt.toInstantOrNull()?.relativeLabel() ?: ""}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!d.current) TextButton(onClick = { vm.revoke(d) }) { Text(stringResource(R.string.set_revoke)) }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    stringResource(R.string.set_footer, BuildConfig.VERSION_NAME, wake),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }

    val code by vm.pairCode.collectAsState()
    code?.let { c ->
        AlertDialog(
            onDismissRequest = { vm.pairCode.value = null },
            title = { Text(stringResource(R.string.set_pair_watch)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.set_pair_body), style = MaterialTheme.typography.bodyMedium)
                    Text(c.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall.copy(fontFamily = MonoStyle.fontFamily))
                    Text(session.serverUrl, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { vm.pairCode.value = null }) { Text(stringResource(R.string.common_done)) } },
        )
    }

    if (pickTime && s != null) {
        val (h, m) = s.briefingTime.split(':').map { it.toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 7 }) to (it.getOrElse(1) { 0 }) }
        val tp = rememberTimePickerState(h, m, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { vm.patch { put("briefingTime", "%02d:%02d".format(tp.hour, tp.minute)) }; pickTime = false }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.common_cancel)) } },
            text = { TimePicker(tp) },
        )
    }
    if (pickVoice && s != null) {
        AlertDialog(
            onDismissRequest = { pickVoice = false },
            title = { Text(stringResource(R.string.set_voice_dialog_title)) },
            text = {
                Column {
                    state.data?.voices.orEmpty().forEach { v ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.voice == v.id, onClick = { vm.patch { put("voice", v.id) } })
                            Column(Modifier.weight(1f)) {
                                Text(v.name, style = MaterialTheme.typography.bodyLarge)
                                Text(v.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { graph.voice.preview(v.id) }) { Text(stringResource(R.string.set_try)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickVoice = false }) { Text(stringResource(R.string.common_done)) } },
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
