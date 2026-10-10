package app.kairo.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.kairo.android.service.ConnectionService
import app.kairo.android.service.ListeningService
import app.kairo.android.ui.KairoAppUi
import app.kairo.android.ui.theme.KairoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var deepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        handle(intent)
        setContent {
            KairoTheme {
                KairoAppUi(graph, deepLink) { deepLink = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // Si la escucha estaba activa (p. ej. tras reiniciar), se reactiva ahora que la app está visible
        lifecycleScope.launch {
            val local = graph.prefs.current()
            val signedIn = graph.session.current() != null
            if (local.wakeWordEnabled && signedIn && graph.voice.hasMicPermission()) {
                ListeningService.start(this@MainActivity)
            }
            // La conexión directa solo corre si la persona la activó en Ajustes
            if (local.directConnection && signedIn) ConnectionService.start(this@MainActivity)
        }
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_DESTINATION)?.let { deepLink = it }
        // Tocar un aviso de la familia lo marca como leído
        intent?.getStringExtra(EXTRA_NOTICE)?.let { id ->
            intent.removeExtra(EXTRA_NOTICE)
            graph.scope.launch { runCatching { graph.api.markNoticesRead(listOf(id)) } }
        }
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_NOTICE = "notice"
    }
}
