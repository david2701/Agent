package app.kairo.android.ui.calendar

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateFormat
import app.kairo.android.ui.common.longDateLabel
import app.kairo.android.ui.common.clockLabel
import app.kairo.android.ui.common.rememberFormatLocale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import app.kairo.android.AppGraph
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.ShareRow
import app.kairo.android.ui.components.SharedInfoRow
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.components.firstNameOf
import app.kairo.android.ui.components.hasOthers
import app.kairo.android.ui.components.owns
import app.kairo.android.ui.components.rememberFamily
import app.kairo.core.model.FamilyInfo
import app.kairo.android.ui.tasks.Labeled
import app.kairo.android.ui.theme.Kairo
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.model.Event
import app.kairo.core.net.KairoApi.Companion.obj
import app.kairo.core.util.firstDayOfWeek
import app.kairo.core.util.monthYearLabel
import app.kairo.core.util.narrowLabel
import app.kairo.core.util.timeLabel
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** What the event sheet saves. */
data class EventEdit(val title: String, val start: Instant, val end: Instant, val allDay: Boolean, val location: String, val shared: Boolean)

class CalendarViewModel(graph: AppGraph) : ResourceViewModel<List<Event>>(graph, setOf("event")) {
    val month = MutableStateFlow(YearMonth.now())

    override suspend fun load(): List<Event> {
        val m = month.value
        val zone = ZoneId.systemDefault()
        val from = m.atDay(1).minusDays(7).atStartOfDay(zone).toInstant()
        val to = m.atEndOfMonth().plusDays(8).atStartOfDay(zone).toInstant()
        return graph.api.events(from.toString(), to.toString())
    }

    init { refresh() }

    fun setMonth(m: YearMonth) { if (m != month.value) { month.value = m; refresh() } }

    fun save(existing: Event?, e: EventEdit) = mutate(if (existing == null) (if (e.shared) str(R.string.cal_created_family) else str(R.string.cal_created)) else str(R.string.cal_saved)) {
        if (existing == null) {
            graph.api.createEvent(obj {
                put("title", e.title); put("startAt", e.start.toString()); put("endAt", e.end.toString())
                put("allDay", e.allDay); put("location", e.location)
                if (e.shared) put("shared", true)
            })
        } else {
            graph.api.updateEvent(existing.id, obj {
                put("title", e.title); put("location", e.location)
                // For recurring events every occurrence arrives with the same id: the time is changed on the series from the dashboard
                if (existing.rrule == null) { put("startAt", e.start.toString()); put("endAt", e.end.toString()); put("allDay", e.allDay) }
                if (e.shared != existing.shared) put("shared", e.shared)
            })
        }
    }

    fun delete(e: Event) = mutate(str(R.string.cal_deleted)) { graph.api.deleteEvent(e.id) }
}

