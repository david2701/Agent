package app.kairo.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

class TimeTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")
    private val today = LocalDate.of(2026, 10, 10)

    @Test
    fun `app locale is Spanish or English`() {
        assertEquals("es", AppLocale.resolve(Locale.forLanguageTag("es-MX")).language)
        assertEquals("en", AppLocale.resolve(Locale.UK).language)
        assertEquals("en", AppLocale.resolve(Locale.FRANCE).language)
        assertEquals("en", AppLocale.resolve(Locale.JAPAN).language)
    }

    @Test
    fun `days with their own word`() {
        assertEquals(RelativeDay.Today, today.relativeDay(today))
        assertEquals(RelativeDay.Tomorrow, today.plusDays(1).relativeDay(today))
        assertEquals(RelativeDay.Yesterday, today.minusDays(1).relativeDay(today))
        assertNull(today.plusDays(2).relativeDay(today))
    }

    @Test
    fun `short dates follow the locale`() {
        val d = LocalDate.of(2026, 10, 14)
        assertEquals("Wed, Oct 14", d.shortDate(today, en))
        assertEquals("mi\u00e9 14 oct", d.shortDate(today, es))
        assertEquals("Oct 14, 2027", LocalDate.of(2027, 10, 14).shortDate(today, en))
        assertEquals("14 oct 2027", LocalDate.of(2027, 10, 14).shortDate(today, es))
    }

    @Test
    fun `long dates and month names follow the locale`() {
        val d = LocalDate.of(2026, 10, 14)
        assertEquals("Wednesday, October 14", d.longDate(en))
        assertEquals("Miércoles 14 de octubre", d.longDate(es))
        assertEquals("October 2026", YearMonth.of(2026, 10).monthYearLabel(en))
        assertEquals("Octubre 2026", YearMonth.of(2026, 10).monthYearLabel(es))
    }

    @Test
    fun `weekday initials and first day of the week`() {
        assertEquals("M", DayOfWeek.MONDAY.narrowLabel(en))
        assertEquals("L", DayOfWeek.MONDAY.narrowLabel(es))
        assertEquals(DayOfWeek.SUNDAY, Locale.US.firstDayOfWeek())
        assertEquals(DayOfWeek.MONDAY, es.firstDayOfWeek())
    }

    @Test
    fun `times use the locale clock`() {
        val at = Instant.parse("2026-10-10T20:45:00Z")
        val zone = ZoneId.of("America/Bogota")
        assertTrue(at.timeLabel(zone, en).replace(' ', ' ').replace(' ', ' ') == "3:45 PM")
        assertEquals("15:45", at.timeLabel(zone, es))
    }

    @Test
    fun `relative times are typed values`() {
        val now = Instant.parse("2026-10-10T12:00:00Z")
        assertEquals(RelativeTime.Now, now.minusSeconds(20).relativeTime(now))
        assertEquals(RelativeTime.MinutesAgo(5), now.minusSeconds(5 * 60 + 10).relativeTime(now))
        assertEquals(RelativeTime.HoursAgo(2), now.minusSeconds(2 * 3600 + 5).relativeTime(now))
        val old = now.minusSeconds(3 * 86_400).relativeTime(now, ZoneId.of("UTC"))
        assertEquals(RelativeTime.OnDay(LocalDate.of(2026, 10, 7)), old)
    }
}
