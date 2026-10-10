package app.kairo.android.ui.calendar

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import app.kairo.android.AppGraph
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.tasks.Labeled
import app.kairo.android.ui.theme.Kairo
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.model.Event
import app.kairo.core.net.KairoApi.Companion.obj
import app.kairo.core.util.Spanish
import app.kairo.core.util.longLabel
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
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters

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

    fun create(title: String, start: Instant, end: Instant, allDay: Boolean, location: String) = mutate("Evento creado") {
        graph.api.createEvent(obj {
            put("title", title); put("startAt", start.toString()); put("endAt", end.toString())
            put("allDay", allDay); put("location", location)
        })
    }

    fun delete(e: Event) = mutate("Evento eliminado. Puedes deshacerlo en Actividad.") { graph.api.deleteEvent(e.id) }
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
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Evento") },
                containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    month.month.getDisplayName(TextStyle.FULL, Spanish).replaceFirstChar { it.uppercase() } + " " + month.year,
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.setMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Mes anterior") }
                TextButton(onClick = { vm.setMonth(YearMonth.now()); selected = LocalDate.now().toString() }) { Text("Hoy") }
                IconButton(onClick = { vm.setMonth(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Mes siguiente") }
            }
            state.error?.let { ErrorBanner(it, Modifier.padding(16.dp), onRetry = { vm.refresh() }) }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    MonthGrid(month, day, { d -> eventsOn(d).size }, Modifier.weight(1f).padding(8.dp)) { selected = it.toString() }
                    Agenda(day, eventsOn(day), state.loading && state.data == null, Modifier.weight(1f)) { detail = it }
                }
            } else {
                MonthGrid(month, day, { d -> eventsOn(d).size }, Modifier.padding(horizontal = 12.dp)) { selected = it.toString() }
                Agenda(day, eventsOn(day), state.loading && state.data == null, Modifier.weight(1f)) { detail = it }
            }
        }
    }

    if (creating) EventSheet(day, onDismiss = { creating = false }) { t, s, e, all, loc -> vm.create(t, s, e, all, loc); creating = false }
    detail?.let { e ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(e.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val s = e.startAt.toInstantOrNull()
                    Text(if (e.allDay) "Todo el día" else "${s?.timeLabel()} a ${e.endAt.toInstantOrNull()?.timeLabel()}", style = MonoStyle)
                    if (e.location.isNotBlank()) Text(e.location)
                    if (e.description.isNotBlank()) Text(e.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (e.source == "google") Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(6.dp))
                        Text("Sincronizado con Google Calendar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Cerrar") } },
            dismissButton = { TextButton(onClick = { vm.delete(e); detail = null }) { Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Eliminar") } },
        )
    }
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, count: (LocalDate) -> Int, modifier: Modifier, onSelect: (LocalDate) -> Unit) {
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val today = LocalDate.now()
    Column(modifier.widthIn(max = 560.dp)) {
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { d ->
                Text(d.getDisplayName(TextStyle.NARROW, Spanish).uppercase(), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Surface(
                            onClick = { onSelect(d) },
                            shape = CircleShape,
                            color = when { isSel -> MaterialTheme.colorScheme.primaryContainer; d == today -> MaterialTheme.colorScheme.surfaceContainerHighest; else -> androidx.compose.ui.graphics.Color.Transparent },
                            modifier = Modifier.size(40.dp),
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
private fun Agenda(day: LocalDate, events: List<Event>, loading: Boolean, modifier: Modifier, onOpen: (Event) -> Unit) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(day.longLabel(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp)) }
        if (loading) item { SkeletonList(3) }
        else if (events.isEmpty()) item { EmptyState(Icons.Rounded.CalendarMonth, "Día libre", "Dile a Kairo \"agéndame...\" para crear un evento.") }
        items(events, key = { it.id + it.startAt }) { e ->
            Surface(onClick = { onOpen(e) }, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).height(36.dp).clip(CircleShape).background(if (e.source == "google") MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (e.allDay) "Todo el día" else "${e.startAt.toInstantOrNull()?.timeLabel()} a ${e.endAt.toInstantOrNull()?.timeLabel()}",
                            style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (e.location.isNotBlank()) Text(e.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventSheet(day: LocalDate, onDismiss: () -> Unit, onCreate: (String, Instant, Instant, Boolean, String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by rememberSaveable { mutableStateOf("") }
    var location by rememberSaveable { mutableStateOf("") }
    var allDay by rememberSaveable { mutableStateOf(false) }
    var date by remember { mutableStateOf(day) }
    var start by remember { mutableStateOf(LocalTime.of(10, 0)) }
    var end by remember { mutableStateOf(LocalTime.of(11, 0)) }
    var pick by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Nuevo evento", style = MaterialTheme.typography.titleLarge)
            Labeled("Título") { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.small) }
            Labeled("Lugar") { OutlinedTextField(location, { location = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.small) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Todo el día", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(allDay, { allDay = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { pick = "date" }) { Text(date.longLabel()) }
                if (!allDay) {
                    TextButton(onClick = { pick = "start" }) { Text("%02d:%02d".format(start.hour, start.minute), style = MonoStyle) }
                    Text("a")
                    TextButton(onClick = { pick = "end" }) { Text("%02d:%02d".format(end.hour, end.minute), style = MonoStyle) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                Button(
                    enabled = title.isNotBlank(),
                    onClick = {
                        val zone = ZoneId.systemDefault()
                        val s = if (allDay) date.atStartOfDay(zone).toInstant() else date.atTime(start).atZone(zone).toInstant()
                        val e = if (allDay) date.plusDays(1).atStartOfDay(zone).toInstant() else date.atTime(if (end.isAfter(start)) end else start.plusHours(1)).atZone(zone).toInstant()
                        onCreate(title.trim(), s, e, allDay, location.trim())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Text("Crear") }
            }
        }
    }
    when (pick) {
        "date" -> {
            val dp = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { pick = null },
                confirmButton = { TextButton(onClick = { dp.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pick = null }) { Text("Listo") } },
                dismissButton = { TextButton(onClick = { pick = null }) { Text("Cancelar") } },
            ) { DatePicker(dp) }
        }
        "start", "end" -> {
            val initial = if (pick == "start") start else end
            val tp = rememberTimePickerState(initial.hour, initial.minute, is24Hour = true)
            AlertDialog(
                onDismissRequest = { pick = null },
                confirmButton = {
                    TextButton(onClick = {
                        val t = LocalTime.of(tp.hour, tp.minute)
                        if (pick == "start") { val dur = java.time.Duration.between(start, end); start = t; end = t.plus(if (dur.isNegative || dur.isZero) java.time.Duration.ofHours(1) else dur) } else end = t
                        pick = null
                    }) { Text("Listo") }
                },
                dismissButton = { TextButton(onClick = { pick = null }) { Text("Cancelar") } },
                text = { TimePicker(tp) },
            )
        }
    }
}
