package app.kairo.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.window.core.layout.WindowSizeClass
import app.kairo.android.AppGraph
import app.kairo.android.ui.activity.ActivityScreen
import app.kairo.android.ui.calendar.CalendarScreen
import app.kairo.android.ui.chat.ChatScreen
import app.kairo.android.ui.family.FamilyScreen
import app.kairo.android.ui.home.HomeScreen
import app.kairo.android.ui.lists.ListsScreen
import app.kairo.android.ui.login.LoginScreen
import app.kairo.android.ui.memory.MemoryScreen
import app.kairo.android.ui.more.MoreScreen
import app.kairo.android.ui.notes.NotesScreen
import app.kairo.android.ui.recordings.RecordingsScreen
import app.kairo.android.ui.reminders.RemindersScreen
import app.kairo.android.ui.settings.SettingsScreen
import app.kairo.android.ui.tasks.TasksScreen
import app.kairo.core.session.Session

enum class Destination(val label: String, val icon: ImageVector) {
    Home("Hoy", Icons.Rounded.WbSunny),
    Chat("Chat", Icons.Rounded.ChatBubbleOutline),
    Tasks("Tareas", Icons.Rounded.TaskAlt),
    Notes("Notas", Icons.Rounded.StickyNote2),
    Reminders("Recordatorios", Icons.Rounded.NotificationsNone),
    Calendar("Calendario", Icons.Rounded.CalendarMonth),
    Lists("Listas", Icons.Rounded.ShoppingBasket),
    Family("Familia", Icons.Rounded.Groups),
    Memory("Memoria", Icons.Rounded.Psychology),
    Recordings("Grabaciones", Icons.Rounded.GraphicEq),
    Activity("Actividad", Icons.Rounded.History),
    Settings("Ajustes", Icons.Rounded.Settings),
    More("Más", Icons.Rounded.MoreHoriz);

    companion object {
        val compactBar = listOf(Home, Chat, Tasks, Notes, More)
        val rail = listOf(Home, Chat, Tasks, Notes, Reminders, Calendar, Lists, More)
        val inMore = listOf(Reminders, Calendar, Lists, Family, Memory, Recordings, Activity, Settings)
        val inMoreWide = listOf(Family, Memory, Recordings, Activity, Settings)

        fun fromRoute(route: String?): Destination? = when (route) {
            "home", "rearm" -> Home
            "chat" -> Chat
            "tasks" -> Tasks
            "notes" -> Notes
            "reminders" -> Reminders
            "calendar" -> Calendar
            "lists" -> Lists
            "family" -> Family
            "memory" -> Memory
            "recordings" -> Recordings
            "activity" -> Activity
            "settings" -> Settings
            else -> null
        }

        fun forEntity(type: String): Destination = when (type) {
            "note" -> Notes
            "task", "project" -> Tasks
            "reminder", "place" -> Reminders
            "list" -> Lists
            "event" -> Calendar
            "memory" -> Memory
            "recording" -> Recordings
            "family", "notice" -> Family
            else -> Home
        }
    }
}

@Composable
fun KairoAppUi(graph: AppGraph, deepLink: String?, onDeepLinkConsumed: () -> Unit) {
    val session by graph.session.session.collectAsState(initial = LoadingSession)
    when (val s = session) {
        LoadingSession -> Box(Modifier.fillMaxSize())
        null -> LoginScreen(graph)
        else -> MainScaffold(graph, s, deepLink, onDeepLinkConsumed)
    }
}

/** Marcador mientras DataStore carga la sesión (evita parpadeo del login). */
private val LoadingSession = Session("", app.kairo.core.model.User("", "", "", "", ""), "")

@Composable
private fun MainScaffold(graph: AppGraph, session: Session, deepLink: String?, onDeepLinkConsumed: () -> Unit) {
    var current by rememberSaveable { mutableStateOf(Destination.Home) }
    val adaptive = currentWindowAdaptiveInfo()
    val wide = adaptive.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val items = if (wide) Destination.rail else Destination.compactBar

    LaunchedEffect(deepLink) {
        Destination.fromRoute(deepLink)?.let { current = it }
        if (deepLink != null) onDeepLinkConsumed()
    }

    val navigate: (Destination) -> Unit = { current = it }
    val selected = if (current in (if (wide) Destination.inMoreWide else Destination.inMore)) Destination.More else current

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            items.forEach { d ->
                item(
                    selected = selected == d,
                    onClick = { current = d },
                    icon = { Icon(d.icon, contentDescription = null) },
                    label = { Text(d.label, style = MaterialTheme.typography.labelMedium) },
                    alwaysShowLabel = !wide || adaptive.windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND),
                )
            }
        },
        layoutType = if (wide) NavigationSuiteType.NavigationRail else NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptive),
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "destination",
        ) { dest ->
            when (dest) {
                Destination.Home -> HomeScreen(graph, session, navigate)
                Destination.Chat -> ChatScreen(graph, navigate)
                Destination.Tasks -> TasksScreen(graph)
                Destination.Notes -> NotesScreen(graph)
                Destination.Reminders -> RemindersScreen(graph)
                Destination.Calendar -> CalendarScreen(graph)
                Destination.Lists -> ListsScreen(graph)
                Destination.Memory -> MemoryScreen(graph)
                Destination.Recordings -> RecordingsScreen(graph, navigate)
                Destination.Activity -> ActivityScreen(graph)
                Destination.Family -> FamilyScreen(graph, session, navigate)
                Destination.Settings -> SettingsScreen(graph, session, navigate)
                Destination.More -> MoreScreen(navigate, if (wide) Destination.inMoreWide else Destination.inMore)
            }
        }
    }
}
