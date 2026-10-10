package app.kairo.domain

import app.kairo.util.Lang
import app.kairo.util.M
import org.dmfs.rfc5545.recur.RecurrenceRule
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.TimeZone

/** RFC 5545 recurrence rules (the ones Google Calendar and iCal use), via lib-recur. */
object Recurrence {

    /** Cleans variants that models generate: `RRULE:` prefix, spaces, lowercase. */
    fun normalize(rrule: String?): String? {
        val s = rrule?.trim()?.removePrefix("RRULE:")?.removePrefix("rrule:")?.replace(" ", "")?.uppercase()
        if (s.isNullOrBlank()) return null
        return if (isValid(s)) s else null
    }

    fun isValid(rrule: String): Boolean = runCatching { RecurrenceRule(rrule) }.isSuccess

    /** First occurrence strictly after [after], with the series starting at [start]. */
    fun nextAfter(rrule: String, start: Instant, zone: ZoneId, after: Instant): Instant? {
        val rule = runCatching { RecurrenceRule(rrule) }.getOrNull() ?: return null
        val it = rule.iterator(start.toEpochMilli(), TimeZone.getTimeZone(zone))
        if (after.isAfter(start)) it.fastForward(after.toEpochMilli() + 1)
        var guard = 0
        while (it.hasNext() && guard++ < 10_000) {
            val ms = it.nextMillis()
            if (ms > after.toEpochMilli()) return Instant.ofEpochMilli(ms)
        }
        return null
    }

    /** Occurrences within [from, to) (at most [max]). */
    fun between(rrule: String, start: Instant, zone: ZoneId, from: Instant, to: Instant, max: Int = 500): List<Instant> {
        val rule = runCatching { RecurrenceRule(rrule) }.getOrNull() ?: return emptyList()
        val it = rule.iterator(start.toEpochMilli(), TimeZone.getTimeZone(zone))
        if (from.isAfter(start)) it.fastForward(from.toEpochMilli())
        val out = ArrayList<Instant>()
        var guard = 0
        while (it.hasNext() && out.size < max && guard++ < 20_000) {
            val ms = it.nextMillis()
            if (ms >= to.toEpochMilli()) break
            if (ms >= from.toEpochMilli()) out += Instant.ofEpochMilli(ms)
        }
        return out
    }

    /** Short description for confirmations ("every Monday", "cada lunes") in [lang]. */
    fun describe(rrule: String, lang: Lang = Lang.DEFAULT): String {
        val parts = rrule.split(';').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val days = parts["BYDAY"]?.split(',')?.mapNotNull { DAYS[it.takeLast(2)] }
        fun dayName(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, lang.locale).let { if (lang == Lang.ES) it.lowercase() else it }
        return when (parts["FREQ"]) {
            "DAILY" -> if (interval == 1) M.REC_DAY.t(lang) else M.REC_DAYS.t(lang, interval)
            "WEEKLY" -> when {
                days != null && days.size == 5 && days.containsAll(WEEKDAYS) -> M.REC_WEEKDAYS.t(lang)
                days != null && days.isNotEmpty() ->
                    if (interval == 1) M.REC_WEEKLY_ON.t(lang, days.joinToString(", ") { dayName(it) })
                    else M.REC_WEEKS_ON.t(lang, interval, days.joinToString(", ") { dayName(it) })
                interval == 1 -> M.REC_WEEK.t(lang)
                else -> M.REC_WEEKS.t(lang, interval)
            }
            "MONTHLY" -> if (interval == 1) M.REC_MONTH.t(lang) else M.REC_MONTHS.t(lang, interval)
            "YEARLY" -> M.REC_YEAR.t(lang)
            "HOURLY" -> if (interval == 1) M.REC_HOUR.t(lang) else M.REC_HOURS.t(lang, interval)
            else -> M.REC_OTHER.t(lang)
        }
    }

    private val WEEKDAYS = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
    )
}
