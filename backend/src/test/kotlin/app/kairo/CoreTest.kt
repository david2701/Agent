package app.kairo

import app.kairo.agent.Speech
import app.kairo.domain.ListsRepo
import app.kairo.domain.MemoriesRepo
import app.kairo.domain.NotesRepo
import app.kairo.domain.Recurrence
import app.kairo.domain.TasksRepo
import app.kairo.domain.Text
import app.kairo.util.Crypto
import app.kairo.util.humanDateTime
import app.kairo.util.parseUserDateTime
import java.time.Instant
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
    fun `fechas del modelo con y sin zona`() {
        assertEquals(Instant.parse("2026-10-11T14:00:00Z"), parseUserDateTime("2026-10-11T09:00:00-05:00", bogota))
        assertEquals(Instant.parse("2026-10-11T14:00:00Z"), parseUserDateTime("2026-10-11T09:00", bogota))
        assertEquals(Instant.parse("2026-10-11T14:00:00Z"), parseUserDateTime("2026-10-11 09:00", bogota))
        assertEquals(Instant.parse("2026-10-11T14:00:00Z"), parseUserDateTime("2026-10-11", bogota))
        assertEquals(Instant.parse("2026-10-11T09:00:00Z"), parseUserDateTime("2026-10-11T09:00:00Z", bogota))
    }

    @Test
    fun `texto humano en espanol`() {
        val now = Instant.parse("2026-10-10T16:00:00Z") // sábado 11:00 en Bogotá
        assertEquals("mañana a las 9:00", humanDateTime(Instant.parse("2026-10-11T14:00:00Z"), bogota, now))
        assertEquals("hoy a las 15:30", humanDateTime(Instant.parse("2026-10-10T20:30:00Z"), bogota, now))
        assertTrue(humanDateTime(Instant.parse("2026-10-13T14:00:00Z"), bogota, now).startsWith("martes 13 de octubre"))
    }

    @Test
    fun `recurrencias semanales y normalizacion`() {
        assertEquals("FREQ=WEEKLY;BYDAY=MO", Recurrence.normalize("RRULE:FREQ=WEEKLY;BYDAY=MO"))
        assertNull(Recurrence.normalize("cada lunes"))
        val start = Instant.parse("2026-10-12T13:00:00Z") // lunes 8:00 Bogotá
        val next = Recurrence.nextAfter("FREQ=WEEKLY;BYDAY=MO", start, bogota, Instant.parse("2026-10-12T14:00:00Z"))
        assertEquals(Instant.parse("2026-10-19T13:00:00Z"), next)
        val occurrences = Recurrence.between("FREQ=DAILY", start, bogota, start, start.plusSeconds(3 * 86400))
        assertEquals(3, occurrences.size)
        assertEquals("cada lunes", Recurrence.describe("FREQ=WEEKLY;BYDAY=MO"))
        assertEquals("de lunes a viernes", Recurrence.describe("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"))
    }

    @Test
    fun `limpieza de markdown para voz`() {
        assertEquals("Listo, guardé la nota Clave wifi.", Speech.clean("Listo, guardé la nota **Clave wifi**."))
        assertEquals("Leche\nHuevos", Speech.clean("- Leche\n- Huevos"))
        assertEquals("Mira aquí", Speech.clean("Mira [aquí](https://ejemplo.com)"))
    }

    @Test
    fun `nombres de listas tolerantes`() {
        assertEquals("super", Text.normalize("la lista del súper"))
        assertTrue(Text.similar("Supermercado", "súper"))
        assertTrue(Text.similar("Huevos", "huevos"))
        assertFalse(Text.similar("Farmacia", "súper"))
        assertEquals("Supermercado", ListsRepo.prettyName("lista del súper"))
        assertEquals("Viaje a Cartagena", ListsRepo.prettyName("viaje a Cartagena"))
    }

    @Test
    fun `normalizaciones de dominio`() {
        assertEquals("doing", TasksRepo.normalizeStatus("en curso"))
        assertEquals("urgent", TasksRepo.normalizePriority("Urgente"))
        assertEquals("personas", MemoriesRepo.normalizeCategory("familia"))
        assertEquals(listOf("trabajo", "ideas"), NotesRepo.normalizeTags(listOf("#Trabajo", "ideas", "trabajo")))
        assertEquals("Comprar regalo para Sofía", NotesRepo.deriveTitle("\n# Comprar regalo para Sofía\nalgo más"))
    }

    @Test
    fun `cifrado y firmas`() {
        val c = Crypto(ByteArray(32) { it.toByte() })
        val enc = c.encrypt("refresh-token-secreto")
        assertNotEquals("refresh-token-secreto", enc)
        assertEquals("refresh-token-secreto", c.decrypt(enc))
        val sig = c.sign("abc:123")
        assertTrue(c.verify("abc:123", sig))
        assertFalse(c.verify("abc:124", sig))
    }
}
