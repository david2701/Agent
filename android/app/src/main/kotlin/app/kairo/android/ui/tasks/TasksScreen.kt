package app.kairo.android.ui.tasks

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.TaskAlt
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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import app.kairo.android.AppGraph
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.MemberAvatar
import app.kairo.android.ui.components.PriorityTag
import app.kairo.android.ui.components.SharedBadge
import app.kairo.android.ui.components.ShareRow
import app.kairo.android.ui.components.SharedInfoRow
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.components.firstNameOf
import app.kairo.android.ui.components.hasOthers
import app.kairo.android.ui.components.owns
import app.kairo.android.ui.components.rememberFamily
import app.kairo.android.ui.components.sharedLabel
import app.kairo.android.family.FamilyStore
import app.kairo.core.model.FamilyInfo
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.model.Project
import app.kairo.core.model.Task
import app.kairo.core.net.KairoApi.Companion.obj
import app.kairo.core.util.dayLabel
import app.kairo.core.util.toLocalDateOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class TasksData(val tasks: List<Task>, val projects: List<Project>)

class TasksViewModel(graph: AppGraph) : ResourceViewModel<TasksData>(graph, setOf("task", "project")) {
    /** Filtro "Asignadas a mí": lo resuelve el servidor (`GET /tasks?assignee=me`). */
    val assignedToMe = kotlinx.coroutines.flow.MutableStateFlow(false)

    override suspend fun load() = TasksData(graph.api.tasks(includeDone = true, assignee = if (assignedToMe.value) "me" else null), graph.api.projects())

    init { refresh() }

    fun setAssignedToMe(on: Boolean) {
        if (assignedToMe.value == on) return
        assignedToMe.value = on
        refresh()
    }

    fun setStatus(t: Task, status: String) {
        updateData { d -> d.copy(tasks = d.tasks.map { if (it.id == t.id) it.copy(status = status) else it }) }
        mutate(if (status == "done") "Tarea completada" else null) { graph.api.updateTask(t.id, obj { put("status", status) }) }
    }

    fun delete(t: Task) = mutate("Tarea eliminada. Puedes deshacerlo en Actividad.") { graph.api.deleteTask(t.id) }

    fun save(existing: Task?, edit: TaskEdit) = mutate(if (existing == null) "Tarea creada" else "Tarea guardada") {
        val (title, notes, priority, due, project) = edit
        val body = obj {
            put("title", title)
            put("notes", notes)
            put("priority", priority)
            put("dueDate", due?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            if (!project.isNullOrBlank()) put("project", project)
            if (edit.shared) put("shared", true)
            edit.assigneeId?.let { put("assigneeId", it) }
        }
        if (existing == null) {
            graph.api.createTask(body)
        } else {
            val name = project?.trim()?.takeIf { it.isNotEmpty() }
            val projectId = name?.let { n ->
                state.value.data?.projects?.firstOrNull { it.name.equals(n, true) }?.id ?: graph.api.createProject(n).id
            }
            graph.api.updateTask(existing.id, obj {
                put("title", title); put("notes", notes); put("priority", priority)
                put("dueDate", due?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                put("projectId", projectId?.let { JsonPrimitive(it) } ?: JsonNull)
                // Solo lo que cambió: compartir y asignar generan avisos para la familia
                if (edit.shared != existing.shared) put("shared", edit.shared)
                if (edit.assigneeId != existing.assigneeId) put("assigneeId", edit.assigneeId?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }
    }
}

/** Lo que guarda el editor de tareas. */
data class TaskEdit(
    val title: String,
    val notes: String,
    val priority: String,
    val due: LocalDate?,
    val project: String?,
    val shared: Boolean = false,
    val assigneeId: String? = null,
)

private val COLUMNS = listOf("todo" to "Por hacer", "doing" to "En curso", "done" to "Hecho")

@Composable
fun TasksScreen(graph: AppGraph) {
    val vm: TasksViewModel = viewModel { TasksViewModel(graph) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val wide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    var tab by rememberSaveable { mutableStateOf("todo") }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyFamily by rememberSaveable { mutableStateOf(false) }
    val assignedToMe by vm.assignedToMe.collectAsState()
    var editing by remember { mutableStateOf<Task?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val family = rememberFamily(graph)

    val all = state.data?.tasks.orEmpty().filter { (project == null || it.projectId == project) && (!onlyFamily || it.shared) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Nueva tarea") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).statusBarsPadding()) {
            Text("Tareas", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
            val projects = state.data?.projects.orEmpty()
            val familyFilters = family.hasOthers || state.data?.tasks.orEmpty().any { it.shared }
            if (projects.isNotEmpty() || familyFilters) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = project == null && !onlyFamily && !assignedToMe, onClick = { project = null; onlyFamily = false; vm.setAssignedToMe(false) }, label = { Text("Todas") }) }
                    if (familyFilters) {
                        item { FilterChip(selected = assignedToMe, onClick = { vm.setAssignedToMe(!assignedToMe) }, label = { Text("Asignadas a mí") }) }
                        item { FilterChip(selected = onlyFamily, onClick = { onlyFamily = !onlyFamily }, label = { Text("Familiares") }) }
                    }
                    items(projects, key = { it.id }) { p -> FilterChip(selected = project == p.id, onClick = { project = if (project == p.id) null else p.id }, label = { Text(p.name) }) }
                }
            }
            state.error?.let { ErrorBanner(it, Modifier.padding(16.dp), onRetry = { vm.refresh() }) }
            when {
                state.loading && state.data == null -> SkeletonList(6, Modifier.padding(16.dp))
                wide -> Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    COLUMNS.forEach { (status, label) ->
                        val items = all.filter { it.status == status }
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp)) {
                            Column {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    Text("${items.size}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TaskColumn(items, vm, family, onEdit = { editing = it }, board = true)
                            }
                        }
                    }
                }
                else -> {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        COLUMNS.forEachIndexed { i, (status, label) ->
                            SegmentedButton(
                                selected = tab == status,
                                onClick = { tab = status },
                                shape = SegmentedButtonDefaults.itemShape(i, COLUMNS.size),
                                label = { Text("$label ${all.count { it.status == status }}") },
                            )
                        }
                    }
                    TaskColumn(all.filter { it.status == tab }, vm, family, onEdit = { editing = it }, board = false)
                }
            }
        }
    }

