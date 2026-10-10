package app.kairo.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val Spanish: Locale = Locale.forLanguageTag("es")

fun zoneOf(tz: String?): ZoneId = try {
    ZoneId.of(tz ?: "UTC")
} catch (_: Exception) {
    ZoneId.of("UTC")
}

/**
 * Interpreta fechas que llegan del modelo o de los clientes. Acepta:
 * `2026-10-11T09:00:00-05:00`, `2026-10-11T09:00:00Z`, `2026-10-11T09:00` (hora local del usuario),
 * `2026-10-11 09:00` y `2026-10-11` (9:00 local por defecto).
 */
fun parseUserDateTime(raw: String, zone: ZoneId, defaultTime: LocalTime = LocalTime.of(9, 0)): Instant {
    val s = raw.trim().replace(' ', 'T')
    runCatching { return OffsetDateTime.parse(s).toInstant() }
    runCatching { return Instant.parse(s) }
    runCatching { return ZonedDateTime.parse(s).toInstant() }
    runCatching { return LocalDateTime.parse(s).atZone(zone).toInstant() }
    runCatching { return LocalDateTime.parse("${s}:00").atZone(zone).toInstant() }
    runCatching { return LocalDate.parse(s.take(10)).atTime(defaultTime).atZone(zone).toInstant() }
    throw IllegalArgumentException("Fecha no válida: $raw")
}

fun parseUserDate(raw: String): LocalDate = LocalDate.parse(raw.trim().take(10))

private val timeFmt = DateTimeFormatter.ofPattern("H:mm")

/** "sábado 11 de octubre, 9:00" en la zona del usuario. */
fun humanDateTime(instant: Instant, zone: ZoneId, now: Instant = Instant.now()): String {
    val z = instant.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val day = when (z.toLocalDate()) {
        today -> "hoy"
        today.plusDays(1) -> "mañana"
        today.minusDays(1) -> "ayer"
        else -> humanDate(z.toLocalDate(), today)
    }
    return "$day a las ${timeFmt.format(z)}"
}

fun humanDate(date: LocalDate, today: LocalDate? = null): String {
    if (today != null) {
        if (date == today) return "hoy"
        if (date == today.plusDays(1)) return "mañana"
    }
    val wd = date.dayOfWeek.getDisplayName(TextStyle.FULL, Spanish)
    val month = date.month.getDisplayName(TextStyle.FULL, Spanish)
    val year = if (today != null && date.year != today.year) " de ${date.year}" else ""
    return "$wd ${date.dayOfMonth} de $month$year"
}

fun humanTime(instant: Instant, zone: ZoneId): String = timeFmt.format(instant.atZone(zone))

/** Contexto temporal que se inyecta en el prompt del sistema. */
fun nowDescription(zone: ZoneId, now: Instant = Instant.now()): String {
    val z = now.atZone(zone)
    val wd = z.dayOfWeek.getDisplayName(TextStyle.FULL, Spanish)
    val offset = z.offset.id.let { if (it == "Z") "+00:00" else it }
    return "$wd ${z.toLocalDate()} ${DateTimeFormatter.ofPattern("HH:mm").format(z)} (zona ${zone.id}, UTC$offset)"
}
