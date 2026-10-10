package app.kairo.android.share

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kairo.android.graph
import app.kairo.android.ui.common.friendly
import app.kairo.android.ui.components.ActionCard
import app.kairo.android.ui.components.MarkdownText
import app.kairo.android.ui.theme.KairoTheme
import app.kairo.core.model.AgentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** "Share > Kairo": saves texts, links and photos (reading the text in the image). */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()
        val stream = androidx.core.content.IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val url = text?.let { Regex("https?://\\S+").find(it)?.value }
        val note = listOfNotNull(subject, text?.replace(url ?: "\u0000", "")?.trim()?.takeIf { it.isNotEmpty() }).joinToString("\n").ifBlank { null }

        setContent {
            KairoTheme {
                var working by remember { mutableStateOf(getString(R.string.share_reading)) }
                var done by remember { mutableStateOf(false) }
                var reply by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                val actions = remember { mutableStateListOf<AgentEvent.ToolEnd>() }

                LaunchedEffect(Unit) {
                    if (graph.session.current() == null) { error = getString(R.string.share_sign_in); done = true; return@LaunchedEffect }
                    try {
                        val image = stream?.let { withContext(Dispatchers.IO) { compress(it) } }
                        graph.api.capture(
                            text = if (url != null && note == null) null else note ?: text,
                            url = url, image = image, imageMime = if (image != null) "image/jpeg" else null,
                        ).collect { ev ->
                            when (ev) {
                                is AgentEvent.ToolStart -> working = ev.label
                                is AgentEvent.ToolEnd -> actions += ev
                                is AgentEvent.Done -> reply = ev.text
                                is AgentEvent.Error -> error = ev.message
                                else -> Unit
                            }
                        }
                    } catch (e: Exception) {
                        error = e.friendly(this@ShareActivity)
                    }
                    done = true
                    delay(if (error == null) 3500 else 6000)
                    finish()
                }

                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { if (done) finish() }, contentAlignment = Alignment.BottomCenter) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(10.dp).navigationBarsPadding(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.share_label), style = MaterialTheme.typography.titleLarge)
                            if (!done) Row(verticalAlignment = Alignment.CenterVertically) {
                                LoadingIndicator(Modifier.size(36.dp)); Spacer(Modifier.size(10.dp))
                                Text(working, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            actions.forEach { ActionCard(it) }
                            reply?.let { MarkdownText(it, style = MaterialTheme.typography.bodyMedium) }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (done) TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.common_done)) }
                        }
                    }
                }
            }
        }
    }

    /** Shrinks the photo to 1600 px at most (JPEG 85 %) to upload it quickly. */
    private fun compress(uri: Uri): ByteArray? = runCatching {
        val src = ImageDecoder.createSource(contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val max = maxOf(info.size.width, info.size.height)
            if (max > 1600) {
                val scale = 1600f / max
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out); out.toByteArray() }
    }.getOrNull()}
