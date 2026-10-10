package app.kairo.android.ui.notes

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kairo.android.AppGraph
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.MarkdownText
import app.kairo.android.ui.components.SharedBadge
import app.kairo.android.ui.components.ShareRow
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.components.firstNameOf
import app.kairo.android.ui.components.hasOthers
import app.kairo.android.ui.components.owns
import app.kairo.android.ui.components.rememberFamily
import app.kairo.android.ui.components.sharedLabel
import app.kairo.core.model.FamilyInfo
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.model.Note
import app.kairo.core.net.KairoApi.Companion.obj
import app.kairo.android.ui.common.relativeLabel
import app.kairo.core.util.toInstantOrNull
import coil3.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

class NotesViewModel(graph: AppGraph) : ResourceViewModel<List<Note>>(graph, setOf("note")) {
    val query = MutableStateFlow("")
    val searchResults = MutableStateFlow<List<Note>?>(null)
    private var searchJob: Job? = null

    override suspend fun load(): List<Note> = graph.api.notes()

    init { refresh() }

    fun search(q: String) {
        query.value = q
        searchJob?.cancel()
        if (q.trim().length < 2) { searchResults.value = null; return }
        searchJob = viewModelScope.launch {
            delay(350)
            searchResults.value = runCatching { graph.api.searchNotes(q.trim()) }.getOrNull()
        }
    }

    fun save(id: String?, title: String, content: String, shared: Boolean, onSaved: (String) -> Unit) = viewModelScope.launch {
        runCatching {
            if (id == null) graph.api.createNote(title, content, shared = shared) else graph.api.updateNote(id, obj { put("title", title); put("content", content) })
        }.onSuccess { onSaved(it.id); refresh(silent = true) }.onFailure { say(R.string.notes_save_failed) }
    }

    /** Only the note's creator can stop sharing it. */
    fun share(n: Note, shared: Boolean) {
        updateData { all -> all.map { if (it.id == n.id) it.copy(shared = shared) else it } }
        mutate(if (shared) str(R.string.notes_shared_toast) else str(R.string.notes_unshared_toast)) { graph.api.updateNote(n.id, obj { put("shared", shared) }) }
    }

    fun togglePin(n: Note) = mutate { graph.api.updateNote(n.id, obj { put("pinned", !n.pinned) }) }
    fun delete(n: Note) = mutate(str(R.string.notes_deleted)) { graph.api.deleteNote(n.id) }
}

@Composable
fun NotesScreen(graph: AppGraph) {
    val vm: NotesViewModel = viewModel { NotesViewModel(graph) }
    val state by vm.state.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    val session by graph.session.session.collectAsState(initial = null)
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo()))
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var owner by rememberSaveable { mutableStateOf(Owner.All) }
    val family = rememberFamily(graph)
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }

    val notes = state.data.orEmpty()
    val tags = notes.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12)
    val showOwner = family.hasOthers || notes.any { it.shared }
    val shown = (results ?: notes).filter { tag == null || tag in it.tags }.filter { n ->
        when (owner) {
            Owner.All -> true
            Owner.Mine -> family.owns(n.ownerId)
            Owner.Family -> n.shared
        }
    }
    val selectedId = navigator.currentDestination?.contentKey

    fun open(id: String) = scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        ListDetailPaneScaffold(
            modifier = Modifier.padding(inner),
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = {
                AnimatedPane {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { Text(stringResource(R.string.nav_notes), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)) }
                            item {
                                TextField(
                                    value = query, onValueChange = vm::search,
                                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                    placeholder = { Text(stringResource(R.string.notes_search)) },
                                    singleLine = true, shape = CircleShape, modifier = Modifier.fillMaxWidth(),
                                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                )
                            }
                            if (showOwner) item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    Owner.entries.forEach { o -> FilterChip(selected = owner == o, onClick = { owner = o }, label = { Text(stringResource(o.label)) }) }
                                }
                            }
                            if (tags.isNotEmpty()) item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                                    items(tags) { t -> FilterChip(selected = tag == t, onClick = { tag = if (tag == t) null else t }, label = { Text("#$t") }) }
                                }
                            }
                            state.error?.let { item { ErrorBanner(it, onRetry = { vm.refresh() }) } }
                            if (state.loading && state.data == null) item { SkeletonList(6) }
                            else if (shown.isEmpty()) item {
                                EmptyState(
                                    Icons.Rounded.StickyNote2,
                                    when { results != null -> stringResource(R.string.notes_empty_no_results); owner == Owner.Family -> stringResource(R.string.notes_empty_nothing_shared); else -> stringResource(R.string.notes_empty_none) },
                                    when {
                                        results != null -> stringResource(R.string.notes_empty_no_results_body)
                                        owner == Owner.Family -> stringResource(R.string.notes_empty_shared_body)
                                        else -> stringResource(R.string.notes_empty_none_body)
                                    },
                                )
                            }
                            items(shown, key = { it.id }) { n ->
                                Surface(onClick = { open(n.id) }, shape = MaterialTheme.shapes.medium, color = if (selectedId == n.id) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (n.pinned) Icon(Icons.Rounded.PushPin, stringResource(R.string.notes_pinned), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                            if (n.pinned) Spacer(Modifier.width(6.dp))
                                            Text(n.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Text(n.updatedAt.toInstantOrNull()?.relativeLabel() ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                                        }
                                        val preview = app.kairo.android.ui.components.plainPreview(n.content, n.title)
                                        if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val badge = sharedLabel(n.shared, n.ownerId, family)
                                        if (n.tags.isNotEmpty() || badge != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            badge?.let { SharedBadge(it) }
                                            if (n.tags.isNotEmpty()) Text(n.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        ExtendedFloatingActionButton(
                            onClick = { open(NEW) },
                            icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.notes_new)) },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            },
            detailPane = {
                AnimatedPane {
                    val id = selectedId
                    val note = notes.firstOrNull { it.id == id } ?: results?.firstOrNull { it.id == id }
                    if (id == null) {
                        EmptyState(Icons.Rounded.StickyNote2, stringResource(R.string.notes_pick), stringResource(R.string.notes_pick_body), Modifier.fillMaxSize())
                    } else {
                        NoteDetail(
                            note = note, isNew = id == NEW, baseUrl = session?.serverUrl ?: "", family = family,
                            showBack = navigator.canNavigateBack(),
                            onBack = { scope.launch { navigator.navigateBack() } },
                            onSave = { title, content, shared -> vm.save(note?.id, title, content, shared) { saved -> if (id == NEW) open(saved) } },
                            onShare = { shared -> note?.let { vm.share(it, shared) } },
                            onPin = { note?.let(vm::togglePin) },
                            onDelete = { note?.let { vm.delete(it); scope.launch { navigator.navigateBack() } } },
                        )
                    }
                }
            },
        )
    }
}

