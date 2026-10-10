package app.kairo.wear

import app.kairo.wear.R
import androidx.compose.ui.res.stringResource
import app.kairo.wear.friendly
import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.kairo.core.util.timeLabel
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val Amber = Color(0xFFF0A23B)
private val Bone = Color(0xFFECE8E1)
private val Ink = Color(0xFF0E1013)

private val KairoWearColors = ColorScheme(
    primary = Amber, onPrimary = Color(0xFF1A1206),
    primaryContainer = Amber, onPrimaryContainer = Color(0xFF1A1206),
    background = Ink, onBackground = Color(0xFFECEDEE),
    surfaceContainer = Color(0xFF1C2025), onSurface = Color(0xFFECEDEE), onSurfaceVariant = Color(0xFF9BA1A9),
)

class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = KairoWearColors) {
                AppScaffold {
                    val session by wear.session.session.collectAsState(initial = null)
                    var checked by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { wear.session.current(); checked = true }
                    if (session == null) { if (checked) PairScreen(this@WearActivity) } else VoiceHome()
                }
            }
        }
    }
}

@Composable
private fun PairScreen(activity: Activity) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberScalingLazyListState()
    fun submit() {
        busy = true; error = null
        scope.launch {
            runCatching { activity.wear.api.pair(BuildConfig.DEFAULT_SERVER, code, activity.getString(R.string.wear_device_name, Build.MODEL)) }
                .onFailure { error = if (it is app.kairo.core.net.ApiException) it.friendly(activity) else activity.getString(R.string.wear_pair_failed); code = "" }
            busy = false
        }
    }
    ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, horizontalAlignment = Alignment.CenterHorizontally) {
            item { ListHeader { Text(stringResource(R.string.wear_pair_title), textAlign = TextAlign.Center) } }
            item { Text(stringResource(R.string.wear_pair_hint), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text(code.padEnd(6, '\u00B7').chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = 6.dp)) }
            error?.let { item { Text(it, color = Color(0xFFF0705F), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) } }
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("<", "0", "OK")).forEach { row ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k ->
                            Button(
                                onClick = {
                                    when (k) {
                                        "<" -> code = code.dropLast(1)
                                        "OK" -> if (code.length == 6 && !busy) submit()
                                        else -> if (code.length < 6) code += k
                                    }
                                },
                                modifier = Modifier.size(width = 48.dp, height = 40.dp),
                                colors = if (k == "OK") ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF1A1206)) else ButtonDefaults.filledTonalButtonColors(),
                            ) {
                                if (k == "<") Icon(Icons.AutoMirrored.Rounded.Backspace, stringResource(R.string.wear_delete_cd)) else Text(k, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceHome() {
    val vm: WearVoice = viewModel()
    val resources = androidx.compose.ui.platform.LocalResources.current
    val s by vm.state.collectAsState()
    val list = rememberScalingLazyListState()
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggle() }
    LaunchedEffect(Unit) { vm.loadToday() }
    val busy = s.phase != WearPhase.Idle
    ScreenScaffold(
        scrollState = list,
        edgeButton = {
            EdgeButton(
                onClick = { if (vm.hasMic()) vm.toggle() else mic.launch(Manifest.permission.RECORD_AUDIO) },
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF1A1206)),
            ) { Icon(if (busy) Icons.Rounded.Stop else Icons.Rounded.Mic, if (busy) stringResource(R.string.wear_stop) else stringResource(R.string.wear_talk)) }
        },
    ) { padding ->
        ScalingLazyColumn(state = list, contentPadding = padding, horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Box(Modifier.size(72.dp).clickable { if (vm.hasMic()) vm.toggle() else mic.launch(Manifest.permission.RECORD_AUDIO) }, contentAlignment = Alignment.Center) {
                    WearOrb(s.phase, s.level)
                }
            }
            item {
                Text(
                    when (s.phase) {
                        WearPhase.Listening -> stringResource(R.string.wear_listening)
                        WearPhase.Thinking -> stringResource(R.string.wear_thinking)
                        WearPhase.Speaking -> stringResource(R.string.wear_kairo)
                        WearPhase.Idle -> s.error ?: if (s.reply != null) stringResource(R.string.wear_done) else stringResource(R.string.wear_tap_to_talk)
                    },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
            }
            s.transcript?.let { item { Text("\"$it\"", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) } }
            s.actions.forEach { a -> item { Text(a, style = MaterialTheme.typography.bodySmall, color = Amber, textAlign = TextAlign.Center) } }
            s.reply?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp)) } }
            s.today?.let { t ->
                item { ListHeader { Text(stringResource(R.string.wear_today)) } }
                val lines = buildList {
                    t.firedReminders.forEach { add(resources.getString(R.string.wear_fired, it.text)) }
                    t.events.forEach { e -> add((if (e.allDay) "" else (e.startAt.toInstantOrNull()?.timeLabel() ?: "") + " ") + e.title) }
                    t.reminders.forEach { r -> add((r.nextFireAt?.toInstantOrNull()?.timeLabel() ?: "") + " " + r.text) }
                    t.tasksOverdue.forEach { add(resources.getString(R.string.wear_overdue, it.title)) }
                    t.tasksDueToday.forEach { add(it.title) }
                }
                if (lines.isEmpty()) item { Text(stringResource(R.string.wear_clear_day), style = MaterialTheme.typography.bodySmall) }
                lines.take(8).forEach { l -> item { Text(l.trim(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) } }
            }
        }
    }
}

@Composable
private fun WearOrb(phase: WearPhase, level: Float) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    val lvl by animateFloatAsState(level, label = "lvl")
    Canvas(Modifier.size(64.dp)) {
        val r = size.minDimension * 0.34f
        val stroke = size.minDimension * 0.08f
        val c = Offset(size.width / 2, size.height / 2)
        rotate(if (phase == WearPhase.Thinking) spin else 0f, c) {
            drawArc(Bone, -10f, 300f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            val a = Math.toRadians(-40.0)
            val dot = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            val grow = if (phase == WearPhase.Listening || phase == WearPhase.Speaking) 1f + lvl * 0.6f else 1f
            drawCircle(Amber, stroke * 0.85f * grow, dot)
        }
    }
}
