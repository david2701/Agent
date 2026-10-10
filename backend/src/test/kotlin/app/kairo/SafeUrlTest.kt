package app.kairo

import app.kairo.util.SafeUrl
import app.kairo.util.SafeUrl.Kind
import app.kairo.util.SafeUrl.Policy
import app.kairo.util.logSafe
import app.kairo.util.readCapped
import app.kairo.util.safeGet
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeUrlTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s) // sólo literales: no usa DNS

    /** Resolución falsa para nombres de prueba (los literales IP se resuelven sin DNS). */
    private fun resolver(table: Map<String, List<String>>): (String) -> List<InetAddress> = { host ->
        table[host]?.map(::ip) ?: if (host.any { it.isLetter() } && !host.contains(':')) throw java.net.UnknownHostException(host) else listOf(ip(host))
    }

    private val noDns = resolver(emptyMap())

    private fun blocked(url: String, policy: Policy = Policy(), dns: (String) -> List<InetAddress> = noDns) {
        assertFailsWith<SafeUrl.UnsafeUrlException>("debía rechazar $url") { SafeUrl.checkBlocking(url, policy, dns) }
    }

    private fun allowed(url: String, policy: Policy = Policy(), dns: (String) -> List<InetAddress> = noDns) {
        SafeUrl.checkBlocking(url, policy, dns)
    }

    @Test
    fun `clasifica IPv4 especiales`() {
        mapOf(
            "8.8.8.8" to Kind.PUBLIC, "1.1.1.1" to Kind.PUBLIC, "172.32.0.1" to Kind.PUBLIC, "100.128.0.1" to Kind.PUBLIC,
            "100.63.255.255" to Kind.PUBLIC, "192.169.0.1" to Kind.PUBLIC, "223.255.255.254" to Kind.PUBLIC,
            "10.0.0.1" to Kind.PRIVATE, "172.16.0.1" to Kind.PRIVATE, "172.31.255.255" to Kind.PRIVATE, "192.168.1.1" to Kind.PRIVATE,
            "100.64.0.1" to Kind.PRIVATE, "100.127.255.255" to Kind.PRIVATE, "198.18.0.1" to Kind.PRIVATE,
            "127.0.0.1" to Kind.LOOPBACK, "127.255.255.254" to Kind.LOOPBACK,
            "0.0.0.0" to Kind.FORBIDDEN, "0.1.2.3" to Kind.FORBIDDEN, "169.254.169.254" to Kind.FORBIDDEN,
            "224.0.0.1" to Kind.FORBIDDEN, "239.255.255.250" to Kind.FORBIDDEN, "240.0.0.1" to Kind.FORBIDDEN,
            "255.255.255.255" to Kind.FORBIDDEN, "192.0.0.8" to Kind.FORBIDDEN, "192.0.2.1" to Kind.FORBIDDEN,
            "198.51.100.1" to Kind.FORBIDDEN, "203.0.113.1" to Kind.FORBIDDEN,
        ).forEach { (a, kind) -> assertEquals(kind, SafeUrl.classify(ip(a)), a) }
    }

    @Test
    fun `clasifica IPv6 especiales y formas que esconden IPv4`() {
        mapOf(
            "2606:4700:4700::1111" to Kind.PUBLIC, "2001:4860:4860::8888" to Kind.PUBLIC,
            "64:ff9b::808:808" to Kind.PUBLIC, // NAT64 de 8.8.8.8
            "::1" to Kind.LOOPBACK,
            "::ffff:127.0.0.1" to Kind.LOOPBACK, "::ffff:10.0.0.1" to Kind.PRIVATE, "::ffff:169.254.169.254" to Kind.FORBIDDEN,
            "::ffff:0:7f00:1" to Kind.LOOPBACK, // IPv4 traducida
            "64:ff9b::7f00:1" to Kind.LOOPBACK, "64:ff9b::a00:1" to Kind.PRIVATE, "64:ff9b::a9fe:a9fe" to Kind.FORBIDDEN,
            "64:ff9b:1::1" to Kind.PRIVATE,
            "2002:7f00:1::" to Kind.LOOPBACK, "2002:c0a8:101::1" to Kind.PRIVATE, "2002:808:808::1" to Kind.PUBLIC, // 6to4
            "fc00::1" to Kind.PRIVATE, "fd12:3456::1" to Kind.PRIVATE, "fec0::1" to Kind.PRIVATE,
            "::" to Kind.FORBIDDEN, "::127.0.0.1" to Kind.FORBIDDEN, "fe80::1" to Kind.FORBIDDEN, "febf::1" to Kind.FORBIDDEN,
            "ff02::1" to Kind.FORBIDDEN, "2001:db8::1" to Kind.FORBIDDEN, "2001::1" to Kind.FORBIDDEN, "100::1" to Kind.FORBIDDEN,
            "4000::1" to Kind.FORBIDDEN,
        ).forEach { (a, kind) -> assertEquals(kind, SafeUrl.classify(ip(a)), a) }
    }

    @Test
    fun `rechaza direcciones privadas, locales y reservadas en la URL`() {
        listOf(
            "http://127.0.0.1/", "http://127.0.0.1:5432/", "https://10.1.2.3/", "http://172.20.0.2:8080/", "http://192.168.0.1/",
            "http://100.64.1.1/", "http://169.254.169.254/latest/meta-data/", "http://0.0.0.0:8080/", "http://255.255.255.255/",
            "http://224.0.0.1/", "http://[::1]/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:7f00:1]/", "http://[64:ff9b::7f00:1]/",
            "http://[fe80::1]/", "http://[fc00::1]/", "http://[fd00::abcd]:8123/", "http://[::]/", "http://[2002:7f00:1::]/",
        ).forEach { blocked(it) }
    }

    @Test
    fun `acepta direcciones publicas`() {
        listOf(
            "https://8.8.8.8/", "http://1.1.1.1:8080/ruta?q=1", "https://[2606:4700:4700::1111]/", "http://[64:ff9b::808:808]/",
        ).forEach { allowed(it) }
        val dns = resolver(mapOf("ejemplo.com" to listOf("93.184.215.14", "2606:2800:21f:cb07:6820:80da:af6b:8b2c")))
        allowed("https://ejemplo.com/articulo", dns = dns)
        allowed("https://EJEMPLO.com./articulo", dns = dns)
    }

    @Test
    fun `rechaza trucos de IP en decimal, octal y hexadecimal`() {
        listOf(
            "http://2130706433/", "http://0x7f.1/", "http://0x7f000001/", "http://0177.0.0.1/", "http://017700000001/",
            "http://127.1/", "http://127.0.1/", "http://0x7f.0x0.0x0.0x1/", "http://00000127.0.0.1/", "http://127.000.000.001/",
            "http://1.2.3.4.5/", "http://ejemplo.0x7f/", "http://3232235777/",
        ).forEach { blocked(it) }
    }

    @Test
    fun `rechaza usuario en la URL y esquemas que no son http`() {
        listOf(
            "http://good.com@127.0.0.1/", "http://user:pass@8.8.8.8/", "http://good.com:80@169.254.169.254/",
            "ftp://8.8.8.8/", "file:///etc/passwd", "gopher://8.8.8.8:70/", "javascript:alert(1)", "dict://8.8.8.8:11211/",
            "//8.8.8.8/", "8.8.8.8", "", "   ", "http:///ruta", "http://8.8.8.8:99999/", "http://8.8.8.8:0/",
            "http://ejemplo.com\\@127.0.0.1/", "http://8.8.8.8/ a", "http://8.8.8.8/\r\nHost: x", "http://ejemplo..com/",
            "http://exa%6dple.com/", "http://[::1%25eth0]/",
        ).forEach { blocked(it) }
    }

    @Test
    fun `el fragmento o la consulta no cambian el host`() {
        // El host real es 127.0.0.1 para Ktor y para java.net.URI
        blocked("http://127.0.0.1#@ejemplo.com/")
        blocked("http://127.0.0.1?@ejemplo.com/")
        blocked("http://127.0.0.1/@ejemplo.com")
    }

    @Test
    fun `nombres internos de docker y localhost siempre bloqueados`() {
        val dns = resolver(
            mapOf(
                "db" to listOf("172.18.0.2"), "kairo" to listOf("172.18.0.3"), "ntfy" to listOf("172.18.0.4"),
                "localhost" to listOf("127.0.0.1"), "backup" to listOf("172.18.0.5"), "foo.localhost" to listOf("127.0.0.1"),
                "host.docker.internal" to listOf("172.17.0.1"), "metadata.google.internal" to listOf("169.254.169.254"),
            ),
        )
        val private = Policy(allowPrivate = true)
        listOf(
            "http://db:5432/", "http://kairo:8080/api", "http://ntfy/", "http://localhost:8080/", "http://LOCALHOST./",
            "http://backup/", "http://foo.localhost/", "http://host.docker.internal/", "http://metadata.google.internal/",
        ).forEach {
            blocked(it, dns = dns)
            blocked(it, private, dns)
        }
        // Sólo si el operador lo permite explícitamente
        allowed("http://ntfy/", Policy(trustedHosts = setOf("ntfy")), dns)
        allowed("http://localhost:8123/", Policy(trustedHosts = setOf("localhost")), dns)
    }

    @Test
    fun `todas las direcciones del nombre deben ser publicas`() {
        val dns = resolver(
            mapOf(
                "mixto.com" to listOf("93.184.215.14", "10.0.0.5"),
                "rebind.com" to listOf("127.0.0.1"),
                "v6local.com" to listOf("2606:4700::1", "::1"),
                "mapeado.com" to listOf("::ffff:192.168.1.1"),
            ),
        )
        listOf("http://mixto.com/", "http://rebind.com/", "http://v6local.com/", "http://mapeado.com/", "http://noexiste.com/").forEach { blocked(it, dns = dns) }
    }

    @Test
    fun `allowPrivate acepta la LAN pero nunca loopback ni enlace local`() {
        val private = Policy(allowPrivate = true)
        val dns = resolver(mapOf("homeassistant" to listOf("192.168.1.20"), "ha.casa.lan" to listOf("192.168.1.20", "fd00::20")))
        listOf(
            "http://192.168.1.20:8123/api/mcp", "http://10.0.0.2/", "http://100.100.1.1/", "http://[fd00::1]:8123/",
            "http://homeassistant:8123/", "http://ha.casa.lan:8123/",
        ).forEach { allowed(it, private, dns) }
        listOf("http://127.0.0.1:8123/", "http://[::1]/", "http://169.254.169.254/", "http://[fe80::1]/", "http://0.0.0.0/", "http://2130706433/")
            .forEach { blocked(it, private, dns) }
        // Sin allowPrivate, ni el nombre de una sola etiqueta ni su IP
        blocked("http://homeassistant:8123/", dns = dns)
        blocked("http://ha.casa.lan:8123/", dns = dns)
    }

    @Test
    fun `los hosts de confianza no se resuelven`() {
        val policy = Policy(trustedHosts = setOf("ntfy.casa.lan", "NTFY.example.com."))
        val explode: (String) -> List<InetAddress> = { error("no debía resolver $it") }
        val checked = SafeUrl.checkBlocking("https://ntfy.casa.lan/upAbc?up=1", policy, explode)
        assertTrue(checked.trusted)
        SafeUrl.checkBlocking("https://ntfy.example.com/upAbc", policy, explode)
        // Otro host, aunque sea un subdominio, no es de confianza
        blocked("https://x.ntfy.casa.lan/", policy, resolver(mapOf("x.ntfy.casa.lan" to listOf("10.0.0.9"))))
    }

    @Test
    fun `readCapped corta sin leer todo`() = runBlocking {
        val data = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val (cut, truncated) = ByteReadChannel(data).readCapped(512 * 1024)
        assertEquals(512 * 1024, cut.size)
        assertTrue(truncated)
        assertTrue(cut.contentEquals(data.copyOf(512 * 1024)))
        val (all, t2) = ByteReadChannel(data.copyOf(1000)).readCapped(1000)
        assertEquals(1000, all.size)
        assertFalse(t2)
        val (empty, t3) = ByteReadChannel(ByteArray(0)).readCapped(10)
        assertEquals(0, empty.size)
        assertFalse(t3)
    }

    @Test
    fun `logSafe escapa saltos de linea`() {
        assertEquals("a\\r\\nINFO falso", logSafe("a\r\nINFO falso"))
        assertEquals("x\\u0000y", logSafe("x\u0000y"))
        assertEquals("abc…", logSafe("abcdef", 3))
        assertEquals("null", logSafe(null))
    }

    // ---------------------------------------------------------------- Redirecciones con un servidor local real

    private lateinit var server: HttpServer
    private val hits = AtomicInteger()
    private var port = 0

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        port = server.address.port
        fun redirect(path: String, location: () -> String) = server.createContext(path) { ex ->
            hits.incrementAndGet()
            ex.responseHeaders.add("Location", location())
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/ok") { ex ->
            hits.incrementAndGet()
            val body = "<title>Hola</title>".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/big") { ex ->
            hits.incrementAndGet()
            ex.sendResponseHeaders(200, 0) // chunked, sin longitud
            runCatching { ex.responseBody.use { out -> val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }; repeat(64) { out.write(chunk) } } }
        }
        redirect("/a-localhost") { "http://localhost:$port/ok" }
        redirect("/a-metadatos") { "http://169.254.169.254/latest/meta-data/" }
        redirect("/a-ipv6") { "http://[::1]:$port/ok" }
        redirect("/a-decimal") { "http://2130706433:$port/ok" }
        redirect("/a-relativa") { "/ok" }
        redirect("/a-ftp") { "ftp://8.8.8.8/" }
        redirect("/bucle") { "/bucle" }
        server.start()
    }

    @AfterTest
    fun stopServer() = server.stop(0)

    private fun client() = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 5_000 }
        followRedirects = false
        expectSuccess = false
    }

    /** El servidor de pruebas está en 127.0.0.1: sólo ese host es de confianza; cada salto se valida. */
    private val local = Policy(trustedHosts = setOf("127.0.0.1"))

    @Test
    fun `sigue redirecciones relativas al mismo host permitido`() = runBlocking {
        client().use { http ->
            val r = http.safeGet("http://127.0.0.1:$port/a-relativa", local, maxBytes = 1024)
            assertEquals(200, r.status)
            assertEquals("<title>Hola</title>", r.body.decodeToString())
            assertEquals("/ok", r.url.encodedPath)
        }
    }

    @Test
    fun `no sigue redirecciones a hosts privados`() = runBlocking {
        client().use { http ->
            for (path in listOf("/a-localhost", "/a-metadatos", "/a-ipv6", "/a-decimal", "/a-ftp")) {
                hits.set(0)
                assertFailsWith<SafeUrl.UnsafeUrlException>(path) { http.safeGet("http://127.0.0.1:$port$path", local, maxBytes = 1024) }
                assertEquals(1, hits.get(), "sólo la primera petición llega al servidor ($path)")
            }
        }
    }

    @Test
    fun `corta las redirecciones en bucle`() = runBlocking {
        client().use { http ->
            hits.set(0)
            val e = assertFailsWith<SafeUrl.UnsafeUrlException> { http.safeGet("http://127.0.0.1:$port/bucle", local, maxBytes = 1024, maxRedirects = 3) }
            assertEquals("Demasiadas redirecciones", e.message)
            assertEquals(4, hits.get())
        }
    }

    @Test
    fun `sin confianza explicita ni siquiera se conecta a 127_0_0_1`() = runBlocking {
        client().use { http ->
            hits.set(0)
            assertFailsWith<SafeUrl.UnsafeUrlException> { http.safeGet("http://127.0.0.1:$port/ok", Policy(), maxBytes = 1024) }
            assertFailsWith<SafeUrl.UnsafeUrlException> { http.safeGet("http://localhost:$port/ok", Policy(allowPrivate = true), maxBytes = 1024) }
            assertEquals(0, hits.get())
        }
    }

    @Test
    fun `el cuerpo se lee con limite`() = runBlocking {
        client().use { http ->
            val r = http.safeGet("http://127.0.0.1:$port/big", local, maxBytes = 512 * 1024)
            assertEquals(200, r.status)
            assertEquals(512 * 1024, r.body.size)
            assertTrue(r.truncated)
        }
    }
}
