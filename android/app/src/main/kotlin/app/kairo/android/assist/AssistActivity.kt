package app.kairo.android.assist

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kairo.android.MainActivity
import app.kairo.android.graph
import app.kairo.android.ui.components.ActionCard
import app.kairo.android.ui.components.KairoOrb
import app.kairo.android.ui.components.MarkdownText
import app.kairo.android.ui.components.OrbState
import app.kairo.android.ui.theme.KairoTheme
import app.kairo.android.voice.VoicePhase
import kotlinx.coroutines.delay

/**
 * Kairo como asistente del sistema: se abre sobre cualquier app (pulsación larga del botón de
 * inicio/encendido si elegiste Kairo como asistente digital, tile de ajustes rápidos o widget)
 * y empieza a escuchar de inmediato.
 */
class AssistActivity : ComponentActivity() {
    private var trigger by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = graph
        setContent {
            KairoTheme {
                val voice by graph.voice.state.collectAsState()
                var started by remember { mutableStateOf(false) }
                val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) graph.voice.talk() else finish()
                }
                LaunchedEffect(trigger) {
                    graph.voice.clearResult()
                    if (graph.session.current() == null) {
                        startActivity(Intent(this@AssistActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish(); return@LaunchedEffect
                    }
                    if (graph.voice.hasMicPermission()) graph.voice.talk() else mic.launch(Manifest.permission.RECORD_AUDIO)
                    started = true
                }
                // Se cierra solo unos segundos después de responder
                LaunchedEffect(voice.phase, voice.reply, voice.error) {
                    val idle = voice.phase == VoicePhase.Off || voice.phase == VoicePhase.Armed
                    if (started && idle && (voice.reply != null || voice.error != null)) {
                        delay(if (voice.actions.isNotEmpty()) 6000 else 4000)
                        finish()
                    }
                }

                Box(
                    Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { graph.voice.cancel(); finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    AnimatedVisibility(visible = true, enter = slideInVertically { it / 3 } + fadeIn()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
                            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(10.dp).navigationBarsPadding()
                                .clickable(remember { MutableInteractionSource() }, indication = null) { },
                        ) {
                            Column(
                                Modifier.padding(20.dp).animateContentSize().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                val orb = when (voice.phase) {
                                    VoicePhase.Listening -> OrbState.Listening
                                    VoicePhase.Thinking -> OrbState.Thinking
                                    VoicePhase.Speaking -> OrbState.Speaking
                                    else -> if (voice.error != null) OrbState.Error else OrbState.Idle
                                }
                                KairoOrb(orb, voice.level, size = 112.dp)
                                Text(
                                    when (voice.phase) {
                                        VoicePhase.Listening -> "Te escucho"
                                        VoicePhase.Thinking -> voice.working ?: "Pensando"
                                        VoicePhase.Speaking -> "Kairo"
                                        else -> voice.error ?: if (voice.reply != null) "Listo" else "Toca para hablar"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                voice.transcript?.let { Text("\"$it\"", style = MaterialTheme.typography.bodyLarge) }
                                voice.actions.forEach { ActionCard(it) }
                                (voice.reply ?: voice.partialReply.takeIf { it.isNotBlank() })?.let { MarkdownText(it) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        startActivity(Intent(this@AssistActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_DESTINATION, "chat"))
                                        finish()
                                    }) { Text("Abrir Kairo") }
                                    Spacer(Modifier.weight(1f))
                                    val busy = voice.phase == VoicePhase.Listening || voice.phase == VoicePhase.Thinking || voice.phase == VoicePhase.Speaking
                                    FilledIconButton(
                                        onClick = { if (busy) graph.voice.cancel() else graph.voice.talk() },
                                        modifier = Modifier.size(52.dp), shape = CircleShape,
                                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                                    ) { Icon(if (busy) Icons.Rounded.Stop else Icons.Rounded.Mic, if (busy) "Detener" else "Hablar") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        trigger++
    }

    companion object {
        const val ACTION_TALK = "app.kairo.android.TALK"
    }
}
