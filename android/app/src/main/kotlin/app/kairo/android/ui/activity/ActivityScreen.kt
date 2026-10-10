package app.kairo.android.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import app.kairo.android.ui.components.entityIcon
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.model.Activity
import app.kairo.core.util.dayLabel
import app.kairo.core.util.timeLabel
import app.kairo.core.util.toInstantOrNull
import java.time.ZoneId

class ActivityViewModel(graph: AppGraph) : ResourceViewModel<List<Activity>>(
    graph, setOf("note", "task", "reminder", "list", "event", "memory", "place", "recording"),
) {
    override suspend fun load() = graph.api.activity(100)
    init { refresh() }
    fun undo(a: Activity) = mutate("Deshecho") { graph.api.undo(a.id) }
}

private fun sourceLabel(s: String) = when (s) {
    "voice" -> "por voz"; "chat" -> "en el chat"; "share" -> "al compartir"; "app" -> "en la app"; "dashboard" -> "en el dashboard"
    "system" -> "automático"; "recording" -> "grabación"; "undo" -> "deshacer"; "wear" -> "desde el reloj"; else -> s
}

@Composable
fun ActivityScreen(graph: AppGraph) {
    val vm: ActivityViewModel = viewModel { ActivityViewModel(graph) }
    val state by vm.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val zone = ZoneId.systemDefault()
    val byDay = state.data.orEmpty().groupBy { it.createdAt.toInstantOrNull()?.atZone(zone)?.toLocalDate() }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner).statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Column(Modifier.padding(start = 4.dp, bottom = 8.dp)) {
                    Text("Actividad", style = MaterialTheme.typography.headlineSmall)
                    Text("Todo lo que Kairo y tú cambiaron. Las creaciones y borrados se pueden deshacer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.error?.let { item { ErrorBanner(it, onRetry = { vm.refresh() }) } }
            if (state.loading && state.data == null) item { SkeletonList(8) }
            else if (state.data.isNullOrEmpty()) item { EmptyState(Icons.Rounded.History, "Sin actividad", "Aquí verás cada acción de Kairo.") }
            byDay.forEach { (day, list) ->
                item(key = "d$day") { SectionHeader(day?.dayLabel()?.replaceFirstChar { it.uppercase() } ?: "") }
                items(list, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(entityIcon(a.entityType), null, Modifier.size(20.dp), tint = if (a.undoneAt != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.summary, style = MaterialTheme.typography.bodyMedium, color = if (a.undoneAt != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text("${a.createdAt.toInstantOrNull()?.timeLabel() ?: ""}  ·  ${sourceLabel(a.source)}" + if (a.undoneAt != null) "  ·  deshecho" else "", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (a.undoable) TextButton(onClick = { vm.undo(a) }) { Text("Deshacer") }
                    }
                }
            }
        }
    }
}
