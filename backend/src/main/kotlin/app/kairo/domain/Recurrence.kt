package app.kairo.domain

import org.dmfs.rfc5545.recur.RecurrenceRule
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone

/** Reglas de recurrencia RFC 5545 (las que usan Google Calendar e iCal), vía lib-recur. */
object Recurrence {

    /** Limpia variantes que generan los modelos: prefijo `RRULE:`, espacios, minúsculas. */
    fun normalize(rrule: String?): String? {
        val s = rrule?.trim()?.removePrefix("RRULE:")?.removePrefix("rrule:")?.replace(" ", "")?.uppercase()
        if (s.isNullOrBlank()) return null
        return if (isValid(s)) s else null
    }

    fun isValid(rrule: String): Boolean = runCatching { RecurrenceRule(rrule) }.isSuccess

    /** Primera ocurrencia estrictamente posterior a [after], empezando la serie en [start]. */
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

    /** Ocurrencias dentro de [from, to) (máximo [max]). */
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

    /** Descripción breve en español para confirmaciones ("cada lunes", "cada día"). */
    fun describe(rrule: String): String {
        val parts = rrule.split(';').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val days = parts["BYDAY"]?.split(',')?.mapNotNull { DAYS[it.takeLast(2)] }
        return when (parts["FREQ"]) {
            "DAILY" -> if (interval == 1) "cada día" else "cada $interval días"
            "WEEKLY" -> when {
                days != null && days.size == 5 && days.containsAll(listOf("lunes", "martes", "miércoles", "jueves", "viernes")) -> "de lunes a viernes"
                days != null && days.isNotEmpty() -> (if (interval == 1) "cada " else "cada $interval semanas el ") + days.joinToString(", ")
                interval == 1 -> "cada semana"
                else -> "cada $interval semanas"
            }
            "MONTHLY" -> if (interval == 1) "cada mes" else "cada $interval meses"
            "YEARLY" -> "cada año"
            "HOURLY" -> if (interval == 1) "cada hora" else "cada $interval horas"
            else -> "recurrente"
        }
    }

    private val DAYS = mapOf("MO" to "lunes", "TU" to "martes", "WE" to "miércoles", "TH" to "jueves", "FR" to "viernes", "SA" to "sábado", "SU" to "domingo")
}