    if (creating || editing != null) {
        TaskEditor(
            task = editing,
            projects = state.data?.projects.orEmpty(),
            family = family,
            onDismiss = { creating = false; editing = null },
            onSave = { edit ->
                vm.save(editing, edit)
                creating = false; editing = null
            },
            onDelete = editing?.let { t -> { vm.delete(t); editing = null } },
        )
    }
}

@Composable
private fun TaskColumn(tasks: List<Task>, vm: TasksViewModel, family: FamilyInfo?, onEdit: (Task) -> Unit, board: Boolean) {
    if (tasks.isEmpty()) {
        EmptyState(Icons.Rounded.TaskAlt, "Nada por aquí", if (board) "Arrastra el día a día a Kairo: \"agrega a mis tareas...\"" else "Dile a Kairo \"tengo que...\" y aparecerá aquí.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(tasks, key = { it.id }) { t -> TaskRow(t, vm, family, onEdit) }
    }
}

@Composable
private fun TaskRow(t: Task, vm: TasksViewModel, family: FamilyInfo?, onEdit: (Task) -> Unit) {
    val today = LocalDate.now()
    val due = t.dueDate?.toLocalDateOrNull()
    val overdue = due != null && due.isBefore(today) && t.status != "done"
    Surface(onClick = { onEdit(t) }, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setStatus(t, if (t.status == "done") "todo" else "done") }) {
                Icon(
                    if (t.status == "done") Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (t.status == "done") "Marcar pendiente" else "Completar",
                    tint = if (t.status == "done") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.status == "done") TextDecoration.LineThrough else null,
                    color = if (t.status == "done") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                val badge = sharedLabel(t.shared, t.ownerId, family)
                if (due != null || t.projectName != null || badge != null || t.priority == "urgent" || t.priority == "high" || t.priority == "low") {
                    // FlowRow: en las columnas angostas del tablero los datos bajan de línea en vez de apretarse
                    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        PriorityTag(t.priority)
                        badge?.let { SharedBadge(it) }
                        due?.let { Text(it.dayLabel(today).replaceFirstChar { c -> c.uppercase() }, style = MonoStyle, maxLines = 1, softWrap = false, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                        t.projectName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            // Responsable: iniciales (el nombre completo lo lee TalkBack)
            family?.member(t.assigneeId)?.let { m ->
                MemberAvatar(m.displayName, Modifier.padding(start = 6.dp), size = 28.dp, description = "Responsable: ${m.displayName}")
            }
            if (t.status != "done") {
                IconButton(onClick = { vm.setStatus(t, if (t.status == "todo") "doing" else "done") }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, if (t.status == "todo") "Pasar a en curso" else "Marcar hecha", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskEditor(
    task: Task?,
    projects: List<Project>,
    family: FamilyInfo?,
    onDismiss: () -> Unit,
    onSave: (TaskEdit) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by rememberSaveable { mutableStateOf(task?.title ?: "") }
    var notes by rememberSaveable { mutableStateOf(task?.notes ?: "") }
    var priority by rememberSaveable { mutableStateOf(task?.priority ?: "medium") }
    var due by remember { mutableStateOf(task?.dueDate?.toLocalDateOrNull()) }
    var project by rememberSaveable { mutableStateOf(task?.projectName ?: "") }
    var shared by rememberSaveable { mutableStateOf(task?.shared ?: false) }
    var assignee by rememberSaveable { mutableStateOf(task?.assigneeId) }
    var pickDate by remember { mutableStateOf(false) }
    // Asignarla a otro miembro la vuelve familiar; dejar de compartir es solo de quien la creó
    val assignedToOther = assignee != null && assignee != family?.me
    val canUnshare = family.owns(task?.ownerId)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (task == null) "Nueva tarea" else "Editar tarea", style = MaterialTheme.typography.titleLarge)
            Labeled("Qué hay que hacer") { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.small) }
            Labeled("Notas") { OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), minLines = 2, shape = MaterialTheme.shapes.small) }
            Labeled("Prioridad") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf("low" to "Baja", "medium" to "Media", "high" to "Alta", "urgent" to "Urgente")
                    options.forEachIndexed { i, (k, l) ->
                        SegmentedButton(selected = priority == k, onClick = { priority = k }, shape = SegmentedButtonDefaults.itemShape(i, options.size), label = { Text(l) })
                    }
                }
            }
            Labeled("Fecha límite") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickDate = true }) {
                        Icon(Icons.Rounded.CalendarToday, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(due?.dayLabel()?.replaceFirstChar { it.uppercase() } ?: "Sin fecha")
                    }
                    if (due != null) TextButton(onClick = { due = null }) { Text("Quitar") }
                }
            }
            Labeled("Proyecto") {
                OutlinedTextField(project, { project = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Ninguno") }, shape = MaterialTheme.shapes.small)
                if (projects.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        projects.forEach { p -> FilterChip(selected = project.equals(p.name, true), onClick = { project = p.name }, label = { Text(p.name) }) }
                    }
                }
            }
            if (family != null && (family.hasOthers || task?.shared == true)) {
                Labeled("Responsable") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = assignee == null, onClick = { assignee = null }, label = { Text("Nadie") })
                        family.members.forEach { m ->
                            FilterChip(
                                selected = assignee == m.id,
                                onClick = { assignee = m.id; if (m.id != family.me) shared = true },
                                label = { Text(if (m.id == family.me) "Yo" else FamilyStore.firstNameOf(m.displayName)) },
                                leadingIcon = { MemberAvatar(m.displayName, size = 20.dp) },
                            )
                        }
                    }
                }
                when {
                    assignedToOther -> SharedInfoRow("Al asignarla a ${family.firstNameOf(assignee) ?: "otra persona"} se comparte con la familia y le llega un aviso.")
                    !canUnshare && task?.shared == true -> SharedInfoRow("La compartió ${family.firstNameOf(task.ownerId) ?: "otro miembro"}; solo esa persona puede dejar de compartirla.")
                    else -> ShareRow(shared, { shared = it }, title = "Familiar", body = "La ven y editan todos en ${family.household.name}.")
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                onDelete?.let { TextButton(onClick = it) { Icon(Icons.Rounded.DeleteOutline, null); Spacer(Modifier.width(6.dp)); Text("Eliminar") } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancelar") }
                Button(
                    onClick = { if (title.isNotBlank()) onSave(TaskEdit(title.trim(), notes, priority, due, project.trim().ifBlank { null }, shared || assignedToOther, assignee)) },
                    enabled = title.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Text("Guardar") }
            }
        }
    }
    if (pickDate) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = (due ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dp.selectedDateMillis?.let { due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text("Listo") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancelar") } },
        ) { DatePicker(dp) }
    }
}

@Composable
fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
