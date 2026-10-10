package app.kairo.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.kairo.android.MainActivity
import app.kairo.android.R
import app.kairo.android.assist.AssistActivity
import app.kairo.android.graph
import app.kairo.android.ui.theme.KairoDarkScheme
import app.kairo.android.ui.theme.KairoLightScheme
import app.kairo.core.model.TodayData
import app.kairo.core.util.timeLabel
import app.kairo.core.util.toInstantOrNull

/** Widget: tu día de un vistazo y un botón para hablar con Kairo. */
class KairoWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(120.dp, 60.dp), DpSize(220.dp, 110.dp), DpSize(280.dp, 200.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = context.graph.prefs.todayCache()?.let { runCatching { context.graph.api.json.decodeFromString(TodayData.serializer(), it) }.getOrNull() }
        provideContent {
            GlanceTheme(colors = ColorProviders(light = KairoLightScheme, dark = KairoDarkScheme)) { Content(today) }
        }
    }

    @Composable
    private fun Content(today: TodayData?) {
        val size = LocalSize.current
        val items = buildList {
            today?.firedReminders?.forEach { add("Sonó: ${it.text}") }
            today?.events?.forEach { e -> add((if (e.allDay) "Hoy" else e.startAt.toInstantOrNull()?.timeLabel() ?: "") + "  " + e.title) }
            today?.reminders?.forEach { r -> add((r.nextFireAt?.toInstantOrNull()?.timeLabel() ?: "") + "  " + r.text) }
            today?.tasksOverdue?.forEach { add("Vencida: ${it.title}") }
            today?.tasksDueToday?.forEach { add("Hoy: ${it.title}") }
        }
        Box(
            GlanceModifier.fillMaxSize().background(GlanceTheme.colors.surface).cornerRadius(24.dp).padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Column(GlanceModifier.fillMaxSize()) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.defaultWeight()) {
                        Text("Kairo", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                        if (size.height > 80.dp) Text(today?.dateLabel ?: "Toca para abrir", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                    }
                    Box(
                        GlanceModifier.size(44.dp).cornerRadius(22.dp).background(ColorProvider(Color(0xFFF0A23B))).clickable(actionStartActivity<AssistActivity>()),
                        contentAlignment = Alignment.Center,
                    ) { Image(ImageProvider(R.drawable.ic_widget_mic), contentDescription = "Hablar con Kairo", modifier = GlanceModifier.size(22.dp)) }
                }
                if (size.height > 100.dp) {
                    Spacer(GlanceModifier.height(10.dp))
                    if (items.isEmpty()) {
                        Text("Día despejado", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
                    } else {
                        items.take(if (size.height > 180.dp) 5 else 2).forEach {
                            Text(it, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp))
                            Spacer(GlanceModifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            if (GlanceAppWidgetManager(context).getGlanceIds(KairoWidget::class.java).isNotEmpty()) KairoWidget().updateAll(context)
        }
    }
}

class KairoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KairoWidget()
}
