package app.kairo.android.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.WaterDrop
import app.kairo.android.ui.components.plainPreview
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import app.kairo.android.AppGraph
import app.kairo.android.ui.Destination
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.components.ActionCard
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.KairoOrb
import app.kairo.android.ui.components.MarkdownText
import app.kairo.android.ui.components.OrbState
import app.kairo.android.ui.components.Pill
import app.kairo.android.ui.components.PriorityTag
import app.kairo.android.ui.components.SectionHeader
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.android.voice.VoicePhase
import app.kairo.android.voice.VoiceState
import app.kairo.core.model.Reminder
import app.kairo.core.model.TodayData
import app.kairo.core.session.Session
import app.kairo.core.util.timeLabel
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.launch

class HomeViewModel(graph: AppGraph) : ResourceViewModel<TodayData>(graph, setOf("event", "task", "reminder", "note", "list")) {
    override suspend fun load(): TodayData = graph.api.today().also {
        runCatching { graph.prefs.saveTodayCache(graph.api.json.encodeToString(TodayData.serializer(), it)) }
    }

    init { refresh() }

    fun done(r: Reminder) = mutate("Hecho") { graph.api.reminderDone(r.id) }
    fun snooze(r: Reminder) = mutate("Te aviso en 10 minutos") { graph.api.reminderSnooze(r.id, 10) }
    fun completeTask(id: String) = mutate("Tarea completada") { graph.api.updateTask(id, app.kairo.core.net.KairoApi.obj { put("status", kotlinx.serialization.json.JsonPrimitive("done")) }) }

    fun briefing() {
        viewModelScope.launch {
            runCatching { graph.api.briefing().text }.onSuccess { graph.voice.say(it) }.onFailure { say("No pude preparar el resumen") }
        }
    }
}

@Composable
fun HomeScreen(graph: AppGraph, session: Session, navigate: (Destination) -> Unit) {
    val vm: HomeViewModel = viewModel { HomeViewModel(graph) }
    val state by vm.state.collectAsState()
    val voice by graph.voice.state.collectAsState()
    val adaptive = currentWindowAdaptiveInfo()
    val expanded = adaptive.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val tabletop = adaptive.windowPosture.isTabletop

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) graph.voice.talk() }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val onTalk: () -> Unit = {
        when {
            voice.phase == VoicePhase.Listening || voice.phase == VoicePhase.Thinking || voice.phase == VoicePhase.Speaking -> graph.voice.cancel()
            graph.voice.hasMicPermission() -> graph.voice.talk()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val name = session.user.displayName.split(' ').first()

    when {
        tabletop -> TabletopHome(voice, name, state.data, onTalk, onAsk = { graph.voice.ask(it, speak = false) }, onClear = graph.voice::clearResult, navigate = navigate)
        expanded -> Row(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp).verticalScroll(rememberScrollState())) {
                Greeting(name, state.data)
                Spacer(Modifier.height(20.dp))
                VoicePanel(voice, onTalk, onAsk = { graph.voice.ask(it, speak = false) }, onClear = graph.voice::clearResult, navigate = navigate, orbSize = 240.dp)
            }
            Box(Modifier.weight(1.1f).fillMaxHeight()) {
                TodayList(state.data, state.loading, state.error, vm, navigate, PaddingValues(top = 24.dp, end = 24.dp, bottom = 24.dp, start = 8.dp), header = null)
            }
        }
        else -> TodayList(state.data, state.loading, state.error, vm, navigate, PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), header = {
            Column(Modifier.statusBarsPadding().padding(top = 16.dp)) {
                Greeting(name, state.data)
                Spacer(Modifier.height(16.dp))
                VoicePanel(voice, onTalk, onAsk = { graph.voice.ask(it, speak = false) }, onClear = graph.voice::clearResult, navigate = navigate, orbSize = 168.dp)
            }
        })
    }
}

