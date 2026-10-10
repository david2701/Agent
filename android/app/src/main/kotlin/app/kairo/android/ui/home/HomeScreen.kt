package app.kairo.android.ui.home

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import app.kairo.android.ui.common.greeting
import app.kairo.android.ui.common.longDateLabel
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
import app.kairo.android.ui.components.rememberFamily
import app.kairo.android.ui.components.sharedLabel
import app.kairo.android.ui.family.FamilyHomeCard
import app.kairo.android.sync.ServerEvents
import app.kairo.core.model.FamilyInfo
import app.kairo.core.model.Notice
import app.kairo.core.model.NoticesResponse
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.android.voice.VoicePhase
import app.kairo.android.voice.VoiceState
import app.kairo.core.model.Reminder
import app.kairo.core.model.TodayData
import app.kairo.core.session.Session
import app.kairo.core.util.timeLabel
import app.kairo.core.util.toLocalDateOrNull
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.launch

class HomeViewModel(graph: AppGraph) : ResourceViewModel<TodayData>(graph, setOf("event", "task", "reminder", "note", "list", "notice", "family")) {
    /** Latest unread family notices for the "Family" card. */
    val notices = kotlinx.coroutines.flow.MutableStateFlow<NoticesResponse?>(null)

    override suspend fun load(): TodayData {
        runCatching { graph.api.notices(unread = true, limit = 3) }.onSuccess { notices.value = it }
        return graph.api.today().also {
            runCatching { graph.prefs.saveTodayCache(graph.api.json.encodeToString(TodayData.serializer(), it)) }
        }
    }

    init { refresh() }

    fun openNotice(n: Notice) {
        notices.value = notices.value?.let { r -> NoticesResponse(r.notices.filter { it.id != n.id }, (r.unread - 1).coerceAtLeast(0)) }
        mutate { graph.api.markNoticesRead(listOf(n.id)) }
    }

    fun done(r: Reminder) = mutate(str(R.string.home_reminder_done)) { graph.api.reminderDone(r.id) }
    fun snooze(r: Reminder) = mutate(str(R.string.home_snoozed)) { graph.api.reminderSnooze(r.id, 10) }
    fun completeTask(id: String) = mutate(str(R.string.home_task_completed)) { graph.api.updateTask(id, app.kairo.core.net.KairoApi.obj { put("status", kotlinx.serialization.json.JsonPrimitive("done")) }) }

