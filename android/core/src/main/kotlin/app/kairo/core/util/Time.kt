package app.kairo.core.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val Spanish: Locale = Locale.forLanguageTag("es")

fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(take(10)) }.getOrNull()

fun Instant.local(zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime = atZone(zone)

private val hm = DateTimeFormatter.ofPattern("H:mm")

fun Instant.timeLabel(zone: ZoneId = ZoneId.systemDefault()): String = hm.format(atZone(zone))

/** "hoy", "mañana", "ayer" o "mar 14 oct". */
fun LocalDate.dayLabel(today: LocalDate = LocalDate.now()): String = when (this) {
    today -> "hoy"
    today.plusDays(1) -> "mañana"
    today.minusDays(1) -> "ayer"
    else -> {
        val wd = dayOfWeek.getDisplayName(TextStyle.SHORT, Spanish).trimEnd('.')
        val m = month.getDisplayName(TextStyle.SHORT, Spanish).trimEnd('.')
        if (year == today.year) "$wd $dayOfMonth $m" else "$dayOfMonth $m $year"
    }
}

fun Instant.dayTimeLabel(zone: ZoneId = ZoneId.systemDefault()): String {
    val z = atZone(zone)
    return "${z.toLocalDate().dayLabel()} · ${hm.format(z)}"
}

fun LocalDate.longLabel(): String {
    val wd = dayOfWeek.getDisplayName(TextStyle.FULL, Spanish)
    val m = month.getDisplayName(TextStyle.FULL, Spanish)
    return "${wd.replaceFirstChar { it.uppercase() }} $dayOfMonth de $m"
}

/** "hace 5 min", "hace 2 h", "ayer", "12 oct". */
fun Instant.relativeLabel(now: Instant = Instant.now()): String {
    val secs = now.epochSecond - epochSecond
    return when {
        secs < 60 -> "ahora"
        secs < 3600 -> "hace ${secs / 60} min"
        secs < 86400 -> "hace ${secs / 3600} h"
        else -> atZone(ZoneId.systemDefault()).toLocalDate().dayLabel()
    }
}
