package app.kairo.android.ui.reminders

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.compose.ui.platform.LocalContext
import android.text.format.DateFormat
import app.kairo.android.ui.common.dayLabel
import app.kairo.android.ui.common.recurrenceLabel
import app.kairo.android.ui.common.clockLabel
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kairo.android.AppGraph
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.SectionHeader
import app.kairo.android.ui.components.MemberAvatar
import app.kairo.android.ui.components.Pill
import app.kairo.android.ui.components.SharedBadge
import app.kairo.android.ui.components.ShareRow
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.components.firstNameOf
import app.kairo.android.ui.components.hasOthers
import app.kairo.android.ui.components.owns
import app.kairo.android.ui.components.rememberFamily
import app.kairo.android.ui.components.sharedLabel
import app.kairo.android.family.FamilyStore
import app.kairo.core.model.FamilyInfo
import app.kairo.android.ui.tasks.Labeled
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.model.Place
import app.kairo.core.model.Reminder
import app.kairo.core.net.KairoApi.Companion.obj
import app.kairo.android.ui.common.dayTimeLabel
import app.kairo.core.util.toInstantOrNull
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

data class RemindersData(val reminders: List<Reminder>, val places: List<Place>)

class RemindersViewModel(graph: AppGraph) : ResourceViewModel<RemindersData>(graph, setOf("reminder", "place")) {
    override suspend fun load() = RemindersData(graph.api.reminders(), graph.api.places())

    init { refresh() }

    fun done(r: Reminder) = mutate(str(R.string.home_reminder_done)) { graph.api.reminderDone(r.id) }
    fun snooze(r: Reminder) = mutate(str(R.string.home_snoozed)) { graph.api.reminderSnooze(r.id, 10) }
    fun cancel(r: Reminder) = mutate(str(R.string.rem_cancelled)) { graph.api.reminderCancel(r.id) }
    fun delete(r: Reminder) = mutate(str(R.string.rem_deleted)) { graph.api.deleteReminder(r.id) }

    /** [forUserId]: another member; it rings on their phone and they get a notice. */
    fun create(text: String, at: Instant?, rrule: String?, placeId: String?, trigger: String, forUserId: String?) =
        mutate(forUserId?.let { id -> graph.family.firstName(id)?.let { str(R.string.rem_scheduled_for, it) } } ?: str(R.string.rem_created)) {
            graph.api.createReminder(obj {
                put("text", text)
                at?.let { put("dueAt", it.toString()) }
                rrule?.let { put("rrule", it) }
                placeId?.let { put("placeId", it); put("placeTrigger", trigger) }
                forUserId?.let { put("forUserId", it) }
            })
        }

    fun saveCurrentPlace(name: String, shared: Boolean) {
        val loc = graph.location.last()
        if (loc == null) { say(R.string.rem_no_location); return }
        mutate(str(R.string.rem_place_saved, name)) { graph.api.savePlace(name, loc.first, loc.second, 150, shared) }
    }

    fun sharePlace(p: Place, shared: Boolean) {
        updateData { d -> d.copy(places = d.places.map { if (it.id == p.id) it.copy(shared = shared) else it }) }
        mutate(if (shared) str(R.string.rem_place_shared, p.name) else str(R.string.rem_place_unshared, p.name)) { graph.api.sharePlace(p.id, shared) }
    }

    fun deletePlace(p: Place) = mutate(str(R.string.rem_place_deleted)) { graph.api.deletePlace(p.id) }
}