private const val NEW = "__new__"

private enum class Owner(@StringRes val label: Int) { All(R.string.tasks_filter_all), Mine(R.string.notes_filter_mine), Family(R.string.tasks_filter_family) }

@Composable
private fun NoteDetail(
    note: Note?,
    isNew: Boolean,
    baseUrl: String,
    family: FamilyInfo?,
    showBack: Boolean,
    onBack: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
    onShare: (Boolean) -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    var editing by rememberSaveable(note?.id, isNew) { mutableStateOf(isNew) }
    var title by rememberSaveable(note?.id) { mutableStateOf(note?.title ?: "") }
    var content by rememberSaveable(note?.id) { mutableStateOf(note?.content ?: "") }
    var shareNew by rememberSaveable(isNew) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) }
            Spacer(Modifier.weight(1f))
            if (note != null && !editing) {
                IconButton(onClick = onPin) { Icon(if (note.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (note.pinned) stringResource(R.string.notes_unpin) else stringResource(R.string.notes_pin)) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.common_delete)) }
            }
            IconButton(onClick = {
                if (editing) { if (content.isNotBlank() || title.isNotBlank()) onSave(title, content, shareNew); editing = false } else editing = true
            }) { Icon(if (editing) Icons.Rounded.Check else Icons.Rounded.Edit, if (editing) stringResource(R.string.common_save) else stringResource(R.string.common_edit)) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (editing) {
                val colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent)
                TextField(title, { title = it }, placeholder = { Text(stringResource(R.string.notes_title_placeholder)) }, textStyle = MaterialTheme.typography.headlineSmall, colors = colors, modifier = Modifier.fillMaxWidth())
                TextField(content, { content = it }, placeholder = { Text(stringResource(R.string.notes_content_placeholder)) }, textStyle = MaterialTheme.typography.bodyLarge, colors = colors, modifier = Modifier.fillMaxWidth(), minLines = 8)
                if (isNew && family.hasOthers) ShareRow(shareNew, { shareNew = it }, body = stringResource(R.string.notes_share_new_body, family?.household?.name ?: stringResource(R.string.notes_your_family)))
            } else if (note != null) {
                Text(note.title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    listOfNotNull(note.updatedAt.toInstantOrNull()?.relativeLabel()?.let { stringResource(R.string.notes_edited, it) }, note.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" }).joinToString("  \u00B7  "),
                    style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    !family.owns(note.ownerId) -> Text(
                        stringResource(R.string.notes_shared_by, family.firstNameOf(note.ownerId) ?: stringResource(R.string.tasks_another_member)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    family.hasOthers || note.shared -> Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        ShareRow(
                            note.shared, onShare, Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            body = if (note.shared) stringResource(R.string.shared_family_body, family?.household?.name ?: stringResource(R.string.notes_your_family)) else stringResource(R.string.notes_private),
                        )
                    }
                }
                note.attachments.forEach { f ->
                    AsyncImage(model = baseUrl + f.url, contentDescription = stringResource(R.string.notes_attachment), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)))
                }
                MarkdownText(note.content)
            }
        }
    }
}