    fun briefing() {
        viewModelScope.launch {
            runCatching { graph.api.briefing().text }.onSuccess { graph.voice.say(it) }.onFailure { say(R.string.home_briefing_failed) }
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
    val family = rememberFamily(graph)
    val notices by vm.notices.collectAsState()
    val familyCard = FamilyCardModel(family, notices) { n ->
        vm.openNotice(n)
        navigate(Destination.fromRoute(ServerEvents.destinationFor(n.entityType)) ?: Destination.Family)
    }

    when {
        tabletop -> TabletopHome(voice, name, state.data, onTalk, onAsk = { graph.voice.ask(it, speak = false) }, onClear = graph.voice::clearResult, navigate = navigate)
        expanded -> Row(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp).verticalScroll(rememberScrollState())) {
                Greeting(name, state.data)
                Spacer(Modifier.height(20.dp))
                VoicePanel(voice, onTalk, onAsk = { graph.voice.ask(it, speak = false) }, onClear = graph.voice::clearResult, navigate = navigate, orbSize = 240.dp)
            }
            Box(Modifier.weight(1.1f).fillMaxHeight()) {
                TodayList(state.data, state.loading, state.error, vm, navigate, familyCard, PaddingValues(top = 24.dp, end = 24.dp, bottom = 24.dp, start = 8.dp), header = null)
            }
        }
        else -> TodayList(state.data, state.loading, state.error, vm, navigate, familyCard, PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), header = {
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
        Text(data?.date?.toLocalDateOrNull()?.longDateLabel() ?: " ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(LocalContext.current.greeting(name), style = MaterialTheme.typography.headlineMedium)
        data?.weather?.let { w ->
            Pill("${w.place} \u00B7 ${w.temperature.toInt()}° ${w.description}", icon = weatherIcon(w.description), modifier = Modifier.padding(top = 6.dp))
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

@Composable
private fun VoiceState.statusText(): String = when (phase) {
    VoicePhase.Listening -> stringResource(R.string.home_status_listening)
    VoicePhase.Thinking -> working ?: stringResource(R.string.home_status_thinking)
    VoicePhase.Speaking -> stringResource(R.string.home_status_speaking)
    VoicePhase.Armed -> stringResource(R.string.home_status_armed, stringResource(R.string.wake_phrase))
    VoicePhase.Off -> stringResource(R.string.home_status_idle)
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
                            IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, stringResource(R.string.home_clear)) }
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
                        placeholder = { Text(stringResource(R.string.home_ask_placeholder)) },
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
                            contentDescription = if (busy) stringResource(R.string.home_stop) else stringResource(R.string.home_talk),
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
    // Tabletop mode (half-open Fold): the orb and the reply stay in the upper half,
    // the controls in the lower half, over the hinge.
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
            Text(LocalContext.current.greeting(name), style = MaterialTheme.typography.titleLarge)
            voice.actions.takeLast(2).forEach { ActionCard(it, onOpen = { t -> navigate(Destination.forEntity(t)) }) }
            Spacer(Modifier.weight(1f))
            VoicePanel(voice.copy(transcript = null, reply = null, partialReply = "", actions = emptyList(), error = null), onTalk, onAsk, onClear, navigate, orbSize = 0.dp, showOrb = false)
        }
    }
}

/** What the Today "Family" card needs. */
private class FamilyCardModel(val family: FamilyInfo?, val notices: NoticesResponse?, val onNotice: (Notice) -> Unit)

@Composable
private fun TodayList(
    data: TodayData?,
    loading: Boolean,
    error: String?,
    vm: HomeViewModel,
    navigate: (Destination) -> Unit,
    familyCard: FamilyCardModel,
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
                Text(stringResource(R.string.home_your_day), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = vm::briefing) { Text(stringResource(R.string.home_listen_briefing)) }
            }
        }

        if (d.firedReminders.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_went_off)) }
            items(d.firedReminders, key = { "f" + it.id }) { r -> ReminderRow(r, onDone = { vm.done(r) }, onSnooze = { vm.snooze(r) }, highlight = true) }
        }

        item(key = "family") { FamilyHomeCard(familyCard.family, familyCard.notices, onOpen = { navigate(Destination.Family) }, onNotice = familyCard.onNotice) }

        item { SectionHeader(stringResource(R.string.home_agenda), action = stringResource(R.string.nav_calendar), onAction = { navigate(Destination.Calendar) }) }
        if (d.events.isEmpty()) {
            item { Text(stringResource(R.string.home_no_events), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
        } else {
            items(d.events, key = { "e" + it.id + it.startAt }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (e.allDay) stringResource(R.string.all_day) else e.startAt.toInstantOrNull()?.timeLabel() ?: "",
                        style = MonoStyle, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(84.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge)
                        val meta = listOfNotNull(e.location.takeIf { it.isNotBlank() }, sharedLabel(e.shared, e.ownerId, familyCard.family)).joinToString(" \u00B7 ")
                        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        val tasks = d.tasksOverdue + d.tasksDueToday + d.tasksInProgress.filter { t -> d.tasksDueToday.none { it.id == t.id } && d.tasksOverdue.none { it.id == t.id } }
        item { SectionHeader(stringResource(R.string.home_pending), action = stringResource(R.string.nav_tasks), onAction = { navigate(Destination.Tasks) }) }
        if (tasks.isEmpty()) {
            item { Text(stringResource(R.string.home_nothing_pending), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)) }
        } else {
            items(tasks, key = { "t" + it.id }) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.completeTask(t.id) }) {
                        Icon(Icons.Rounded.Check, stringResource(R.string.home_complete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge)
                        val overdue = d.tasksOverdue.any { it.id == t.id }
                        Text(
                            when { overdue -> stringResource(R.string.home_task_overdue); t.status == "doing" -> stringResource(R.string.home_task_in_progress); else -> stringResource(R.string.home_task_due_today) },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PriorityTag(t.priority)
                }
            }
        }

        if (d.reminders.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_upcoming), action = stringResource(R.string.home_see_all), onAction = { navigate(Destination.Reminders) }) }
            items(d.reminders.take(6), key = { "r" + it.id }) { r -> ReminderRow(r, onDone = { vm.done(r) }, onSnooze = null, highlight = false) }
        }

        if (d.lists.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.nav_lists), action = stringResource(R.string.home_open), onAction = { navigate(Destination.Lists) }) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    d.lists.forEach { l ->
                        Surface(onClick = { navigate(Destination.Lists) }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(l.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(pluralStringResource(R.plurals.home_list_pending, l.items.count { !it.checked }, l.items.count { !it.checked }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (d.recentNotes.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.home_recent_notes), action = stringResource(R.string.nav_notes), onAction = { navigate(Destination.Notes) }) }
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
            item { EmptyState(Icons.Rounded.Schedule, stringResource(R.string.home_clear_day), stringResource(R.string.home_clear_day_body)) }
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
                val whenText = r.nextFireAt?.toInstantOrNull()?.timeLabel() ?: r.placeName?.let { stringResource(R.string.home_on_arrival, it) } ?: ""
                if (whenText.isNotBlank()) Text(whenText, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            onSnooze?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Snooze, stringResource(R.string.home_snooze_10_cd)) } }
            IconButton(onClick = onDone) { Icon(Icons.Rounded.Check, stringResource(R.string.home_reminder_done)) }
        }
    }
}

/** The weather description is written by the server in the account language (English or Spanish). */
private fun weatherIcon(description: String): ImageVector {
    val d = description.lowercase()
    val rain = listOf("rain", "drizzle", "shower", "thunder", "storm", "lluvia", "llovizna", "chubasco", "tormenta")
    val cloud = listOf("cloud", "overcast", "fog", "mist", "nublado", "niebla")
    return when {
        rain.any { it in d } -> Icons.Rounded.WaterDrop
        cloud.any { it in d } -> Icons.Rounded.Cloud
        else -> Icons.Rounded.WbSunny
    }
}
