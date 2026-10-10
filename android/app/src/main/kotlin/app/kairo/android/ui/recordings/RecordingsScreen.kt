package app.kairo.android.ui.recordings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kairo.android.AppGraph
import app.kairo.android.ui.Destination
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.KairoOrb
import app.kairo.android.ui.components.OrbState
import app.kairo.android.ui.components.SectionHeader
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.model.Recording
import app.kairo.core.util.relativeLabel
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RecordingsViewModel(graph: AppGraph) : ResourceViewModel<List<Recording>>(graph, setOf("recording")) {
    override suspend fun load() = graph.api.recordings()
    init { refresh() }
    fun delete(r: Recording) = mutate("Grabación eliminada") { graph.api.deleteRecording(r.id) }
    fun createTasks(r: Recording) = mutate { val n = graph.api.recordingTasks(r.id); say("Se crearon $n tareas") }
    suspend fun detail(id: String): Recording? = runCatching { graph.api.recording(id) }.getOrNull()
    fun poll() = viewModelScope.launch { while (state.value.data?.any { it.status == "processing" || it.status == "recording" } == true) { delay(4000); refresh(silent = true) } }
}

@Composable
fun RecordingsScreen(graph: AppGraph, navigate: (Destination) -> Unit) {
    val vm: RecordingsViewModel = viewModel { RecordingsViewModel(graph) }
    val state by vm.state.collectAsState()
    val rec by graph.recorder.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var open by remember { mutableStateOf<Recording?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(state.data, rec.lastFinishedId) { vm.poll() }
    LaunchedEffect(rec.lastFinishedId) { if (rec.lastFinishedId != null) vm.refresh(silent = true) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) graph.recorder.start() }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(rec.active) { while (rec.active) { now = System.currentTimeMillis(); delay(500) } }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner).statusBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Grabaciones", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        KairoOrb(if (rec.active) OrbState.Listening else OrbState.Idle, rec.level, size = 120.dp)
                        if (rec.active) {
                            val secs = ((now - rec.startedAt) / 1000).coerceAtLeast(0)
                            Text("%02d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = MonoStyle.fontFamily))
                            Text(if (rec.finishing) "Terminando y subiendo..." else "Transcribiendo por partes · ${rec.uploaded} subidas", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { graph.recorder.stop() }, enabled = !rec.finishing, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                                Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Detener")
                            }
                        } else {
                            Text("Graba una reunión o una idea larga", style = MaterialTheme.typography.titleMedium)
                            Text("Kairo transcribe, resume y propone tareas. El audio se borra al subirse.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(
                                onClick = { if (graph.voice.hasMicPermission()) graph.recorder.start() else mic.launch(Manifest.permission.RECORD_AUDIO) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                            ) { Icon(Icons.Rounded.FiberManualRecord, null); Spacer(Modifier.width(8.dp)); Text("Grabar") }
                        }
                        rec.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            state.error?.let { item { ErrorBanner(it, onRetry = { vm.refresh() }) } }
            item { SectionHeader("Anteriores") }
            if (state.loading && state.data == null) item { SkeletonList(3) }
            else if (state.data.isNullOrEmpty()) item { EmptyState(Icons.Rounded.GraphicEq, "Sin grabaciones", "Tus reuniones resumidas aparecerán aquí.") }
            items(state.data.orEmpty(), key = { it.id }) { r ->
                Surface(onClick = { open = r }, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (r.status) {
                                    "processing" -> "Resumiendo..."
                                    "recording" -> "Grabando · ${r.segments} partes"
                                    "failed" -> r.error ?: "No se pudo procesar"
                                    else -> "${r.durationS / 60} min · ${r.createdAt.toInstantOrNull()?.relativeLabel() ?: ""}"
                                },
                                style = MonoStyle, color = if (r.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (r.status == "processing") LoadingIndicator(Modifier.size(28.dp))
                        IconButton(onClick = { vm.delete(r) }) { Icon(Icons.Rounded.DeleteOutline, "Eliminar") }
                    }
                }
            }
        }
    }

    open?.let { r ->
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var full by remember(r.id) { mutableStateOf<Recording?>(null) }
        LaunchedEffect(r.id) { full = vm.detail(r.id) }
        val d = full ?: r
        var showTranscript by remember(r.id) { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { open = null }, sheetState = sheet) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(d.title, style = MaterialTheme.typography.titleLarge)
                if (d.summary.isNotBlank()) Text(d.summary, style = MaterialTheme.typography.bodyLarge)
                if (d.keyPoints.isNotEmpty()) {
                    Text("Puntos clave", style = MaterialTheme.typography.titleSmall)
                    d.keyPoints.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                }
                if (d.actionItems.isNotEmpty()) {
                    Text("Acciones", style = MaterialTheme.typography.titleSmall)
                    d.actionItems.forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
                    OutlinedButton(onClick = { vm.createTasks(d) }) { Icon(Icons.Rounded.TaskAlt, null); Spacer(Modifier.width(8.dp)); Text("Crear tareas") }
                }
                if (d.noteId != null) TextButton(onClick = { open = null; navigate(Destination.Notes) }) { Text("Ver nota") }
                if (d.transcript.isNotBlank()) {
                    TextButton(onClick = { showTranscript = !showTranscript }) { Text(if (showTranscript) "Ocultar transcripción" else "Ver transcripción") }
                    if (showTranscript) Text(d.transcript, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
