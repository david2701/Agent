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
    private var invite by mutableStateOf<InviteLink?>(null)

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
                KairoAppUi(graph, deepLink, invite) { deepLink = null; invite = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // If listening was on (e.g. after a reboot), turn it back on now that the app is visible
        lifecycleScope.launch {
            val local = graph.prefs.current()
            val signedIn = graph.session.current() != null
            if (local.wakeWordEnabled && signedIn && graph.voice.hasMicPermission()) {
                ListeningService.start(this@MainActivity)
            }
            // The direct connection only runs if the person turned it on in Settings
            if (local.directConnection && signedIn) ConnectionService.start(this@MainActivity)
        }
    }

    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_DESTINATION)?.let { deepLink = it }
        // Invite link: https://host/join?code=XXXX-XXXX (or the legacy /unirse?codigo=XXXX-XXXX)
        if (intent?.action == Intent.ACTION_VIEW) InviteLink.parse(intent.dataString)?.let { invite = it }
        // Tapping a family notice marks it as read
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