@Composable
fun RemindersScreen(graph: AppGraph) {
    val vm: RemindersViewModel = viewModel { RemindersViewModel(graph) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newPlace by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if (it.values.any { g -> g }) newPlace = true }
    val family = rememberFamily(graph)

    val all = state.data?.reminders.orEmpty()
    val fired = all.filter { it.status == "fired" }
    val upcoming = all.filter { it.status == "scheduled" && it.rrule == null && it.placeId == null }
    val recurring = all.filter { it.status == "scheduled" && it.rrule != null }
    val byPlace = all.filter { it.status == "scheduled" && it.placeId != null }
    val done = all.filter { it.status == "done" || it.status == "cancelled" }.take(15)

    val titleWentOff = stringResource(R.string.home_went_off)
    val titleUpcoming = stringResource(R.string.rem_group_upcoming)
    val titleRecurring = stringResource(R.string.rem_group_recurring)
    val titleByPlace = stringResource(R.string.rem_group_place)
    val titleDone = stringResource(R.string.rem_group_done)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.rem_new)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Text(stringResource(R.string.nav_reminders), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
            state.error?.let { item { ErrorBanner(it, onRetry = { vm.refresh() }) } }
            if (state.loading && state.data == null) { item { SkeletonList(6) }; return@LazyColumn }
            if (all.none { it.status == "scheduled" || it.status == "fired" }) item {
                EmptyState(Icons.Rounded.NotificationsNone, stringResource(R.string.rem_empty_title), stringResource(R.string.rem_empty_body))
            }
            group(titleWentOff, fired) { ReminderItem(it, vm, family, highlight = true) }
            group(titleUpcoming, upcoming) { ReminderItem(it, vm, family) }
            group(titleRecurring, recurring) { ReminderItem(it, vm, family) }
            group(titleByPlace, byPlace) { ReminderItem(it, vm, family) }
            item { SectionHeader(stringResource(R.string.rem_places), action = stringResource(R.string.rem_save_location), onAction = {
                if (graph.location.hasPermission()) newPlace = true
                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) }
            val places = state.data?.places.orEmpty()
            if (places.isEmpty()) item {
                Text(stringResource(R.string.rem_places_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(places, key = { "p" + it.id }) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.rem_radius, p.radiusM), style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!family.owns(p.ownerId)) sharedLabel(p.shared, p.ownerId, family)?.let { SharedBadge(it) }
                        }
                    }
                    // Whoever saved it decides if it is a family place; everyone else just uses it
                    if (family.owns(p.ownerId) && (family.hasOthers || p.shared)) {
                        FilterChip(
                            selected = p.shared, onClick = { vm.sharePlace(p, !p.shared) },
                            label = { Text(stringResource(R.string.shared_family)) },
                            leadingIcon = if (p.shared) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null,
                        )
                    }
                    IconButton(onClick = { vm.deletePlace(p) }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.rem_delete_place_cd)) }
                }
            }
            if (done.isNotEmpty()) group(titleDone, done) { ReminderItem(it, vm, family, muted = true) }
        }
    }

    if (creating) CreateReminderSheet(state.data?.places.orEmpty(), family, onDismiss = { creating = false }) { text, at, rrule, place, trigger, forUser ->
        vm.create(text, at, rrule, place, trigger, forUser); creating = false
    }
    if (newPlace) {
        var name by rememberSaveable { mutableStateOf("") }
        var shared by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { newPlace = false },
            title = { Text(stringResource(R.string.rem_save_place_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rem_use_location), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.rem_place_name)) }, singleLine = true)
                    if (family.hasOthers) ShareRow(shared, { shared = it }, title = stringResource(R.string.shared_family), body = stringResource(R.string.rem_place_share_body))
                }
            },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { vm.saveCurrentPlace(name.trim(), shared); newPlace = false } }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { newPlace = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.group(title: String, list: List<Reminder>, row: @Composable (Reminder) -> Unit) {
    if (list.isEmpty()) return
    item { SectionHeader(title) }
    items(list, key = { title + it.id }) { row(it) }
}

@Composable
private fun ReminderItem(r: Reminder, vm: RemindersViewModel, family: FamilyInfo?, highlight: Boolean = false, muted: Boolean = false) {
    // For another member (you scheduled it) or from another member (they scheduled it for you)
    val forOther = r.ownerId != null && family != null && r.ownerId != family.me
    val fromOther = r.createdBy != null && family != null && r.createdBy != family.me
    val who = when {
        forOther -> family.firstNameOf(r.ownerId)?.let { stringResource(R.string.rem_for, it) } ?: stringResource(R.string.rem_for_other)
        fromOther -> family.firstNameOf(r.createdBy)?.let { stringResource(R.string.rem_from, it) } ?: stringResource(R.string.rem_from_other)
        else -> null
    }
    Surface(
        color = when { highlight -> app.kairo.android.ui.theme.Kairo.colors.accentFill.copy(alpha = 0.16f); else -> MaterialTheme.colorScheme.surfaceContainerLow },
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when { r.placeId != null -> Icons.Rounded.Place; r.rrule != null -> Icons.Rounded.Repeat; else -> Icons.Rounded.NotificationsActive },
                null, tint = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.text, style = MaterialTheme.typography.bodyLarge, color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                val detail = listOfNotNull(
                    r.nextFireAt?.toInstantOrNull()?.dayTimeLabel()?.replaceFirstChar { it.uppercase() },
                    r.rrule?.recurrenceLabel(),
                    r.placeName?.let { if (r.placeTrigger == "leave") stringResource(R.string.rem_on_leaving, it) else stringResource(R.string.home_on_arrival, it) },
                ).joinToString("  \u00B7  ")
                if (detail.isNotBlank()) Text(detail, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                who?.let { Pill(it, Modifier.padding(top = 4.dp)) }
            }
            if (!muted && !forOther) {
                if (r.status == "fired") IconButton(onClick = { vm.snooze(r) }) { Icon(Icons.Rounded.Snooze, stringResource(R.string.home_snooze_10_cd)) }
                IconButton(onClick = { vm.done(r) }) { Icon(Icons.Rounded.Check, stringResource(R.string.home_reminder_done)) }
            }
            IconButton(onClick = { if (muted) vm.delete(r) else vm.cancel(r) }) { Icon(Icons.Rounded.DeleteOutline, if (muted) stringResource(R.string.common_delete) else stringResource(R.string.common_cancel)) }
        }
    }
}

