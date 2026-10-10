package app.kairo.core.util

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Locale used to format dates, times and names of weekdays and months.
 *
 * The app ships English (default) and Spanish. Spanish devices get Spanish; anything else gets English,
 * so dates always match the language of the strings. Android applies the per-app language to
 * [Locale.getDefault], so this also follows the system language picker.
 */
object AppLocale {
    fun resolve(base: Locale = Locale.getDefault()): Locale = when (base.language) {
        "es", "en" -> base
        else -> Locale.US
    }
}

val Locale.isSpanish: Boolean get() = language == "es"

fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(take(10)) }.getOrNull()

fun Instant.local(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = atZone(zone)

/** Short localized time: "3:45 PM" in English, "15:45" in Spanish. */
fun Instant.timeLabel(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = AppLocale.resolve()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(atZone(zone))

/** Days that have their own word ("today", "tomorrow", "yesterday"); the UI maps them to string resources. */
enum class RelativeDay { Yesterday, Today, Tomorrow }

fun LocalDate.relativeDay(today: LocalDate = LocalDate.now()): RelativeDay? = when (this) {
    today -> RelativeDay.Today
    today.plusDays(1) -> RelativeDay.Tomorrow
    today.minusDays(1) -> RelativeDay.Yesterday
    else -> null
}

private fun pattern(locale: Locale, en: String, es: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (locale.isSpanish) es else en, locale)

/** "Tue, Oct 14" / "mar 14 oct"; with another year, "Oct 14, 2025" / "14 oct 2025". */
fun LocalDate.shortDate(today: LocalDate = LocalDate.now(), locale: Locale = AppLocale.resolve()): String {
    val text = if (year == today.year) {
        pattern(locale, "EEE, MMM d", "EEE d MMM").format(this)
    } else {
        pattern(locale, "MMM d, yyyy", "d MMM yyyy").format(this)
    }
    return text.replace(".", "")
}

/** "Tuesday, October 14" / "Martes 14 de octubre", with a capital first letter. */
fun LocalDate.longDate(locale: Locale = AppLocale.resolve()): String =
    pattern(locale, "EEEE, MMMM d", "EEEE d 'de' MMMM").format(this).replaceFirstChar { it.titlecase(locale) }

/** "October 2026" / "Octubre 2026". */
fun YearMonth.monthYearLabel(locale: Locale = AppLocale.resolve()): String =
    format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) }

/** One-letter weekday initial ("M", "T"...), upper case. */
fun DayOfWeek.narrowLabel(locale: Locale = AppLocale.resolve()): String =
    getDisplayName(TextStyle.NARROW, locale).uppercase(locale)

/** First day of the week for the locale (Sunday in the US, Monday in Spain). */
fun Locale.firstDayOfWeek(): DayOfWeek = java.time.temporal.WeekFields.of(this).firstDayOfWeek

/** How long ago something happened, as a typed value the UI turns into text with string resources. */
sealed interface RelativeTime {
    data object Now : RelativeTime
    data class MinutesAgo(val minutes: Int) : RelativeTime
    data class HoursAgo(val hours: Int) : RelativeTime
    data class OnDay(val date: LocalDate) : RelativeTime
}

fun Instant.relativeTime(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): RelativeTime {
    val secs = now.epochSecond - epochSecond
    return when {
        secs < 60 -> RelativeTime.Now
        secs < 3600 -> RelativeTime.MinutesAgo((secs / 60).toInt())
        secs < 86400 -> RelativeTime.HoursAgo((secs / 3600).toInt())
        else -> RelativeTime.OnDay(atZone(zone).toLocalDate())
    }
}