@Composable
private fun Greeting(name: String, data: TodayData?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(data?.dateLabel ?: " ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${data?.greeting ?: "Hola"}, $name", style = MaterialTheme.typography.headlineMedium)
        data?.weather?.let { w ->
            Pill("${w.place} · ${w.temperature.toInt()}° ${w.description}", icon = weatherIcon(w.description), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun VoiceState.orbState(): OrbState = when {
    error != null && phase == VoicePhase.Off -> OrbState.Error
    phase == VoicePhase.Listening -> OrbState.Listening
    phase == VoicePhase.Thinking -> OrbState.Thinking
    phase == VoicePhase.Speaking -> OrbState.Speaking
    phase == VoicePhase.Armed -> OrbState.Armed
    else -> OrbState.Idle
}

private fun VoiceState.statusText(): String = when (phase) {
    VoicePhase.Listening -> "Te escucho"
    VoicePhase.Thinking -> working ?: "Pensando"
    VoicePhase.Speaking -> "Kairo responde"
    VoicePhase.Armed -> "Di \"Oye Kairo\" o toca para hablar"
    VoicePhase.Off -> "Toca para hablar"
}

@Composable
fun VoicePanel(
    voice: VoiceState,
    onTalk: () -> Unit,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
    navigate: (Destination) -> Unit,
    orbSize: Dp,
    showComposer: Boolean = true,
    showOrb: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val busy = voice.phase == VoicePhase.Listening || voice.phase == VoicePhase.Thinking || voice.phase == VoicePhase.Speaking
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showOrb) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(onClick = onTalk, shape = CircleShape, color = androidx.compose.ui.graphics.Color.Transparent) {
                        KairoOrb(voice.orbState(), voice.level, size = orbSize)
                    }
                }
                Text(voice.statusText(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            voice.transcript?.let {
                Spacer(Modifier.height(10.dp))
                Text("\"$it\"", style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            val reply = voice.reply ?: voice.partialReply.takeIf { it.isNotBlank() }
            AnimatedVisibility(reply != null || voice.actions.isNotEmpty() || voice.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    voice.actions.forEach { ActionCard(it, onOpen = { t -> navigate(Destination.forEntity(t)) }) }
                    reply?.let { MarkdownText(it, style = MaterialTheme.typography.bodyLarge) }
                    voice.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    if (!busy) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Limpiar") }
                        }
                    }
                }
            }
            if (showComposer) {
                if (showOrb) Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Escribe a Kairo") },
                        modifier = Modifier.weight(1f),
                        shape = CircleShape,
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (text.isNotBlank()) { onAsk(text.trim()); text = ""; keyboard?.hide() }
                        }),
                    )
                    Spacer(Modifier.width(8.dp))
                    val size by animateDpAsState(if (busy) 52.dp else 56.dp, label = "mic")
                    FilledIconButton(
                        onClick = { if (text.isNotBlank()) { onAsk(text.trim()); text = ""; keyboard?.hide() } else onTalk() },
                        modifier = Modifier.size(size),
                        shape = if (busy) RoundedCornerShape(16.dp) else CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(
                            when { text.isNotBlank() -> Icons.AutoMirrored.Rounded.Send; busy -> Icons.Rounded.Stop; else -> Icons.Rounded.Mic },
                            contentDescription = if (busy) "Detener" else "Hablar",
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TabletopHome(
    voice: VoiceState,
    name: String,
    data: TodayData?,
    onTalk: () -> Unit,
    onAsk: (String) -> Unit,
    onClear: () -> Unit,
    navigate: (Destination) -> Unit,
) {
    // Modo mesa (Fold semiabierto): el orbe y la respuesta quedan en la mitad superior,
    // los controles en la mitad inferior, sobre la bisagra.
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            KairoOrb(voice.orbState(), voice.level, size = 150.dp)
            Text(voice.statusText(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (voice.reply ?: voice.partialReply.takeIf { it.isNotBlank() })?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${data?.greeting ?: "Hola"}, $name", style = MaterialTheme.typography.titleLarge)
            voice.actions.takeLast(2).forEach { ActionCard(it, onOpen = { t -> navigate(Destination.forEntity(t)) }) }
            Spacer(Modifier.weight(1f))
            VoicePanel(voice.copy(transcript = null, reply = null, partialReply = "", actions = emptyList(), error = null), onTalk, onAsk, onClear, navigate, orbSize = 0.dp, showOrb = false)
        }
    }
}

@Composable
private fun TodayList(
    data: TodayData?,
    loading: Boolean,
    error: String?,
    vm: HomeViewModel,
    navigate: (Destination) -> Unit,
    padding: PaddingValues,
    header: (@Composable () -> Unit)?,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        header?.let { item { it() } }
        error?.let { item { ErrorBanner(it, Modifier.padding(top = 12.dp), onRetry = { vm.refresh() }) } }
        if (loading && data == null) {
            item { SkeletonList(4, Modifier.padding(top = 16.dp)) }
            return@LazyColumn
        }
        val d = data ?: return@LazyColumn
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tu día", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = vm::briefing) { Text("Escuchar resumen") }
            }
        }

        if (d.firedReminders.isNotEmpty()) {
            item { SectionHeader("Sonaron") }
            items(d.firedReminders, key = { "f" + it.id }) { r -> ReminderRow(r, onDone = { vm.done(r) }, onSnooze = { vm.snooze(r) }, highlight = true) }
        }

        item { SectionHeader("Agenda", action = "Calendario", onAction = { navigate(Destination.Calendar) }) }
        if (d.events.isEmpty()) {
            item { Text("Sin eventos hoy", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
        } else {
            items(d.events, key = { "e" + it.id + it.startAt }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (e.allDay) "Todo el día" else e.startAt.toInstantOrNull()?.timeLabel() ?: "",
                        style = MonoStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(84.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge)
                        if (e.location.isNotBlank()) Text(e.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        val tasks = d.tasksOverdue + d.tasksDueToday + d.tasksInProgress.filter { t -> d.tasksDueToday.none { it.id == t.id } && d.tasksOverdue.none { it.id == t.id } }
        item { SectionHeader("Pendientes", action = "Tareas", onAction = { navigate(Destination.Tasks) }) }
        if (tasks.isEmpty()) {
            item { Text("Nada pendiente para hoy", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
        } else {
            items(tasks, key = { "t" + it.id }) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.completeTask(t.id) }) {
                        Icon(Icons.Rounded.Check, "Completar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge)
                        val overdue = d.tasksOverdue.any { it.id == t.id }
                        Text(
                            when { overdue -> "Vencida"; t.status == "doing" -> "En curso"; else -> "Para hoy" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PriorityTag(t.priority)
                }
            }
        }

        if (d.reminders.isNotEmpty()) {
            item { SectionHeader("Próximos avisos", action = "Ver todos", onAction = { navigate(Destination.Reminders) }) }
            items(d.reminders.take(6), key = { "r" + it.id }) { r -> ReminderRow(r, onDone = { vm.done(r) }, onSnooze = null, highlight = false) }
        }

        if (d.lists.isNotEmpty()) {
            item { SectionHeader("Listas", action = "Abrir", onAction = { navigate(Destination.Lists) }) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    d.lists.forEach { l ->
                        Surface(onClick = { navigate(Destination.Lists) }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(l.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${l.items.count { !it.checked }} pendientes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (d.recentNotes.isNotEmpty()) {
            item { SectionHeader("Notas recientes", action = "Notas", onAction = { navigate(Destination.Notes) }) }
            items(d.recentNotes.take(4), key = { "n" + it.id }) { n ->
                Surface(onClick = { navigate(Destination.Notes) }, color = androidx.compose.ui.graphics.Color.Transparent) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(plainPreview(n.content, n.title), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (d.events.isEmpty() && tasks.isEmpty() && d.reminders.isEmpty() && d.firedReminders.isEmpty()) {
            item { EmptyState(Icons.Rounded.Schedule, "Día despejado", "Pídele a Kairo que agende algo o que te recuerde lo importante.") }
        }
    }
}

@Composable
private fun ReminderRow(r: Reminder, onDone: () -> Unit, onSnooze: (() -> Unit)?, highlight: Boolean) {
    Surface(
        color = if (highlight) app.kairo.android.ui.theme.Kairo.colors.accentFill.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.text, style = MaterialTheme.typography.bodyLarge)
                val whenText = r.nextFireAt?.toInstantOrNull()?.timeLabel() ?: r.placeName?.let { "Al llegar a $it" } ?: ""
                if (whenText.isNotBlank()) Text(whenText, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            onSnooze?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Snooze, "Posponer 10 minutos") } }
            IconButton(onClick = onDone) { Icon(Icons.Rounded.Check, "Hecho") }
        }
    }
}

private fun weatherIcon(description: String) = when {
    description.contains("lluvia") || description.contains("llovizna") || description.contains("chubasco") || description.contains("tormenta") -> Icons.Rounded.WaterDrop
    description.contains("nublado") || description.contains("niebla") -> Icons.Rounded.Cloud
    else -> Icons.Rounded.WbSunny
}
