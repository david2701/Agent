package app.kairo

import app.kairo.agent.Speech
import app.kairo.domain.ListsRepo
import app.kairo.domain.MemoriesRepo
import app.kairo.domain.NotesRepo
import app.kairo.domain.Recurrence
import app.kairo.domain.TasksRepo
import app.kairo.domain.Text
import app.kairo.integrations.WeatherService
import app.kairo.util.Crypto
import app.kairo.util.Lang
import app.kairo.util.humanDate
import app.kairo.util.humanDateTime
import app.kairo.util.humanTime
import app.kairo.util.parseUserDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val bogota = ZoneId.of("America/Bogota")

    @Test
    fun `dates from the model with and without a zone`() {
        assertEquals(Instant.parse("2026-10-11T14:00:00Z"), parseUserDateTime("2026-10-11T09:00:00-05:00", bogota))
        assertEquals(Instant.parse("2026-10-11T14:00:00Z"), parseUserDateTime("2026-10-11T09:00", bogota))
        assertEquals(Instant.parse("2026-10-11T14:00:00Z"), parseUserDateTime("2026-10-11 09:00", bogota))
        assertEquals(Instant.parse("2026-10-11T14:00:00Z"), parseUserDateTime("2026-10-11", bogota))
        assertEquals(Instant.parse("2026-10-11T09:00:00Z"), parseUserDateTime("2026-10-11T09:00:00Z", bogota))
    }

    @Test
    fun `human dates in english are the default`() {
        val now = Instant.parse("2026-10-10T16:00:00Z") // Saturday 11:00 in Bogota
        assertEquals("tomorrow at 9:00 AM", humanDateTime(Instant.parse("2026-10-11T14:00:00Z"), bogota, now))
        assertEquals("today at 3:30 PM", humanDateTime(Instant.parse("2026-10-10T20:30:00Z"), bogota, now))
        assertEquals("Tuesday, October 13 at 9:00 AM", humanDateTime(Instant.parse("2026-10-13T14:00:00Z"), bogota, now))
        assertEquals("Saturday, October 10", humanDate(LocalDate.of(2026, 10, 10)))
        assertEquals("Monday, January 4, 2027", humanDate(LocalDate.of(2027, 1, 4), LocalDate.of(2026, 10, 10)))
        assertEquals("9:00 AM", humanTime(Instant.parse("2026-10-11T14:00:00Z"), bogota))
    }

    @Test
    fun `human dates in spanish`() {
        val now = Instant.parse("2026-10-10T16:00:00Z") // sábado 11:00 en Bogotá
        assertEquals("mañana a las 9:00", humanDateTime(Instant.parse("2026-10-11T14:00:00Z"), bogota, now, Lang.ES))
        assertEquals("hoy a las 15:30", humanDateTime(Instant.parse("2026-10-10T20:30:00Z"), bogota, now, Lang.ES))
        assertTrue(humanDateTime(Instant.parse("2026-10-13T14:00:00Z"), bogota, now, Lang.ES).startsWith("martes 13 de octubre"))
        assertEquals("sábado 10 de octubre", humanDate(LocalDate.of(2026, 10, 10), lang = Lang.ES))
        assertEquals("lunes 4 de enero de 2027", humanDate(LocalDate.of(2027, 1, 4), LocalDate.of(2026, 10, 10), Lang.ES))
        assertEquals("15:30", humanTime(Instant.parse("2026-10-10T20:30:00Z"), bogota, Lang.ES))
    }

    @Test
    fun `weekly recurrences and normalization`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", Recurrence.normalize("RRULE:FREQ=WEEKLY;BYDAY=MO"))
        assertNull(Recurrence.normalize("every monday"))
        val start = Instant.parse("2026-10-12T13:00:00Z") // Monday 8:00 Bogota
        val next = Recurrence.nextAfter("FREQ=WEEKLY;BYDAY=MO", start, bogota, Instant.parse("2026-10-12T14:00:00Z"))
        assertEquals(Instant.parse("2026-10-19T13:00:00Z"), next)
        val occurrences = Recurrence.between("FREQ=DAILY", start, bogota, start, start.plusSeconds(3 * 86400))
        assertEquals(3, occurrences.size)
    }

    @Test
    fun `recurrence descriptions in both languages`() {
        assertEquals("every Monday", Recurrence.describe("FREQ=WEEKLY;BYDAY=MO"))
        assertEquals("Monday to Friday", Recurrence.describe("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"))
        assertEquals("every day", Recurrence.describe("FREQ=DAILY"))
        assertEquals("every 3 days", Recurrence.describe("FREQ=DAILY;INTERVAL=3"))
        assertEquals("every Monday, Wednesday", Recurrence.describe("FREQ=WEEKLY;BYDAY=MO,WE"))
        assertEquals("every 2 weeks on Friday", Recurrence.describe("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR"))
        assertEquals("every year", Recurrence.describe("FREQ=YEARLY"))
        assertEquals("cada lunes", Recurrence.describe("FREQ=WEEKLY;BYDAY=MO", Lang.ES))
        assertEquals("de lunes a viernes", Recurrence.describe("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", Lang.ES))
        assertEquals("cada día", Recurrence.describe("FREQ=DAILY", Lang.ES))
        assertEquals("cada 2 semanas el viernes", Recurrence.describe("FREQ=WEEKLY;INTERVAL=2;BYDAY=FR", Lang.ES))
    }

    @Test
    fun `markdown cleanup for voice`() {
        assertEquals("Done, I saved the note Wifi password.", Speech.clean("Done, I saved the note **Wifi password**."))
        assertEquals("Milk\nEggs", Speech.clean("- Milk\n- Eggs"))
        assertEquals("Look here", Speech.clean("Look [here](https://example.com)"))
    }

    @Test
    fun `tolerant list names in english and spanish`() {
        assertEquals("super", Text.normalize("la lista del súper"))
        assertEquals("shopping list", Text.normalize("my shopping list"))
        assertEquals("shopping list", Text.normalize("the Shopping List"))
        assertEquals("groceries", Text.normalize("list of groceries"))
        assertTrue(Text.similar("Supermercado", "súper"))
        assertTrue(Text.similar("Shopping list", "my shopping list"))
        assertTrue(Text.similar("Shopping list", "Supermercado"))
        assertTrue(Text.similar("Supermercado", "the groceries"))
        assertTrue(Text.similar("Groceries", "lista del súper"))
        assertTrue(Text.similar("Eggs", "eggs"))
        assertFalse(Text.similar("Pharmacy", "súper"))
        assertFalse(Text.similar("Pharmacy", "shopping list"))
        assertEquals("Supermercado", ListsRepo.prettyName("lista del súper", Lang.ES))
        assertEquals("Supermercado", ListsRepo.prettyName("compras", Lang.ES))
        assertEquals("Shopping list", ListsRepo.prettyName("lista del súper"))
        assertEquals("Shopping list", ListsRepo.prettyName("groceries"))
        assertEquals("Shopping list", ListsRepo.prettyName("the shopping list", Lang.EN))
        assertEquals("Trip to Cartagena", ListsRepo.prettyName("trip to Cartagena"))
        assertEquals("Viaje a Cartagena", ListsRepo.prettyName("viaje a Cartagena", Lang.ES))
    }

    @Test
    fun `place names match across english and spanish`() {
        assertEquals(Text.placeKey("Home"), Text.placeKey("Casa"))
        assertEquals(Text.placeKey("my office"), Text.placeKey("Trabajo"))
        assertEquals(Text.placeKey("Supermarket"), Text.placeKey("súper"))
        assertNotEquals(Text.placeKey("Home"), Text.placeKey("Work"))
        assertEquals("grandmas house", Text.placeKey("Grandma's house"))
    }

    @Test
    fun `domain normalizations`() {
        assertEquals("doing", TasksRepo.normalizeStatus("en curso"))
        assertEquals("doing", TasksRepo.normalizeStatus("in progress"))
        assertEquals("urgent", TasksRepo.normalizePriority("Urgente"))
        assertEquals("urgent", TasksRepo.normalizePriority("urgent"))
        assertEquals("personas", MemoriesRepo.normalizeCategory("familia"))
        assertEquals("personas", MemoriesRepo.normalizeCategory("people"))
        assertEquals("hogar", MemoriesRepo.normalizeCategory("home"))
        assertEquals(listOf("work", "ideas"), NotesRepo.normalizeTags(listOf("#Work", "ideas", "work")))
        assertEquals("Buy a gift for Mia", NotesRepo.deriveTitle("\n# Buy a gift for Mia\nsomething else"))
        assertEquals("Note", NotesRepo.deriveTitle("   "))
        assertEquals("Nota", NotesRepo.deriveTitle("   ", Lang.ES))
    }

    @Test
    fun `weather descriptions follow the language`() {
        assertEquals("clear", WeatherService.describe(0))
        assertEquals("despejado", WeatherService.describe(0, Lang.ES))
        assertEquals("thunderstorm with hail", WeatherService.describe(96))
        assertEquals("tormenta con granizo", WeatherService.describe(96, Lang.ES))
        assertEquals("variable", WeatherService.describe(1234))
    }

    @Test
    fun `encryption and signatures`() {
        val c = Crypto(ByteArray(32) { it.toByte() })
        val enc = c.encrypt("secret-refresh-token")
        assertNotEquals("secret-refresh-token", enc)
        assertEquals("secret-refresh-token", c.decrypt(enc))
        val sig = c.sign("abc:123")
        assertTrue(c.verify("abc:123", sig))
        assertFalse(c.verify("abc:124", sig))
    }
}
