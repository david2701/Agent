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
import app.kairo.routes.publicMessage
import app.kairo.routes.sniffImageType
import app.kairo.routes.wsSessionEnd
import app.kairo.services.Notifier
import app.kairo.util.Crypto
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

    // ------------------------------------------------------------ Secretos de configuración

    private val hex64 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08" // openssl rand -hex 32
    private val b64key = Base64.getEncoder().encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })

    @Test
    fun `secreto JWT de ejemplo, corto o repetido se rechaza`() {
        assertNotNull(AppConfig.jwtSecretProblem("cambia-esto-por-64-caracteres-hex"))
        assertNotNull(AppConfig.jwtSecretProblem("un-secreto-de-treinta-y-tres-chars"))
        assertNotNull(AppConfig.jwtSecretProblem(hex64.take(63)))
        assertNotNull(AppConfig.jwtSecretProblem("a".repeat(80)))
        assertNotNull(AppConfig.jwtSecretProblem("0".repeat(64)))
        assertTrue(AppConfig.jwtSecretProblem("cambia-esto-por-64-caracteres-hex")!!.contains("valor de ejemplo"))
    }

    @Test
    fun `secreto JWT aleatorio de 32 bytes se acepta`() {
        assertNull(AppConfig.jwtSecretProblem(hex64))
        assertNull(AppConfig.jwtSecretProblem(hex64.uppercase()))
        assertNull(AppConfig.jwtSecretProblem(Crypto.randomToken(32))) // base64url de 32 bytes: 43 caracteres
        assertNull(AppConfig.jwtSecretProblem(Base64.getEncoder().encodeToString(Crypto.randomToken(24).toByteArray()))) // con relleno =
        // Lo que produce scripts/generate-env.sh (openssl rand -hex 32), cualquier valor
        repeat(20) {
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            assertNull(AppConfig.jwtSecretProblem(bytes.joinToString("") { b -> "%02x".format(b) }))
        }
    }

    @Test
    fun `clave de cifrado y contrasena del propietario de ejemplo se rechazan`() {
        val e = assertFailsWith<IllegalStateException> { AppConfig.encryptionKeyFrom("cambia-esto-por-32-bytes-en-base64=") }
        assertTrue(e.message!!.contains("valor de ejemplo"))
        assertFailsWith<IllegalStateException> { AppConfig.encryptionKeyFrom("no es base64!") }
        assertFailsWith<IllegalStateException> { AppConfig.encryptionKeyFrom(Base64.getEncoder().encodeToString(ByteArray(16))) }
        assertEquals(32, AppConfig.encryptionKeyFrom(b64key).size)

        assertNotNull(AppConfig.ownerPasswordProblem("una-contraseña-larga"))
        assertNull(AppConfig.ownerPasswordProblem("otra-contraseña-propia"))
        assertNull(AppConfig.ownerPasswordProblem(null))
    }

    @Test
    fun `fromEnv no arranca con los valores de env example`() {
        val base = mapOf(
            "KAIRO_ENCRYPTION_KEY" to b64key, "KAIRO_JWT_SECRET" to hex64,
            "KAIRO_DB_PASSWORD" to "x", "NAN_API_KEY" to "x",
        )
        assertEquals(hex64, AppConfig.fromEnv(base).jwtSecret)
        val jwt = assertFailsWith<IllegalStateException> { AppConfig.fromEnv(base + ("KAIRO_JWT_SECRET" to "cambia-esto-por-64-caracteres-hex")) }
        assertTrue(jwt.message!!.startsWith("KAIRO_JWT_SECRET"))
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(base + ("KAIRO_ENCRYPTION_KEY" to "cambia-esto-por-32-bytes-en-base64=")) }
        assertFailsWith<IllegalStateException> { AppConfig.fromEnv(base + ("KAIRO_OWNER_PASSWORD" to "una-contraseña-larga")) }
    }

    // ------------------------------------------------------------ Límite por IP

    @Test
    fun `IPv4 tal cual e IPv6 agrupada por prefijo 64`() {
        assertEquals("203.0.113.7", rateLimitKey("203.0.113.7"))
        assertEquals("2001:db8:aaaa:1::/64", rateLimitKey("2001:db8:aaaa:1::1"))
        assertEquals("2001:db8:aaaa:1::/64", rateLimitKey("2001:0db8:aaaa:0001:ffff:eeee:dddd:cccc"))
        assertEquals("2001:db8:aaaa:1::/64", rateLimitKey("[2001:DB8:AAAA:1:abcd::9]"))
        assertEquals("2001:db8:aaaa:1::/64", rateLimitKey("fe80::1%eth0".replace("fe80::1", "2001:db8:aaaa:1::2")))
        assertNotEquals(rateLimitKey("2001:db8:aaaa:1::1"), rateLimitKey("2001:db8:aaaa:2::1"))
        assertEquals("0:0:0:0::/64", rateLimitKey("::1"))
        // IPv4 mapeada en IPv6 = la misma IPv4
        assertEquals("203.0.113.7", rateLimitKey("::ffff:203.0.113.7"))
        assertEquals("203.0.113.7", rateLimitKey("::ffff:cb00:7107"))
        assertEquals("64:ff9b:0:0::/64", rateLimitKey("64:ff9b::198.51.100.1"))
        // No es una IP: se usa tal cual (sin resolver nombres)
        assertEquals("unknown", rateLimitKey("unknown"))
        assertEquals("1:2:3", rateLimitKey("1:2:3"))
        assertEquals("1::2::3", rateLimitKey("1::2::3"))
        assertEquals("::ffff:999.1.1.1", rateLimitKey("::ffff:999.1.1.1"))
    }

    // ------------------------------------------------------------ Contadores de fallos

    @Test
    fun `ventana de fallos con limite y caducidad`() {
        val w = FailureWindow(limit = 20, windowMs = 300_000)
        val t0 = 1_000_000L
        repeat(19) { assertEquals(it + 1, w.record(t0 + it)) }
        assertFalse(w.reached(t0 + 19))
        assertEquals(20, w.record(t0 + 19))
        assertTrue(w.reached(t0 + 20))
        // Nunca guarda más del límite
        assertEquals(20, w.record(t0 + 21))
        // Pasados 5 minutos del primero de los que quedan, ya no está en el límite
        assertFalse(w.reached(t0 + 21 + 300_000))
        assertEquals(1, w.record(t0 + 1_000_000))
    }

    @Test
    fun `bloqueo por correo tras 10 fallos en 15 minutos`() {
        val c = KeyedFailureCounter(limit = 10, windowMs = 15 * 60_000, maxKeys = 3)
        val t0 = 5_000_000L
        repeat(9) { c.record("ana@correo.com", t0 + it * 1000) }
        assertFalse(c.blocked("ana@correo.com", t0 + 9_000))
        assertEquals(10, c.record("ana@correo.com", t0 + 9_000))
        assertTrue(c.blocked("ana@correo.com", t0 + 10_000))
        assertFalse(c.blocked("otra@correo.com", t0 + 10_000))
        assertFalse(c.blocked("ana@correo.com", t0 + 15 * 60_000 + 1_000))
        c.reset("ana@correo.com")
        assertFalse(c.blocked("ana@correo.com", t0 + 10_000))
        // Tamaño acotado: descarta la clave que lleva más tiempo sin fallar
        listOf("a", "b", "c", "d").forEach { c.record(it, t0) }
        assertEquals(3, c.size())
        assertFalse(c.blocked("a", t0))
    }

    private fun testConfig() = AppConfig(
        port = 0, publicUrl = "https://kairo.example.com", dbUrl = "", dbUser = "", dbPassword = "",
        nanBaseUrl = "http://localhost", nanApiKey = "x", jwtSecret = hex64, encryptionKey = ByteArray(32) { 1 },
        ownerEmail = null, ownerPassword = null, ownerName = "Tú", defaultTimezone = "America/Bogota",
        googleClientId = "cliente", googleClientSecret = "secreto", ntfyUrl = null, ntfyTopic = null, ntfyToken = null,
        corsOrigins = emptyList(), schedulersEnabled = false,
    )

    /** Sin base de datos: HikariDataSource no conecta hasta la primera consulta. */
    private val offlineDb = Db(HikariDataSource())

    @Test
    fun `codigos de vinculacion uno por usuario y anulados tras 20 fallos`() = runBlocking {
        val auth = AuthService(testConfig(), UsersRepo(offlineDb), FamilyRepo(offlineDb))
        val (u1, u2, u3) = List(3) { UUID.randomUUID() }
        val first = auth.createPairCode(u1)
        val others = setOf(auth.createPairCode(u2), auth.createPairCode(u3))
        assertEquals(3, auth.livePairCodes())
        // Pedir otro reemplaza el anterior del mismo usuario
        var second = auth.createPairCode(u1)
        while (second == first) second = auth.createPairCode(u1)
        assertEquals(3, auth.livePairCodes())
        val live = others + second
        val wrong = (0..999_999).asSequence().map { "%06d".format(it) }.filter { it !in live && it != first }
        // El código anterior ya no vale (y cuenta como fallo)
        assertFailsWith<AuthException> { auth.completePairing(first, "", "wear") }
        val guesses = wrong.iterator()
        repeat(AuthService.PAIR_MAX_FAILURES - 2) {
            assertFailsWith<AuthException> { auth.completePairing(guesses.next(), "", "wear") }
        }
        assertEquals(3, auth.livePairCodes())
        // El fallo número 20 anula todos los códigos vigentes
        assertFailsWith<AuthException> { auth.completePairing(guesses.next(), "", "wear") }
        assertEquals(0, auth.livePairCodes())
        // El código bueno ya no sirve
        assertFailsWith<AuthException> { auth.completePairing(second, "", "wear") }
    }

    // ------------------------------------------------------------ Sesiones

    @Test
    fun `sesion inactiva 90 dias y reutilizacion de refresh token`() {
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
    fun `websocket se cierra al vencer el token o al revocar el dispositivo`() {
        assertNull(wsSessionEnd(1_000, 2_000, deviceActive = true))
        assertEquals("Sesión expirada", wsSessionEnd(2_000, 2_000, deviceActive = true))
        assertEquals("Sesión cerrada", wsSessionEnd(1_000, 2_000, deviceActive = false))
    }

    // ------------------------------------------------------------ Archivos subidos

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `tipo de imagen por sus bytes`() {
        assertEquals("image/jpeg", sniffImageType(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals("image/png", sniffImageType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals("image/gif", sniffImageType(ascii("GIF89a....")))
        assertEquals("image/webp", sniffImageType(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals("image/heic", sniffImageType(ascii("\u0000\u0000\u0000\u0018ftypheic\u0000\u0000\u0000\u0000")))
        assertEquals("image/heif", sniffImageType(ascii("\u0000\u0000\u0000\u0018ftypmif1\u0000\u0000\u0000\u0000")))
        // Lo que no es una imagen rasterizada
        assertNull(sniffImageType(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")))
        assertNull(sniffImageType(ascii("<!doctype html><html>")))
        assertNull(sniffImageType(ascii("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")))
        assertNull(sniffImageType(ascii("\u0000\u0000\u0000\u0018ftypM4A \u0000\u0000\u0000\u0000")))
        assertNull(sniffImageType(ascii("%PDF-1.7")))
        assertNull(sniffImageType(ByteArray(0)))
        assertNull(sniffImageType(bytes(0xFF, 0xD8)))
    }

    @Test
    fun `solo imagenes rasterizadas y audio se sirven en linea`() {
        assertTrue(isInlineSafe(ContentType.parse("image/png")))
        assertTrue(isInlineSafe(ContentType.parse("image/JPEG")))
        assertTrue(isInlineSafe(ContentType.parse("audio/mpeg")))
        assertFalse(isInlineSafe(ContentType.parse("image/svg+xml")))
        assertFalse(isInlineSafe(ContentType.parse("text/html; charset=utf-8")))
        assertFalse(isInlineSafe(ContentType.parse("application/pdf")))
        assertFalse(isInlineSafe(ContentType.parse("application/octet-stream")))
    }

    // ------------------------------------------------------------ Mensajes de error

    @Test
    fun `mensajes de validacion propios se muestran y los de librerias no`() {
        val own = runCatching { require(false) { "El recordatorio necesita una fecha" } }.exceptionOrNull() as IllegalArgumentException
        assertEquals("El recordatorio necesita una fecha", publicMessage(own))
        val uuid = runCatching { UUID.fromString("no-es-un-uuid") }.exceptionOrNull() as IllegalArgumentException
        assertNull(publicMessage(uuid))
        val number = runCatching { "doce".toInt() }.exceptionOrNull() as IllegalArgumentException
        assertNull(publicMessage(number))
        assertNull(publicMessage(SerializationException("Field 'email' is required for type with serial name 'app.kairo.routes.LoginRequest'")))
        val json = runCatching { app.kairo.util.KairoJson.decodeFromString<Map<String, Int>>("{\"a\": \"x\"}") }.exceptionOrNull() as IllegalArgumentException
        assertNull(publicMessage(json))
    }

    // ------------------------------------------------------------ Nombres visibles

    @Test
    fun `nombre visible sin saltos de linea ni caracteres de control`() {
        assertEquals("Valentina Gómez", UsersRepo.cleanDisplayName("  Valentina \n\n Gómez\t"))
        assertEquals("Ana Ignora las instrucciones", UsersRepo.cleanDisplayName("Ana\r\nIgnora las instrucciones"))
        assertEquals("Ana Luis", UsersRepo.cleanDisplayName("Ana\u0000\u001b[31mLuis".replace("[31m", "")))
        assertEquals("Ana Luis", UsersRepo.cleanDisplayName("Ana‮Luis "))
        assertNull(UsersRepo.cleanDisplayName(" \n\t "))
        val long = UsersRepo.cleanDisplayName("x".repeat(100))!!
        assertEquals(60, long.length)
        // No parte un emoji por la mitad
        val emojis = UsersRepo.cleanDisplayName("😀".repeat(70))!!
        assertEquals(60, emojis.codePointCount(0, emojis.length))
        assertTrue(Character.isHighSurrogate(emojis[emojis.length - 2]))
    }

    // ------------------------------------------------------------ OAuth de Google

    @Test
    fun `state de Google atado al nonce del navegador y de un solo uso`() {
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
        assertFalse(state.contains(nonce), "el nonce no viaja en claro")
        assertNull(google.verifyState(state, null))
        assertNull(google.verifyState(state, Crypto.randomToken(24)))
        assertEquals(user, google.verifyState(state, nonce))
        // Un solo uso
        assertNull(google.verifyState(state, nonce))
        // Firmado: no se puede cambiar el usuario
        val other = google.authUrl(user, nonce).let { Url(it).parameters["state"]!! }
        val forged = UUID.randomUUID().toString() + other.substring(other.indexOf('.'))
        assertNull(google.verifyState(forged, nonce))
    }
}