@Composable
fun CalendarScreen(graph: AppGraph) {
    val vm: CalendarViewModel = viewModel { CalendarViewModel(graph) }
    val state by vm.state.collectAsState()
    val month by vm.month.collectAsState()
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val day = LocalDate.parse(selected)
    var creating by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Event?>(null) }
    var editing by remember { mutableStateOf<Event?>(null) }
    val family = rememberFamily(graph)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val wide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val zone = ZoneId.systemDefault()
    val events = state.data.orEmpty()
    fun eventsOn(d: LocalDate) = events.filter { e ->
        val s = e.startAt.toInstantOrNull()?.atZone(zone)?.toLocalDate()
        val en = e.endAt.toInstantOrNull()?.atZone(zone)?.toLocalDate()
        s != null && (s == d || (en != null && s.isBefore(d) && en.isAfter(d)))
    }.sortedBy { it.startAt }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.cal_event)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    month.monthYearLabel(rememberFormatLocale()),
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.setMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.cal_prev_month)) }
                TextButton(onClick = { vm.setMonth(YearMonth.now()); selected = LocalDate.now().toString() }) { Text(stringResource(R.string.cal_today)) }
                IconButton(onClick = { vm.setMonth(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.cal_next_month)) }
            }
            state.error?.let { ErrorBanner(it, Modifier.padding(16.dp), onRetry = { vm.refresh() }) }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    MonthGrid(month, day, { d -> eventsOn(d).size }, Modifier.weight(1f).padding(8.dp)) { selected = it.toString() }
                    Agenda(day, eventsOn(day), state.loading && state.data == null, family, Modifier.weight(1f)) { detail = it }
                }
            } else {
                MonthGrid(month, day, { d -> eventsOn(d).size }, Modifier.padding(horizontal = 12.dp)) { selected = it.toString() }
                Agenda(day, eventsOn(day), state.loading && state.data == null, family, Modifier.weight(1f)) { detail = it }
            }
        }
    }

    if (creating || editing != null) {
        EventSheet(day, editing, family, onDismiss = { creating = false; editing = null }) { edit -> vm.save(editing, edit); creating = false; editing = null }
    }
    detail?.let { e ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(e.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val s = e.startAt.toInstantOrNull()
                    Text(if (e.allDay) stringResource(R.string.all_day) else stringResource(R.string.time_range, s?.timeLabel() ?: "", e.endAt.toInstantOrNull()?.timeLabel() ?: ""), style = MonoStyle)
                    if (e.location.isNotBlank()) Text(e.location)
                    if (e.description.isNotBlank()) Text(e.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (e.shared) Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Groups, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(6.dp))
                        Text(
                            if (family.owns(e.ownerId)) stringResource(R.string.cal_family_event) else stringResource(R.string.cal_family_event_by, family.firstNameOf(e.ownerId) ?: stringResource(R.string.tasks_another_member)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (e.source == "google") Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.cal_synced), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { editing = e; detail = null }) { Text(stringResource(R.string.common_edit)) }
                    TextButton(onClick = { detail = null }) { Text(stringResource(R.string.common_close)) }
                }
            },
            dismissButton = { TextButton(onClick = { vm.delete(e); detail = null }) { Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.common_delete)) } },
        )
    }
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, count: (LocalDate) -> Int, modifier: Modifier, onSelect: (LocalDate) -> Unit) {
    val locale = rememberFormatLocale()
    val firstDay = locale.firstDayOfWeek()
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay))
    val today = LocalDate.now()
    Column(modifier.widthIn(max = 560.dp)) {
        Row(Modifier.fillMaxWidth()) {
            (0L until 7L).map { firstDay.plus(it) }.forEach { d ->
                Text(d.narrowLabel(locale), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        for (week in 0 until 6) {
            val weekStart = first.plusDays(week * 7L)
            if (week == 5 && weekStart.month != month.month) break
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = weekStart.plusDays(i.toLong())
                    val inMonth = d.month == month.month
                    val isSel = d == selected
                    val n = count(d)
                    Box(Modifier.weight(1f).aspectRatio(1.1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        val label = if (n == 0) d.longDateLabel() else pluralStringResource(R.plurals.cal_day_cd, n, d.longDateLabel(), n)
                        Surface(
                            onClick = { onSelect(d) },
                            shape = CircleShape,
                            color = when { isSel -> MaterialTheme.colorScheme.primaryContainer; d == today -> MaterialTheme.colorScheme.surfaceContainerHighest; else -> androidx.compose.ui.graphics.Color.Transparent },
                            modifier = Modifier.size(40.dp).semantics { contentDescription = label },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "${d.dayOfMonth}", style = MonoStyle,
                                    color = when { isSel -> MaterialTheme.colorScheme.onPrimaryContainer; inMonth -> MaterialTheme.colorScheme.onSurface; else -> Kairo.colors.faint },
                                )
                                if (n > 0) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp).size(4.dp).clip(CircleShape).background(if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Agenda(day: LocalDate, events: List<Event>, loading: Boolean, family: FamilyInfo?, modifier: Modifier, onOpen: (Event) -> Unit) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(day.longDateLabel(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp)) }
        if (loading) item { SkeletonList(3) }
        else if (events.isEmpty()) item { EmptyState(Icons.Rounded.CalendarMonth, stringResource(R.string.cal_free_day), stringResource(R.string.cal_free_day_body)) }
        items(events, key = { it.id + it.startAt }) { e ->
            Surface(onClick = { onOpen(e) }, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(36.dp).clip(CircleShape).background(if (e.source == "google") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (e.allDay) stringResource(R.string.all_day) else stringResource(R.string.time_range, e.startAt.toInstantOrNull()?.timeLabel() ?: "", e.endAt.toInstantOrNull()?.timeLabel() ?: ""),
                            style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (e.location.isNotBlank()) Text(e.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Family event: same color as the rest, marked with the icon and who scheduled it
                        if (e.shared) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Groups, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(4.dp))
                            Text(
                                if (family.owns(e.ownerId)) stringResource(R.string.shared_family) else stringResource(R.string.cal_family_by, family.firstNameOf(e.ownerId) ?: stringResource(R.string.tasks_another_member)),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventSheet(day: LocalDate, event: Event?, family: FamilyInfo?, onDismiss: () -> Unit, onSave: (EventEdit) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zone = ZoneId.systemDefault()
    val startAt = event?.startAt?.toInstantOrNull()?.atZone(zone)
    val endAt = event?.endAt?.toInstantOrNull()?.atZone(zone)
    var title by rememberSaveable { mutableStateOf(event?.title ?: "") }
    var location by rememberSaveable { mutableStateOf(event?.location ?: "") }
    var allDay by rememberSaveable { mutableStateOf(event?.allDay ?: false) }
    var shared by rememberSaveable { mutableStateOf(event?.shared ?: false) }
    var date by remember { mutableStateOf(startAt?.toLocalDate() ?: day) }
    var start by remember { mutableStateOf(startAt?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(10, 0)) }
    var end by remember { mutableStateOf(endAt?.toLocalTime()?.withSecond(0)?.withNano(0) ?: LocalTime.of(11, 0)) }
    var pick by remember { mutableStateOf<String?>(null) }
    val recurring = event?.rrule != null
    val canUnshare = family.owns(event?.ownerId)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (event == null) stringResource(R.string.cal_new_event) else stringResource(R.string.cal_edit_event), style = MaterialTheme.typography.titleLarge)
            Labeled(stringResource(R.string.field_title)) { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.small) }
            Labeled(stringResource(R.string.cal_f_location)) { OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.small) }
            if (recurring) {
                Text(stringResource(R.string.cal_recurring_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.all_day), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(allDay, { allDay = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pick = "date" }) { Text(date.longDateLabel()) }
                    if (!allDay) {
                        TextButton(onClick = { pick = "start" }) { Text(start.clockLabel(), style = MonoStyle) }
                        Text(stringResource(R.string.time_to))
                        TextButton(onClick = { pick = "end" }) { Text(end.clockLabel(), style = MonoStyle) }
                    }
                }
            }
            if (family.hasOthers || event?.shared == true) {
                if (!canUnshare && event?.shared == true) {
                    SharedInfoRow(stringResource(R.string.cal_shared_by_other, family.firstNameOf(event.ownerId) ?: stringResource(R.string.tasks_another_member)), title = stringResource(R.string.cal_family_event))
                } else {
                    ShareRow(shared, { shared = it }, title = stringResource(R.string.cal_family_event), body = stringResource(R.string.cal_share_body))
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    enabled = title.isNotBlank(),
                    onClick = {
                        val s = if (allDay) date.atStartOfDay(zone).toInstant() else date.atTime(start).atZone(zone).toInstant()
                        val e = if (allDay) date.plusDays(1).atStartOfDay(zone).toInstant() else date.atTime(if (end.isAfter(start)) end else start.plusHours(1)).atZone(zone).toInstant()
                        onSave(EventEdit(title.trim(), s, e, allDay, location.trim(), shared))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Text(if (event == null) stringResource(R.string.rem_create) else stringResource(R.string.common_save)) }
            }
        }
    }
    when (pick) {
        "date" -> {
            val dp = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { pick = null },
                confirmButton = { TextButton(onClick = { dp.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pick = null }) { Text(stringResource(R.string.common_done)) } },
                dismissButton = { TextButton(onClick = { pick = null }) { Text(stringResource(R.string.common_cancel)) } },
            ) { DatePicker(dp) }
        }
        "start", "end" -> {
            val initial = if (pick == "start") start else end
            val tp = rememberTimePickerState(initial.hour, initial.minute, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
            AlertDialog(
                onDismissRequest = { pick = null },
                confirmButton = {
                    TextButton(onClick = {
                        val t = LocalTime.of(tp.hour, tp.minute)
                        if (pick == "start") { val dur = java.time.Duration.between(start, end); start = t; end = t.plus(if (dur.isNegative || dur.isZero) java.time.Duration.ofHours(1) else dur) } else end = t
                        pick = null
                    }) { Text(stringResource(R.string.common_done)) }
                },
                dismissButton = { TextButton(onClick = { pick = null }) { Text(stringResource(R.string.common_cancel)) } },
                text = { TimePicker(tp) },
            )
        }
    }
}
