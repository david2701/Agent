package app.kairo.android

import android.content.Context
import androidx.annotation.StringRes
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
import app.kairo.core.model.EntityRef
import app.kairo.core.util.longDate
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.util.Locale

/** Test application: no WorkManager and no realtime connection. */
class TestKairoApp : KairoApplication() {
    override fun startBackground() = Unit
}

private const val PHONE = "w411dp-h891dp-xxhdpi"
private const val FOLD_OPEN = "w700dp-h840dp-xhdpi"
private const val FOLD_LAND = "w900dp-h700dp-xhdpi"

/**
 * UI screenshots with Roborazzi (rendered on the JVM, no emulator).
 * The data comes from [ScreenshotFixtures] (English sample data served by an in-process HTTP server),
 * so no backend is needed. The tests only run when recording:
 *
 *     ./gradlew :app:recordRoborazziDebug
 *
 * Images go to docs/screenshots/android (English file names). To render the Spanish interface set
 * KAIRO_SCREENSHOT_LOCALE=es; those images go to a subfolder named after the locale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestKairoApp::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val locale = System.getProperty("kairo.screenshot.locale")?.takeIf { it.isNotBlank() } ?: "en"
    private val out = (System.getProperty("kairo.screenshots") ?: "build/screenshots").let { if (locale == "en") it else "$it/$locale" }
    private val graph get() = ApplicationProvider.getApplicationContext<KairoApplication>().graph

    @Before
    fun login() {
        assumeTrue("Screenshots only run when recording (recordRoborazziDebug)", System.getProperty("roborazzi.test.record") == "true")
        ScreenshotFixtures.resetInvites()
        val server = ScreenshotFixtures.start()
        runBlocking { graph.session.saveForTest(server, ScreenshotFixtures.user, "device-1", "test-token") }
    }

    /** A string from the app resources in the language being rendered. */
    private fun str(@StringRes id: Int, vararg args: Any): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun qualifiers(size: String) = "$locale-$size"

    private fun waitText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun settle() = repeat(8) { Thread.sleep(250); compose.waitForIdle() }

    private fun app(route: String, dark: Boolean, size: String, waitFor: () -> String, name: String, before: () -> Unit = {}) {
        RuntimeEnvironment.setQualifiers(qualifiers(size))
        compose.setContent { KairoTheme(dark = dark) { KairoAppUi(graph, route) {} } }
        waitText(waitFor())
        settle()
        before()
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    /** Scrolls the main list until [text] is at the top, below the status bar. */
    private fun scrollTo(text: String) {
        val list = compose.onAllNodes(hasScrollToNodeAction()).onFirst()
        list.performScrollToNode(hasText(text))
        compose.waitForIdle()
        val top = compose.onAllNodesWithText(text).onFirst().fetchSemanticsNode().boundsInRoot.top
        val margin = with(compose.density) { 40.dp.toPx() }
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, top - margin) }
        settle()
    }

    @Test fun todayPhoneDark() = app("home", true, PHONE, { str(R.string.home_your_day) }, "today-phone-dark")
    @Test fun todayPhoneLight() = app("home", false, PHONE, { str(R.string.home_your_day) }, "today-phone-light")
    @Test fun todayFoldOpen() = app("home", true, FOLD_OPEN, { str(R.string.home_your_day) }, "today-fold-open")
    @Test fun tasksPhone() = app("tasks", true, PHONE, { str(R.string.tasks_col_todo) }, "tasks-phone")
    @Test fun tasksFoldLandscape() = app("tasks", false, FOLD_LAND, { str(R.string.tasks_col_doing) }, "tasks-fold-landscape")
    @Test fun notesFoldOpen() = app("notes", true, FOLD_OPEN, { "Plumber visit" }, "notes-fold-open")
    @Test fun chatPhone() = app("chat", true, PHONE, { "plumber" }, "chat-phone")
    @Test fun remindersPhone() = app("reminders", false, PHONE, { str(R.string.home_went_off) }, "reminders-phone-light")
    @Test fun calendarFoldOpen() = app("calendar", true, FOLD_OPEN, { LocalDate.now().year.toString() }, "calendar-fold-open")
    @Test fun listsPhone() = app("lists", true, PHONE, { "Groceries" }, "lists-phone")
    @Test fun memoryPhone() = app("memory", true, PHONE, { "Mia" }, "memory-phone")
    @Test fun settingsPhone() = app("settings", true, PHONE, { str(R.string.set_always_group) }, "settings-phone")
    @Test fun activityPhone() = app("activity", false, PHONE, { str(R.string.act_undo) }, "activity-phone-light")

    // --- Family
    @Test fun familyPhone() = app("family", true, PHONE, { str(R.string.fam_news) }, "family-phone")
    @Test fun familyFoldOpen() = app("family", true, FOLD_OPEN, { str(R.string.fam_news) }, "family-fold-open")
    @Test fun todayFamilyPhone() = app("home", true, PHONE, { str(R.string.home_your_day) }, "today-family-phone") { scrollTo(str(R.string.nav_family)) }
    @Test fun settingsPushPhone() = app("settings", true, PHONE, { str(R.string.set_always_group) }, "settings-push-phone") { scrollTo(str(R.string.set_push_group)) }

    @Test
    fun tasksFamilyPhone() = app("tasks", true, PHONE, { str(R.string.tasks_col_todo) }, "tasks-family-phone") {
        compose.onAllNodesWithText(str(R.string.tasks_filter_family)).onFirst().performClick()
        settle()
    }

    @Test
    fun calendarFamilyFoldOpen() = app("calendar", true, FOLD_OPEN, { LocalDate.now().year.toString() }, "calendar-family-fold-open") {
        // A day with a family event that another member scheduled
        compose.onNodeWithContentDescription(ScreenshotFixtures.parentsMeetingDay.longDate(Locale.forLanguageTag(locale)), substring = true).performClick()
        waitText("Parent-teacher meeting")
        settle()
    }

    @Test
    fun taskAssigneePhone() = app("tasks", true, PHONE, { "Pick up Mia" }, "task-assignee-phone") {
        compose.onNodeWithText("Pick up Mia from school").performClick()
        waitText(str(R.string.tasks_f_assignee))
        settle()
    }

    /** Invitation created from the app: the big code and "Share invitation". */
    @Test
    fun inviteCodePhone() = app("family", true, PHONE, { str(R.string.fam_news) }, "family-invite-phone") {
        compose.onAllNodesWithText(str(R.string.fam_invite_button)).onFirst().performClick()
        waitText(str(R.string.fam_invite_title))
        compose.onNodeWithTag("invite-name").performTextInput("Mia")
        compose.onNodeWithText(str(R.string.fam_create_invite)).performClick()
        waitText(str(R.string.fam_share_invite))
        settle()
    }

    /** Join form after looking up a code (invite code in the canonical `/join?code=` format). */
    @Test
    fun joinPhone() {
        RuntimeEnvironment.setQualifiers(qualifiers(PHONE))
        compose.setContent { KairoTheme(dark = true) { LoginScreen(graph) } }
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.login_code_title)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("invite-code").performTextInput(ScreenshotFixtures.inviteCode.lowercase().replace("-", ""))
        compose.onNodeWithText(str(R.string.login_action_continue)).performClick()
        waitText(str(R.string.login_join_title, "Rivera family"))
        settle()
        compose.onRoot().captureRoboImage("$out/join-phone.png")
    }

    @Test
    fun loginPhone() {
        RuntimeEnvironment.setQualifiers(qualifiers(PHONE))
        compose.setContent { KairoTheme(dark = true) { LoginScreen(graph) } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$out/login-phone.png")
    }

    @Test
    fun orbStates() {
        RuntimeEnvironment.setQualifiers(qualifiers("w720dp-h300dp-xhdpi"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            KairoTheme(dark = true) {
                Surface(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(OrbState.Idle to "Idle", OrbState.Armed to "Armed", OrbState.Listening to "Listening", OrbState.Thinking to "Thinking", OrbState.Speaking to "Speaking").forEach { (s, l) ->
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
        compose.onRoot().captureRoboImage("$out/orb-states.png")
    }

    @Test
    fun tabletopMode() {
        RuntimeEnvironment.setQualifiers(qualifiers("w840dp-h700dp-xhdpi"))
        compose.mainClock.autoAdvance = false
        val voice = VoiceState(
            phase = VoicePhase.Speaking, level = 0.5f, transcript = "Remind me tomorrow at 9 to call the dentist",
            reply = "Done, I'll remind you tomorrow at 9 AM.",
            actions = listOf(AgentEvent.ToolEnd("1", "create_reminder", true, "Reminder: Call the dentist · tomorrow at 9:00 AM", EntityRef("reminder", "x", "Call the dentist"))),
        )
        compose.setContent { KairoTheme(dark = true) { Surface { TabletopHome(voice, "Alex", null, {}, {}, {}, {}) } } }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("$out/tabletop-mode.png")
    }
}
