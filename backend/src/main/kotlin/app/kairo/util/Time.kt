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

fun zoneOf(tz: String?): ZoneId = try {
    ZoneId.of(tz ?: "UTC")
} catch (_: Exception) {
    ZoneId.of("UTC")
}

/**
 * Parses dates that come from the model or from clients. Accepts:
 * `2026-10-11T09:00:00-05:00`, `2026-10-11T09:00:00Z`, `2026-10-11T09:00` (the user's local time),
 * `2026-10-11 09:00` and `2026-10-11` (9:00 local by default).
 */
fun parseUserDateTime(raw: String, zone: ZoneId, defaultTime: LocalTime = LocalTime.of(9, 0)): Instant {
    val s = raw.trim().replace(' ', 'T')
    runCatching { return OffsetDateTime.parse(s).toInstant() }
    runCatching { return Instant.parse(s) }
    runCatching { return ZonedDateTime.parse(s).toInstant() }
    runCatching { return LocalDateTime.parse(s).atZone(zone).toInstant() }
    runCatching { return LocalDateTime.parse("${s}:00").atZone(zone).toInstant() }
    runCatching { return LocalDate.parse(s.take(10)).atTime(defaultTime).atZone(zone).toInstant() }
    invalid(M.INVALID_DATE, raw)
}

fun parseUserDate(raw: String): LocalDate = LocalDate.parse(raw.trim().take(10))

/** Spanish reads 24-hour times ("15:30"), English 12-hour ones ("3:30 PM"). */
private fun timeFormat(lang: Lang): DateTimeFormatter = when (lang) {
    Lang.ES -> DateTimeFormatter.ofPattern("H:mm", lang.locale)
    Lang.EN -> DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
}

/** "mañana a las 9:00" / "tomorrow at 9:00 AM", or "sábado 11 de octubre a las 9:00" / "Saturday, October 11 at 9:00 AM". */
fun humanDateTime(instant: Instant, zone: ZoneId, now: Instant = Instant.now(), lang: Lang = Lang.DEFAULT): String {
    val z = instant.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val day = when (z.toLocalDate()) {
        today -> M.TODAY.t(lang)
        today.plusDays(1) -> M.TOMORROW.t(lang)
        today.minusDays(1) -> M.YESTERDAY.t(lang)
        else -> humanDate(z.toLocalDate(), today, lang)
    }
    return M.DATE_AT_TIME.t(lang, day, timeFormat(lang).format(z))
}

/** "sábado 11 de octubre" / "Saturday, October 11" (with the year when it is not the current one). */
fun humanDate(date: LocalDate, today: LocalDate? = null, lang: Lang = Lang.DEFAULT): String {
    if (today != null) {
        if (date == today) return M.TODAY.t(lang)
        if (date == today.plusDays(1)) return M.TOMORROW.t(lang)
    }
    val wd = date.dayOfWeek.getDisplayName(TextStyle.FULL, lang.locale)
    val month = date.month.getDisplayName(TextStyle.FULL, lang.locale)
    val yearShown = today != null && date.year != today.year
    return when (lang) {
        Lang.ES -> "$wd ${date.dayOfMonth} de $month" + if (yearShown) " de ${date.year}" else ""
        Lang.EN -> "$wd, $month ${date.dayOfMonth}" + if (yearShown) ", ${date.year}" else ""
    }
}

fun humanTime(instant: Instant, zone: ZoneId, lang: Lang = Lang.DEFAULT): String = timeFormat(lang).format(instant.atZone(zone))

/** Time context injected into the system prompt (always English: it is read by the model, not by the user). */
fun nowDescription(zone: ZoneId, now: Instant = Instant.now()): String {
    val z = now.atZone(zone)
    val wd = z.dayOfWeek.getDisplayName(TextStyle.FULL, Lang.EN.locale)
    val offset = z.offset.id.let { if (it == "Z") "+00:00" else it }
    return "$wd ${z.toLocalDate()} ${DateTimeFormatter.ofPattern("HH:mm").format(z)} (time zone ${zone.id}, UTC$offset)"
}