private enum class Repeat(@StringRes val label: Int) { None(R.string.rec_none), Daily(R.string.rec_daily), Weekdays(R.string.rec_weekdays), Weekly(R.string.rec_weekly), Monthly(R.string.rec_monthly) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateReminderSheet(places: List<Place>, family: FamilyInfo?, onDismiss: () -> Unit, onCreate: (String, Instant?, String?, String?, String, String?) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable { mutableStateOf("") }
    var byPlace by rememberSaveable { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf(LocalTime.now().plusHours(1).withMinute(0).withSecond(0).withNano(0)) }
    var repeat by rememberSaveable { mutableStateOf(Repeat.None) }
    var placeId by rememberSaveable { mutableStateOf(places.firstOrNull()?.id) }
    var trigger by rememberSaveable { mutableStateOf("arrive") }
    var forUser by rememberSaveable { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.rem_new_title), style = MaterialTheme.typography.titleLarge)
            Labeled(stringResource(R.string.rem_f_what)) { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.small) }
            if (family.hasOthers) {
                Labeled(stringResource(R.string.rem_f_for)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = forUser == null, onClick = { forUser = null }, label = { Text(stringResource(R.string.tasks_me)) })
                        family?.others.orEmpty().forEach { m ->
                            FilterChip(
                                selected = forUser == m.id, onClick = { forUser = m.id },
                                label = { Text(FamilyStore.firstNameOf(m.displayName)) },
                                leadingIcon = { MemberAvatar(m.displayName, size = 20.dp) },
                            )
                        }
                    }
                    forUser?.let { id ->
                        Text(stringResource(R.string.rem_for_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!byPlace, { byPlace = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.rem_at_time)) }
                SegmentedButton(byPlace, { byPlace = true }, SegmentedButtonDefaults.itemShape(1, 2), enabled = places.isNotEmpty()) { Text(stringResource(R.string.rem_at_place)) }
            }
            if (!byPlace) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickDate = true }) { Text(date.dayLabel().replaceFirstChar { it.uppercase() }) }
                    TextButton(onClick = { pickTime = true }) { Text(time.clockLabel(), style = MonoStyle) }
                }
                Labeled(stringResource(R.string.rem_f_repeat)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Repeat.entries.forEach { r -> FilterChip(selected = repeat == r, onClick = { repeat = r }, label = { Text(stringResource(r.label)) }) }
                    }
                }
            } else {
                Labeled(stringResource(R.string.rem_f_place)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        places.forEach { p -> FilterChip(selected = placeId == p.id, onClick = { placeId = p.id }, label = { Text(p.name) }, leadingIcon = { Icon(Icons.Rounded.MyLocation, null, Modifier.size(16.dp)) }) }
                    }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(trigger == "arrive", { trigger = "arrive" }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.rem_on_arrive)) }
                    SegmentedButton(trigger == "leave", { trigger = "leave" }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.rem_on_leave)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    enabled = text.isNotBlank() && (!byPlace || placeId != null),
                    onClick = {
                        val at = if (byPlace) null else date.atTime(time).atZone(ZoneId.systemDefault()).toInstant()
                        val day = date.dayOfWeek
                        val rule = if (byPlace) null else when (repeat) {
                            Repeat.None -> null
                            Repeat.Daily -> "FREQ=DAILY"
                            Repeat.Weekdays -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
                            Repeat.Weekly -> "FREQ=WEEKLY;BYDAY=" + day.code()
                            Repeat.Monthly -> "FREQ=MONTHLY;BYMONTHDAY=" + date.dayOfMonth
                        }
                        onCreate(text.trim(), at, rule, if (byPlace) placeId else null, trigger, forUser)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Text(stringResource(R.string.rem_create)) }
            }
        }
    }
    if (pickDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { dp.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text(stringResource(R.string.common_done)) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(dp) }
    }
    if (pickTime) {
        val tp = rememberTimePickerState(time.hour, time.minute, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(tp.hour, tp.minute); pickTime = false }) { Text(stringResource(R.string.common_done)) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.common_cancel)) } },
            text = { TimePicker(tp, Modifier.widthIn(max = 360.dp)) },
        )
    }
}

private fun DayOfWeek.code() = when (this) {
    DayOfWeek.MONDAY -> "MO"; DayOfWeek.TUESDAY -> "TU"; DayOfWeek.WEDNESDAY -> "WE"; DayOfWeek.THURSDAY -> "TH"
    DayOfWeek.FRIDAY -> "FR"; DayOfWeek.SATURDAY -> "SA"; DayOfWeek.SUNDAY -> "SU"
}
