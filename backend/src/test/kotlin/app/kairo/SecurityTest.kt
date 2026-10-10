package app.kairo

import app.kairo.auth.AuthException
import app.kairo.auth.AuthService
import app.kairo.auth.FailureWindow
import app.kairo.auth.KeyedFailureCounter
import app.kairo.auth.SessionPolicy
import app.kairo.auth.rateLimitKey
import app.kairo.config.AppConfig
import app.kairo.db.Db
import app.kairo.domain.ActivityRepo
import app.kairo.domain.EventsRepo
import app.kairo.domain.FamilyRepo
import app.kairo.domain.NoticesRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.UsersRepo
import app.kairo.integrations.ApnsService
import app.kairo.integrations.GoogleCalendarService
import app.kairo.integrations.NtfyNotifier
import app.kairo.integrations.PushService
import app.kairo.realtime.EventBus
import app.kairo.routes.isInlineSafe
import app.kairo.routes.publicError
import app.kairo.routes.publicMessage
import app.kairo.routes.sniffImageType
import app.kairo.routes.wsSessionEnd
import app.kairo.services.Notifier
import app.kairo.util.Crypto
import app.kairo.util.InvalidInput
import app.kairo.util.Lang
import app.kairo.util.M
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityTest {

    // ------------------------------------------------------------ Configuration secrets

    private val hex64 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08" // openssl rand -hex 32
    private val b64key = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })

    @Test
    fun `example, short or repeated JWT secret is rejected`() {
        assertNotNull(AppConfig.jwtSecretProblem("change-this-to-64-hex-characters"))
        assertNotNull(AppConfig.jwtSecretProblem("cambia-esto-por-64-caracteres-hex")) // the former Spanish example
        assertNotNull(AppConfig.jwtSecretProblem("a-secret-of-thirty-three-chars-xx"))
        assertNotNull(AppConfig.jwtSecretProblem(hex64.take(63)))
        assertNotNull(AppConfig.jwtSecretProblem("a".repeat(80)))
        assertNotNull(AppConfig.jwtSecretProblem("0".repeat(64)))
        assertTrue(AppConfig.jwtSecretProblem("change-this-to-64-hex-characters")!!.contains("example value"))
    }

    @Test
    fun `random 32-byte JWT secret is accepted`() {
        assertNull(AppConfig.jwtSecretProblem(hex64))
        assertNull(AppConfig.jwtSecretProblem(hex64.uppercase()))
        assertNull(AppConfig.jwtSecretProblem(Crypto.randomToken(32))) // base64url of 32 bytes: 43 characters
        assertNull(AppConfig.jwtSecretProblem(Base64.getEncoder().encodeToString(Crypto.randomToken(24).toByteArray()))) // with = padding
        // What scripts/generate-env.sh produces (openssl rand -hex 32), any value
        repeat(20) {
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            assertNull(AppConfig.jwtSecretProblem(bytes.joinToString("") { b -> "%02x".format(b) }))
        }
    }

    @Test
    fun `example encryption key and owner password are rejected`() {
        val e = assertFailsWith<IllegalStateException> { AppConfig.encryptionKeyFrom("change-this-to-32-bytes-in-base64=") }
        assertTrue(e.message!!.contains("example value"))
        assertFailsWith<IllegalStateException> { AppConfig.encryptionKeyFrom("cambia-esto-por-32-bytes-en-base64=") }
        assertFailsWith<IllegalStateException> { AppConfig.encryptionKeyFrom("not base64!") }
        assertFailsWith<IllegalStateException> { AppConfig.encryptionKeyFrom(Base64.getEncoder().encodeToString(ByteArray(16))) }
        assertEquals(32, AppConfig.encryptionKeyFrom(b64key).size)

        assertNotNull(AppConfig.ownerPasswordProblem("a-long-password-here"))
        assertNotNull(AppConfig.ownerPasswordProblem("una-contraseña-larga"))
        assertNull(AppConfig.ownerPasswordProblem("my-own-different-password"))
        assertNull(AppConfig.ownerPasswordProblem(null))
    }

    @Test
    fun `fromEnv does not start with the env example values`() {
        val base = mapOf(
            "KAIRO_ENCRYPTION_KEY" to b64key, "KAIRO_JWT_SECRET" to hex64,
            "KAIRO_DB_PASSWORD" to "x", "NAN_API_KEY" to "x",
        )
        assertEquals(hex64, AppConfig.fromEnv(base).jwtSecret)
        val jwt = assertFailsWith<IllegalStateException> { AppConfig.fromEnv(base + ("KAIRO_JWT_SECRET" to "change-this-to-64-hex-characters")) }
        assertTrue(jwt.message!!.startsWith("KAIRO_JWT_SECRET"))
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(base + ("KAIRO_ENCRYPTION_KEY" to "change-this-to-32-bytes-in-base64=")) }
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(base + ("KAIRO_OWNER_PASSWORD" to "a-long-password-here")) }
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(base + ("KAIRO_OWNER_PASSWORD" to "una-contraseña-larga")) }
    }

    @Test
    fun `default language comes from KAIRO_DEFAULT_LANGUAGE and is English otherwise`() {
        val base = mapOf(
            "KAIRO_ENCRYPTION_KEY" to b64key, "KAIRO_JWT_SECRET" to hex64,
            "KAIRO_DB_PASSWORD" to "x", "NAN_API_KEY" to "x",
        )
        val en = AppConfig.fromEnv(base)
        assertEquals(Lang.EN, en.defaultLanguage)
        assertEquals(M.DEFAULT_OWNER_NAME.en, en.ownerName)
        val es = AppConfig.fromEnv(base + ("KAIRO_DEFAULT_LANGUAGE" to "es"))
        assertEquals(Lang.ES, es.defaultLanguage)
        assertEquals(M.DEFAULT_OWNER_NAME.es, es.ownerName)
        assertEquals(Lang.ES, AppConfig.fromEnv(base + ("KAIRO_DEFAULT_LANGUAGE" to "es-CO")).defaultLanguage)
        assertEquals("Ana", AppConfig.fromEnv(base + ("KAIRO_OWNER_NAME" to "Ana")).ownerName)
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(base + ("KAIRO_DEFAULT_LANGUAGE" to "fr")) }
    }

    // ------------------------------------------------------------ Per-IP limit

    @Test
    fun `IPv4 as is and IPv6 grouped by 64 prefix`() {
        assertEquals("203.0.113.7", rateLimitKey("203.0.113.7"))
        assertEquals("2001:db8:aaaa:1::/64", rateLimitKey("2001:db8:aaaa:1::1"))
        assertEquals("2001:db8:aaaa:1::/64", rateLimitKey("2001:0db8:aaaa:0001:ffff:eeee:dddd:cccc"))
        assertEquals("2001:db8:aaaa:1::/64", rateLimitKey("[2001:DB8:AAAA:1:abcd::9]"))
        assertEquals("2001:db8:aaaa:1::/64", rateLimitKey("fe80::1%eth0".replace("fe80::1", "2001:db8:aaaa:1::2")))
        assertNotEquals(rateLimitKey("2001:db8:aaaa:1::1"), rateLimitKey("2001:db8:aaaa:2::1"))
        assertEquals("0:0:0:0::/64", rateLimitKey("::1"))
        // IPv4 mapped in IPv6 = the same IPv4
        assertEquals("203.0.113.7", rateLimitKey("::ffff:203.0.113.7"))
        assertEquals("203.0.113.7", rateLimitKey("::ffff:cb00:7107"))
        assertEquals("64:ff9b:0:0::/64", rateLimitKey("64:ff9b::198.51.100.1"))
        // Not an IP: used as is (names are not resolved)
        assertEquals("unknown", rateLimitKey("unknown"))
        assertEquals("1:2:3", rateLimitKey("1:2:3"))
        assertEquals("1::2::3", rateLimitKey("1::2::3"))
        assertEquals("::ffff:999.1.1.1", rateLimitKey("::ffff:999.1.1.1"))
    }

    // ------------------------------------------------------------ Failure counters

    @Test
    fun `failure window with limit and expiry`() {
        val w = FailureWindow(limit = 20, windowMs = 300_000)
        val t0 = 1_000_000L
        repeat(19) { assertEquals(it + 1, w.record(t0 + it)) }
        assertFalse(w.reached(t0 + 19))
        assertEquals(20, w.record(t0 + 19))
        assertTrue(w.reached(t0 + 20))
        // It never keeps more than the limit
        assertEquals(20, w.record(t0 + 21))
        // 5 minutes after the first of the remaining ones, it is no longer at the limit
        assertFalse(w.reached(t0 + 21 + 300_000))
        assertEquals(1, w.record(t0 + 1_000_000))
    }

    @Test
    fun `email lockout after 10 failures in 15 minutes`() {
        val c = KeyedFailureCounter(limit = 10, windowMs = 15 * 60_000, maxKeys = 3)
        val t0 = 5_000_000L
        repeat(9) { c.record("ana@example.com", t0 + it * 1000) }
        assertFalse(c.blocked("ana@example.com", t0 + 9_000))
        assertEquals(10, c.record("ana@example.com", t0 + 9_000))
        assertTrue(c.blocked("ana@example.com", t0 + 10_000))
        assertFalse(c.blocked("other@example.com", t0 + 10_000))
        assertFalse(c.blocked("ana@example.com", t0 + 15 * 60_000 + 1_000))
        c.reset("ana@example.com")
        assertFalse(c.blocked("ana@example.com", t0 + 10_000))
        // Bounded size: it drops the key that has gone longest without failing
        listOf("a", "b", "c", "d").forEach { c.record(it, t0) }
        assertEquals(3, c.size())
        assertFalse(c.blocked("a", t0))
    }

    private fun testConfig() = AppConfig(
        port = 0, publicUrl = "https://kairo.example.com", dbUrl = "", dbUser = "", dbPassword = "",
        nanBaseUrl = "http://localhost", nanApiKey = "x", jwtSecret = hex64, encryptionKey = ByteArray(32) { 1 },
        ownerEmail = null, ownerPassword = null, ownerName = "You", defaultTimezone = "America/Bogota",
        googleClientId = "client", googleClientSecret = "secret", ntfyUrl = null, ntfyTopic = null, ntfyToken = null,
        corsOrigins = emptyList(), schedulersEnabled = false,
    )

    /** No database: HikariDataSource does not connect until the first query. */
    private val offlineDb = Db(HikariDataSource())

    @Test
    fun `pairing codes one per user and voided after 20 failures`() = runBlocking {
        val auth = AuthService(testConfig(), UsersRepo(offlineDb), FamilyRepo(offlineDb))
        val (u1, u2, u3) = List(3) { UUID.randomUUID() }
        val first = auth.createPairCode(u1)
        val others = setOf(auth.createPairCode(u2), auth.createPairCode(u3))
        assertEquals(3, auth.livePairCodes())
        // Asking for another replaces the previous one of the same user
        var second = auth.createPairCode(u1)
        while (second == first) second = auth.createPairCode(u1)
        assertEquals(3, auth.livePairCodes())
        val live = others + second
        val wrong = (0..999_999).asSequence().map { "%06d".format(it) }.filter { it !in live && it != first }
        // The previous code is no longer valid (and counts as a failure)
        assertFailsWith<AuthException> { auth.completePairing(first, "", "wear") }
        val guesses = wrong.iterator()
        repeat(AuthService.PAIR_MAX_FAILURES - 2) {
            assertFailsWith<AuthException> { auth.completePairing(guesses.next(), "", "wear") }
        }
        assertEquals(3, auth.livePairCodes())
        // The 20th failure voids every valid code
        assertFailsWith<AuthException> { auth.completePairing(guesses.next(), "", "wear") }
        assertEquals(0, auth.livePairCodes())
        // The good code no longer works
        assertFailsWith<AuthException> { auth.completePairing(second, "", "wear") }
    }

    // ------------------------------------------------------------ Sessions

    @Test
    fun `session idle for 90 days and refresh token reuse`() {
        val now = Instant.parse("2026-10-10T12:00:00Z")
        assertFalse(SessionPolicy.idleExpired(now.minus(Duration.ofDays(89)), now))
        assertFalse(SessionPolicy.idleExpired(now.minus(Duration.ofDays(90)), now))
        assertTrue(SessionPolicy.idleExpired(now.minus(Duration.ofDays(90)).minusSeconds(1), now))
        assertTrue(SessionPolicy.idleExpired(now.minus(Duration.ofDays(400)), now))

        assertFalse(SessionPolicy.reuseIsSuspicious(now.minusSeconds(30), now, AuthService.REFRESH_GRACE_MS))
        assertTrue(SessionPolicy.reuseIsSuspicious(now.minusSeconds(61), now, AuthService.REFRESH_GRACE_MS))
        assertTrue(SessionPolicy.reuseIsSuspicious(null, now, AuthService.REFRESH_GRACE_MS))
    }

    @Test
    fun `websocket closes when the token expires or the device is revoked`() {
        assertNull(wsSessionEnd(1_000, 2_000, deviceActive = true))
        assertEquals("Session expired", wsSessionEnd(2_000, 2_000, deviceActive = true))
        assertEquals("Session closed", wsSessionEnd(1_000, 2_000, deviceActive = false))
    }

    // ------------------------------------------------------------ Uploaded files

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `image type from its bytes`() {
        assertEquals("image/jpeg", sniffImageType(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals("image/png", sniffImageType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals("image/gif", sniffImageType(ascii("GIF89a....")))
        assertEquals("image/webp", sniffImageType(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals("image/heic", sniffImageType(ascii("\u0000\u0000\u0000\u0018ftypheic\u0000\u0000\u0000\u0000")))
        assertEquals("image/heif", sniffImageType(ascii("\u0000\u0000\u0000\u0018ftypmif1\u0000\u0000\u0000\u0000")))
        // What is not a raster image
        assertNull(sniffImageType(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")))
        assertNull(sniffImageType(ascii("<!doctype html><html>")))
        assertNull(sniffImageType(ascii("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")))
        assertNull(sniffImageType(ascii("\u0000\u0000\u0000\u0018ftypM4A \u0000\u0000\u0000\u0000")))
        assertNull(sniffImageType(ascii("%PDF-1.7")))
        assertNull(sniffImageType(ByteArray(0)))
        assertNull(sniffImageType(bytes(0xFF, 0xD8)))
    }

    @Test
    fun `only raster images and audio are served inline`() {
        assertTrue(isInlineSafe(ContentType.parse("image/png")))
        assertTrue(isInlineSafe(ContentType.parse("image/JPEG")))
        assertTrue(isInlineSafe(ContentType.parse("audio/mpeg")))
        assertFalse(isInlineSafe(ContentType.parse("image/svg+xml")))
        assertFalse(isInlineSafe(ContentType.parse("text/html; charset=utf-8")))
        assertFalse(isInlineSafe(ContentType.parse("application/pdf")))
        assertFalse(isInlineSafe(ContentType.parse("application/octet-stream")))
    }

    // ------------------------------------------------------------ Error messages

    @Test
    fun `own validation messages are shown and library ones are not`() {
        val own = runCatching { require(false) { "The reminder needs a date" } }.exceptionOrNull() as IllegalArgumentException
        assertEquals("The reminder needs a date", publicMessage(own))
        val uuid = runCatching { UUID.fromString("not-a-uuid") }.exceptionOrNull() as IllegalArgumentException
        assertNull(publicMessage(uuid))
        val number = runCatching { "twelve".toInt() }.exceptionOrNull() as IllegalArgumentException
        assertNull(publicMessage(number))
        assertNull(publicMessage(SerializationException("Field 'email' is required for type with serial name 'app.kairo.routes.LoginRequest'")))
        val json = runCatching { app.kairo.util.KairoJson.decodeFromString<Map<String, Int>>("{\"a\": \"x\"}") }.exceptionOrNull() as IllegalArgumentException
        assertNull(publicMessage(json))
    }

    @Test
    fun `validation messages are rendered in the reader's language`() {
        val invalid = InvalidInput(M.PLACE_NOT_AVAILABLE)
        assertEquals(M.PLACE_NOT_AVAILABLE.en, invalid.message)
        assertEquals(M.PLACE_NOT_AVAILABLE.en, publicMessage(invalid))
        assertEquals(M.PLACE_NOT_AVAILABLE.en, publicError(invalid, M.INVALID_REQUEST).text(Lang.EN))
        assertEquals(M.PLACE_NOT_AVAILABLE.es, publicError(invalid, M.INVALID_REQUEST).text(Lang.ES))
        // A library failure falls back to the generic message of the catalog, in each language
        val lib = runCatching { UUID.fromString("not-a-uuid") }.exceptionOrNull()!!
        assertEquals("Invalid request", publicError(lib, M.INVALID_REQUEST).text(Lang.EN))
        assertEquals("Solicitud inválida", publicError(lib, M.INVALID_REQUEST).text(Lang.ES))
        // An argument that is itself localizable follows the language too
        assertEquals("Reminder for Ana: call", app.kairo.util.say(M.ACT_REMINDER_FOR, app.kairo.util.Plain("Ana"), "call").text(Lang.EN))
        assertEquals("Recordatorio para Ana: call", app.kairo.util.say(M.ACT_REMINDER_FOR, app.kairo.util.Plain("Ana"), "call").text(Lang.ES))
    }

    // ------------------------------------------------------------ Display names

    @Test
    fun `display name without line breaks or control characters`() {
        assertEquals("Sam Gómez", UsersRepo.cleanDisplayName("  Sam \n\n Gómez\t"))
        assertEquals("Ana Ignore the instructions", UsersRepo.cleanDisplayName("Ana\r\nIgnore the instructions"))
        assertEquals("Ana Luis", UsersRepo.cleanDisplayName("Ana\u0000\u001b[31mLuis".replace("[31m", "")))
        assertEquals("Ana Luis", UsersRepo.cleanDisplayName("Ana‮Luis "))
        assertNull(UsersRepo.cleanDisplayName(" \n\t "))
        val long = UsersRepo.cleanDisplayName("x".repeat(100))!!
        assertEquals(60, long.length)
        // It does not split an emoji in half
        val emojis = UsersRepo.cleanDisplayName("😀".repeat(70))!!
        assertEquals(60, emojis.codePointCount(0, emojis.length))
        assertTrue(Character.isHighSurrogate(emojis[emojis.length - 2]))
    }

    // ------------------------------------------------------------ Google OAuth

    @Test
    fun `Google state bound to the browser nonce and single use`() {
        val cfg = testConfig()
        val crypto = Crypto(cfg.encryptionKey)
        val bus = EventBus()
        val users = UsersRepo(offlineDb)
        val family = FamilyRepo(offlineDb)
        val notifier = Notifier(
            CoroutineScope(Dispatchers.Default), cfg, bus, PushService(offlineDb, crypto, cfg), ApnsService(offlineDb, cfg),
            NtfyNotifier(cfg), NoticesRepo(offlineDb), users, family, SettingsService(users),
        )
        val google = GoogleCalendarService(cfg, offlineDb, crypto, EventsRepo(offlineDb, ActivityRepo(offlineDb, bus, family), notifier), bus)
        val user = UUID.randomUUID()
        val nonce = Crypto.randomToken(24)
        val state = Url(google.authUrl(user, nonce)).parameters["state"]!!
        assertFalse(state.contains(nonce), "the nonce does not travel in clear")
        assertNull(google.verifyState(state, null))
        assertNull(google.verifyState(state, Crypto.randomToken(24)))
        assertEquals(user, google.verifyState(state, nonce))
        // Single use
        assertNull(google.verifyState(state, nonce))
        // Signed: the user cannot be changed
        val other = google.authUrl(user, nonce).let { Url(it).parameters["state"]!! }
        val forged = UUID.randomUUID().toString() + other.substring(other.indexOf('.'))
        assertNull(google.verifyState(forged, nonce))
    }
}
