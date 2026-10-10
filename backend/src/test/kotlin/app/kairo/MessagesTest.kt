package app.kairo

import app.kairo.domain.SettingsService
import app.kairo.domain.UserSettings
import app.kairo.domain.UsersRepo
import app.kairo.db.Db
import app.kairo.util.Lang
import app.kairo.util.M
import app.kairo.util.Plain
import app.kairo.util.localized
import app.kairo.util.say
import com.zaxxer.hikari.HikariDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MessagesTest {

    @Test
    fun `every key exists in both languages with the same placeholders`() {
        for (m in M.entries) {
            assertTrue(m.en.isNotBlank(), "${m.name}: English text is blank")
            assertTrue(m.es.isNotBlank(), "${m.name}: Spanish text is blank")
            assertEquals(M.placeholders(m.en), M.placeholders(m.es), "${m.name}: placeholders differ between languages")
        }
    }

    @Test
    fun `english texts contain no spanish-only characters`() {
        val spanishOnly = Regex("[áéíóúñ¿¡ÁÉÍÓÚÑ]")
        // Names in examples (Sofía) are the only accepted exceptions: none of the catalog texts needs them
        for (m in M.entries) assertFalse(spanishOnly.containsMatchIn(m.en), "${m.name}: '${m.en}' looks Spanish")
    }

    @Test
    fun `most texts are actually translated`() {
        val same = M.entries.filter { it.en == it.es }.map { it.name }
        // Neutral texts (a list of items, a placeholder only...) may be identical; translations must be the rule
        assertTrue(same.size < M.entries.size / 10, "too many identical texts: $same")
    }

    @Test
    fun `placeholders are filled in and missing arguments become empty`() {
        assertEquals("Reminder for Ana: call", M.ACT_REMINDER_FOR.t(Lang.EN, "Ana", "call"))
        assertEquals("Recordatorio para Ana: call", M.ACT_REMINDER_FOR.t(Lang.ES, "Ana", "call"))
        assertEquals("Reminder for Ana: ", M.ACT_REMINDER_FOR.t(Lang.EN, "Ana"))
        assertEquals("today", M.TODAY.t(Lang.EN))
        assertEquals("hoy", M.TODAY.t(Lang.ES))
        // The text of the argument is never interpreted as a placeholder
        assertEquals("Reminder for {1}: x", M.ACT_REMINDER_FOR.t(Lang.EN, "{1}", "x"))
    }

    @Test
    fun `localizable arguments render in the language of the reader`() {
        val s = say(M.NOTICE_EVENT_ADDED, localized { lang -> if (lang == Lang.ES) "Valentina (es)" else "Valentina (en)" })
        assertEquals("Valentina (en) added a family event", s.text(Lang.EN))
        assertEquals("Valentina (es) agregó un evento familiar", s.text(Lang.ES))
        assertEquals("Valentina added a family event", say(M.NOTICE_EVENT_ADDED, Plain("Valentina")).text(Lang.EN))
    }

    @Test
    fun `accept-language picks Spanish only when it is the preferred language`() {
        assertEquals(Lang.EN, Lang.fromAcceptLanguage(null))
        assertEquals(Lang.EN, Lang.fromAcceptLanguage(""))
        assertEquals(Lang.EN, Lang.fromAcceptLanguage("en-US,en;q=0.9"))
        assertEquals(Lang.ES, Lang.fromAcceptLanguage("es"))
        assertEquals(Lang.ES, Lang.fromAcceptLanguage("es-CO"))
        assertEquals(Lang.ES, Lang.fromAcceptLanguage("ES-mx,es;q=0.9,en;q=0.8"))
        assertEquals(Lang.ES, Lang.fromAcceptLanguage("en;q=0.5, es;q=0.9"))
        assertEquals(Lang.EN, Lang.fromAcceptLanguage("en-US,es;q=0.8"))
        assertEquals(Lang.EN, Lang.fromAcceptLanguage("fr-FR,fr;q=0.9"))
        assertEquals(Lang.EN, Lang.fromAcceptLanguage("*"))
        assertEquals(Lang.EN, Lang.fromAcceptLanguage("es;q=0, en"))
        assertEquals(Lang.ES, Lang.fromAcceptLanguage("es, en")) // equal weight: the first listed wins
    }

    @Test
    fun `language resolution from the setting, the request and the default`() {
        assertEquals(Lang.ES, Lang.resolve("es", "en-US"))
        assertEquals(Lang.EN, Lang.resolve("en", "es-CO"))
        assertEquals(Lang.ES, Lang.resolve("auto", "es-CO"))
        assertEquals(Lang.EN, Lang.resolve("auto", "en"))
        assertEquals(Lang.EN, Lang.resolve("auto", null))
        assertEquals(Lang.ES, Lang.resolve("auto", null, Lang.ES))
        assertEquals(Lang.ES, Lang.resolve(null, null, Lang.ES))
        assertEquals(Lang.ES, Lang.resolve("es-CO", null))
        assertEquals(Lang.EN, Lang.resolve("klingon", null))
        assertEquals(Lang.ES, Lang.fromCode("ES_co"))
        assertEquals(null, Lang.fromCode("auto"))
        assertEquals(setOf("en", "es", "auto"), Lang.SETTING_VALUES)
    }

    @Test
    fun `settings service resolves the language of a user`() {
        val users = UsersRepo(Db(HikariDataSource()))
        val spanishServer = SettingsService(users, Lang.ES)
        val englishServer = SettingsService(users)
        assertEquals(Lang.ES, englishServer.langOf(UserSettings(language = "es")))
        assertEquals(Lang.EN, spanishServer.langOf(UserSettings(language = "en")))
        // auto: the request wins, then the server default
        assertEquals(Lang.ES, englishServer.langOf(UserSettings(language = "auto"), "es-CO"))
        assertEquals(Lang.ES, spanishServer.langOf(UserSettings(language = "auto")))
        assertEquals(Lang.EN, englishServer.langOf(UserSettings(language = "auto")))
        assertEquals(Lang.EN, UserSettings().language.let { Lang.fromCode(it) })
        assertNotEquals(Lang.ES, Lang.DEFAULT)
    }
}
