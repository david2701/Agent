package app.kairo.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.kairo.android.ui.KairoAppUi
import app.kairo.android.ui.components.KairoOrb
import app.kairo.android.ui.components.OrbState
import app.kairo.android.ui.home.TabletopHome
import app.kairo.android.ui.login.LoginScreen
import app.kairo.android.ui.theme.KairoTheme
import app.kairo.android.voice.VoicePhase
import app.kairo.android.voice.VoiceState
import app.kairo.core.model.AgentEvent
import app.kairo.core.model.AuthResponse
import app.kairo.core.model.EntityRef
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.HttpURLConnection
import java.net.URL

/** Aplicación de pruebas: sin WorkManager ni tiempo real. */
class TestKairoApp : KairoApplication() {
    override fun startBackground() = Unit
}

private const val PHONE = "w411dp-h891dp-xxhdpi"
private const val FOLD_OPEN = "w700dp-h840dp-xhdpi"
private const val FOLD_LAND = "w900dp-h700dp-xhdpi"

/**
 * Capturas de la UI con Roborazzi (renderizado en la JVM, sin emulador).
 * Usa el backend local real (KAIRO_TEST_SERVER, por defecto http://localhost:8088) para mostrar datos reales.
 * Ejecutar: ./gradlew :app:recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestKairoApp::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val server = System.getenv("KAIRO_TEST_SERVER") ?: "http://localhost:8088"
    private val out = System.getProperty("kairo.screenshots") ?: "build/screenshots"
    private val graph get() = ApplicationProvider.getApplicationContext<KairoApplication>().graph

    @Before
    fun login() {
        // Un solo login por JVM: el backend limita a 10 intentos por minuto
        val cached = System.getProperty("kairo.test.auth")
        val auth = cached?.let { Json { ignoreUnknownKeys = true }.decodeFromString(AuthResponse.serializer(), it) }
            ?: runCatching { loginToBackend() }.getOrNull()
        assumeTrue("Backend local no disponible en $server", auth != null)
        runBlocking { graph.session.saveForTest(server, auth!!.user, auth.deviceId, auth.accessToken) }
    }

    private fun loginToBackend(): AuthResponse {
        val c = URL("$server/api/auth/login").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val email = System.getenv("KAIRO_TEST_EMAIL") ?: "david@kairo.local"
        val pass = System.getenv("KAIRO_TEST_PASSWORD") ?: "kairo-local-test-1"
        c.outputStream.use { it.write("""{"email":"$email","password":"$pass","deviceName":"Capturas","platform":"android"}""".toByteArray()) }
        val body = c.inputStream.bufferedReader().readText()
        System.setProperty("kairo.test.auth", body)
        return Json { ignoreUnknownKeys = true }.decodeFromString(AuthResponse.serializer(), body)
    }

    /** Petición autenticada como el usuario de prueba (para preparar y limpiar datos de una captura). */
    private fun call(method: String, path: String, body: String? = null): String {
        val token = graph.session.accessToken ?: error("Sin sesión")
        val c = URL("$server/api$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return (if (c.responseCode < 400) c.inputStream else c.errorStream).bufferedReader().readText()
    }

    private fun waitText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun settle() = repeat(8) { Thread.sleep(250); compose.waitForIdle() }

    private fun app(route: String, dark: Boolean, qualifiers: String, waitFor: String, name: String, before: () -> Unit = {}) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        compose.setContent { KairoTheme(dark = dark) { KairoAppUi(graph, route) {} } }
        waitText(waitFor)
        settle()
        before()
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    /** Desplaza la lista principal hasta que [text] quede arriba, debajo de la barra de estado. */
    private fun scrollTo(text: String) {
        val list = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
        list.performScrollToNode(hasText(text))
        compose.waitForIdle()
        val top = compose.onAllNodesWithText(text).onFirst().fetchSemanticsNode().boundsInRoot.top
        val margin = with(compose.density) { 40.dp.toPx() }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, top - margin) }
        settle()
    }

    @Test fun homePhoneDark() = app("home", true, PHONE, "Tu día", "hoy-telefono-oscuro")
    @Test fun homePhoneLight() = app("home", false, PHONE, "Tu día", "hoy-telefono-claro")
    @Test fun homeFoldOpen() = app("home", true, FOLD_OPEN, "Tu día", "hoy-fold-abierto")
    @Test fun tasksPhone() = app("tasks", true, PHONE, "Por hacer", "tareas-telefono")
    @Test fun tasksFoldLandscape() = app("tasks", false, FOLD_LAND, "En curso", "tareas-fold-horizontal")
    @Test fun notesFoldOpen() = app("notes", true, FOLD_OPEN, "hace", "notas-fold-abierto")
    @Test fun chatPhone() = app("chat", true, PHONE, "Anota", "conversaciones-telefono")
    @Test fun remindersPhone() = app("reminders", false, PHONE, "Sonaron", "recordatorios-telefono-claro")
    @Test fun calendarFoldOpen() = app("calendar", true, FOLD_OPEN, "Octubre", "calendario-fold-abierto")
    @Test fun listsPhone() = app("lists", true, PHONE, "Supermercado", "listas-telefono")
    @Test fun memoryPhone() = app("memory", true, PHONE, "Sofía", "memoria-telefono")
    @Test fun settingsPhone() = app("settings", true, PHONE, "Para que funcione siempre", "ajustes-telefono")
    @Test fun activityPhone() = app("activity", false, PHONE, "Deshacer", "actividad-telefono-claro")

    // --- Familia
    @Test fun familyPhone() = app("family", true, PHONE, "Novedades", "familia-telefono")
    @Test fun familyFoldOpen() = app("family", true, FOLD_OPEN, "Novedades", "familia-fold-abierto")
    @Test fun homeFamilyPhone() = app("home", true, PHONE, "Tu día", "hoy-familia-telefono") { scrollTo("Familia") }
    @Test fun settingsPushPhone() = app("settings", true, PHONE, "Para que funcione siempre", "ajustes-push-telefono") { scrollTo("Avisos al instante, sin Firebase") }

    @Test
    fun tasksFamilyPhone() = app("tasks", true, PHONE, "Por hacer", "tareas-familia-telefono") {
        compose.onNodeWithText("Familiares").performClick()
        settle()
    }

    @Test
    fun calendarFamilyFoldOpen() = app("calendar", true, FOLD_OPEN, "Octubre", "calendario-familia-fold-abierto") {
        // Un día con un evento familiar que agendó otro miembro
        compose.onNodeWithContentDescription("Miércoles 14 de octubre", substring = true).performClick()
        waitText("Reunión de padres")
        settle()
    }

    @Test
    fun taskAssigneePhone() = app("tasks", true, PHONE, "Recoger a Sofía", "tareas-responsable-telefono") {
        compose.onNodeWithText("Recoger a Sofía en el colegio").performClick()
        waitText("Responsable")
        settle()
    }

    /** Invitación creada desde la app: el código grande y "Compartir invitación" (se revoca al terminar). */
    @Test
    fun inviteCodePhone() {
        fun pendingIds() = (Json.parseToJsonElement(call("GET", "/family/invites")) as kotlinx.serialization.json.JsonArray)
            .map { it.jsonObject }.filter { it["usedAt"]?.jsonPrimitive?.content in listOf(null, "null") }
            .map { it["id"]!!.jsonPrimitive.content }.toSet()
        val before = pendingIds()
        try {
            app("family", true, PHONE, "Novedades", "familia-invitar-telefono") {
                compose.onNodeWithText("Invitar").performClick()
                waitText("Invitar a la familia")
                compose.onNodeWithTag("invite-name").performTextInput("Sofía")
                compose.onNodeWithText("Crear invitación").performClick()
                waitText("Compartir invitación")
                settle()
            }
        } finally {
            (pendingIds() - before).forEach { call("DELETE", "/family/invites/$it") }
        }
    }

    /** Formulario para unirse tras revisar un código real (la invitación se revoca al terminar). */
    @Test
    fun joinPhone() {
        val created = Json.parseToJsonElement(call("POST", "/family/invites", """{"name":"Sofía","role":"member"}""")).jsonObject
        val code = created["code"]!!.jsonPrimitive.content
        val inviteId = created["invitation"]!!.jsonObject["id"]!!.jsonPrimitive.content
        try {
            RuntimeEnvironment.setQualifiers(PHONE)
            compose.setContent { KairoTheme(dark = true) { LoginScreen(graph) } }
            compose.waitForIdle()
            compose.onNodeWithText("Tengo un código").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("invite-code").performTextInput(code.lowercase().replace("-", ""))
            compose.onNodeWithText("Continuar").performClick()
            waitText("Te invitaron")
            settle()
            compose.onRoot().captureRoboImage("$out/unirse-telefono.png")
        } finally {
            call("DELETE", "/family/invites/$inviteId")
        }
    }

    @Test
    fun loginPhone() {
        RuntimeEnvironment.setQualifiers(PHONE)
        compose.setContent { KairoTheme(dark = true) { LoginScreen(graph) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/login-telefono.png")
    }

    @Test
    fun orbStates() {
        RuntimeEnvironment.setQualifiers("w720dp-h300dp-xhdpi")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KairoTheme(dark = true) {
                Surface(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(OrbState.Idle to "Reposo", OrbState.Armed to "Atento", OrbState.Listening to "Escucha", OrbState.Thinking to "Piensa", OrbState.Speaking to "Habla").forEach { (s, l) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                KairoOrb(s, if (s == OrbState.Listening || s == OrbState.Speaking) 0.7f else 0f, size = 120.dp)
                                Text(l, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(900)
        compose.onRoot().captureRoboImage("$out/orbe-estados.png")
    }

    @Test
    fun tabletop() {
        RuntimeEnvironment.setQualifiers("w840dp-h700dp-xhdpi")
        compose.mainClock.autoAdvance = false
        val voice = VoiceState(
            phase = VoicePhase.Speaking, level = 0.5f, transcript = "Recuérdame mañana a las 9 llamar al dentista",
            reply = "Listo, te lo recuerdo mañana a las 9 de la mañana.",
            actions = listOf(AgentEvent.ToolEnd("1", "create_reminder", true, "Recordatorio: Llamar al dentista · mañana a las 9:00", EntityRef("reminder", "x", "Llamar al dentista"))),
        )
        compose.setContent { KairoTheme(dark = true) { Surface { TabletopHome(voice, "David", null, {}, {}, {}, {}) } } }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("$out/modo-mesa.png")
    }
}
