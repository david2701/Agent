package app.kairo.android.ui.memory

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.kairo.android.ui.components.SkeletonList
import app.kairo.core.model.Memory

// The category keys are the values stored by the server (they are part of the API and are not translated);
// only the labels follow the app language.
private val CATEGORIES = listOf(
    "perfil" to R.string.mem_cat_profile, "preferencias" to R.string.mem_cat_preferences, "personas" to R.string.mem_cat_people, "salud" to R.string.mem_cat_health,
    "trabajo" to R.string.mem_cat_work, "hogar" to R.string.mem_cat_home, "general" to R.string.mem_cat_general,
)

class MemoryViewModel(graph: AppGraph) : ResourceViewModel<List<Memory>>(graph, setOf("memory")) {
    override suspend fun load() = graph.api.memories()
    init { refresh() }
    fun save(m: Memory?, content: String, category: String) = mutate(if (m == null) str(R.string.mem_saved) else str(R.string.mem_updated)) {
        if (m == null) graph.api.createMemory(content, category) else graph.api.updateMemory(m.id, content, category)
    }
    fun delete(m: Memory) = mutate(str(R.string.mem_forgot)) { graph.api.deleteMemory(m.id) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(graph: AppGraph) {
    val vm: MemoryViewModel = viewModel { MemoryViewModel(graph) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val grouped = state.data.orEmpty().groupBy { it.category }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(stringResource(R.string.common_add)) },
                containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner).statusBarsPadding(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Column(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.nav_memory), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.mem_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.error?.let { item { ErrorBanner(it, onRetry = { vm.refresh() }) } }
            if (state.loading && state.data == null) item { SkeletonList(5) }
            else if (grouped.isEmpty()) item { EmptyState(Icons.Rounded.Psychology, stringResource(R.string.mem_empty_title), stringResource(R.string.mem_empty_body)) }
            CATEGORIES.forEach { (key, label) ->
                val list = grouped[key].orEmpty()
                if (list.isNotEmpty()) {
                    item(key = "h$key") { SectionHeader(stringResource(label)) }
                    items(list, key = { it.id }) { m ->
                        Surface(onClick = { editing = m }, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(m.content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.delete(m) }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.mem_forget_cd), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        val m = editing
        var content by rememberSaveable(m?.id) { mutableStateOf(m?.content ?: "") }
        var category by rememberSaveable(m?.id) { mutableStateOf(m?.category ?: "general") }
        AlertDialog(
            onDismissRequest = { creating = false; editing = null },
            title = { Text(if (m == null) stringResource(R.string.mem_new) else stringResource(R.string.mem_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text(stringResource(R.string.mem_fact)) })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CATEGORIES.forEach { (k, l) -> FilterChip(selected = category == k, onClick = { category = k }, label = { Text(stringResource(l)) }) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { if (content.isNotBlank()) { vm.save(m, content.trim(), category); creating = false; editing = null } }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { creating = false; editing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
