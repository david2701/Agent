package app.kairo.android.ui.common

import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.kairo.android.R
import app.kairo.core.util.AppLocale
import app.kairo.core.util.RelativeDay
import app.kairo.core.util.RelativeTime
import app.kairo.core.util.longDate
import app.kairo.core.util.relativeDay
import app.kairo.core.util.relativeTime
import app.kairo.core.util.shortDate
import app.kairo.core.util.timeLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

// Localized text for dates, times and relative times. The pure date logic lives in core (no Android
// resources there); the words ("today", "5 min ago") come from string resources so they follow the
// app language.

/** Locale for dates and numbers: the app's language (Spanish or English). */
fun Context.formatLocale(): Locale = AppLocale.resolve(resources.configuration.locales[0])

/** "today", "tomorrow", "yesterday" or "Tue, Oct 14" (lower case, like the words it replaces). */
fun Context.dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date.relativeDay(today)) {
    RelativeDay.Today -> getString(R.string.day_today)
    RelativeDay.Tomorrow -> getString(R.string.day_tomorrow)
    RelativeDay.Yesterday -> getString(R.string.day_yesterday)
    null -> date.shortDate(today, formatLocale())
}

fun Context.timeLabel(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String = at.timeLabel(zone, formatLocale())

fun Context.longDateLabel(date: LocalDate): String = date.longDate(formatLocale())

/** "today - 9:00 AM". */
fun Context.dayTimeLabel(at: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
    dayLabel(at.atZone(zone).toLocalDate()) + " \u00B7 " + timeLabel(at, zone)

/** "5 min ago", "2 h ago", "yesterday", "Oct 12". */
fun Context.relativeLabel(at: Instant, now: Instant = Instant.now()): String = when (val r = at.relativeTime(now)) {
    RelativeTime.Now -> getString(R.string.time_now)
    is RelativeTime.MinutesAgo -> getString(R.string.time_minutes_ago, r.minutes)
    is RelativeTime.HoursAgo -> getString(R.string.time_hours_ago, r.hours)
    is RelativeTime.OnDay -> dayLabel(r.date)
}

/** Clock time following the device's 12/24 hour setting ("3:45 PM" or "15:45"). */
fun Context.clockLabel(time: LocalTime): String {
    val locale = formatLocale()
    val pattern = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm a"
    return DateTimeFormatter.ofPattern(pattern, locale).format(time)
}

/**
 * Describes a recurrence rule (RRULE) in the app language: "Every day", "Weekdays", "Every Mon, Wed".
 * Weekday names come from the locale, not from a hardcoded list.
 */
fun Context.describeRecurrence(rrule: String): String {
    val parts = rrule.split(';').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    val locale = formatLocale()
    val days = mapOf("MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY)
    return when (parts["FREQ"]) {
        "DAILY" -> getString(R.string.rec_daily)
        "WEEKLY" -> {
            val byDay = parts["BYDAY"]?.split(',')?.mapNotNull { days[it.takeLast(2)] }
            when {
                byDay == null -> getString(R.string.rec_weekly)
                byDay.size == 5 && DayOfWeek.SATURDAY !in byDay && DayOfWeek.SUNDAY !in byDay -> getString(R.string.rec_weekdays)
                else -> getString(R.string.rec_every_days, byDay.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale).trimEnd('.') })
            }
        }
        "MONTHLY" -> getString(R.string.rec_monthly)
        "YEARLY" -> getString(R.string.rec_yearly)
        else -> getString(R.string.rec_repeats)
    }
}

/** Greeting for the local hour (same bands as the server: 5-11 morning, 12-18 afternoon, else evening). */
fun Context.greetingForHour(hour: Int): String = getString(
    when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..18 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    },
)

fun Context.greeting(name: String, hour: Int = java.time.LocalTime.now().hour): String = getString(R.string.home_greeting, greetingForHour(hour), name)

/** "Hey Kairo" in English, "Oye Kairo" in Spanish: what the UI tells people to say. */
fun Context.wakePhrase(): String = getString(R.string.wake_phrase)

// Composable wrappers. They read LocalConfiguration so the text updates if the language changes.

@Composable
private fun rememberContext(): Context {
    LocalConfiguration.current
    return LocalContext.current
}

@Composable
fun LocalDate.dayLabel(today: LocalDate = LocalDate.now()): String = rememberContext().dayLabel(this, today)

@Composable
fun LocalDate.longDateLabel(): String = rememberContext().longDateLabel(this)

@Composable
fun Instant.dayTimeLabel(zone: ZoneId = ZoneId.systemDefault()): String = rememberContext().dayTimeLabel(this, zone)

@Composable
fun Instant.relativeLabel(): String = rememberContext().relativeLabel(this)

@Composable
fun String.recurrenceLabel(): String = rememberContext().describeRecurrence(this)

@Composable
fun LocalTime.clockLabel(): String = rememberContext().clockLabel(this)

@Composable
fun rememberFormatLocale(): Locale = rememberContext().formatLocale()

@Composable
fun wakePhrase(): String = rememberContext().wakePhrase()

/** Capitalizes the first letter using the app locale ("today" -> "Today"). */
fun String.sentenceCase(locale: Locale = Locale.getDefault()): String = replaceFirstChar { it.titlecase(locale) }
