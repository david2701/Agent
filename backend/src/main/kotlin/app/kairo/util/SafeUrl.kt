package app.kairo.util

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Protección contra SSRF para las peticiones a URLs que elige un usuario (vista previa de enlaces,
 * servidores MCP, endpoints de Web Push).
 *
 * Una URL pasa si:
 * - es http o https, sin `usuario:contraseña@` y sin formas ambiguas de IP (`2130706433`, `0x7f.1`, `0177.0.0.1`, `127.1`);
 * - el host no es un nombre interno (`localhost`, los servicios de docker-compose `db`, `kairo`, `ntfy`, `backup`...);
 * - TODAS las direcciones a las que resuelve son públicas: nada de loopback, enlace local (169.254/16, fe80::/10),
 *   redes privadas (10/8, 172.16/12, 192.168/16, fc00::/7), CGNAT (100.64/10), 0.0.0.0/8, multicast, broadcast,
 *   rangos reservados o de documentación, ni sus formas IPv4 mapeadas / NAT64 / 6to4.
 *
 * Con [Policy.allowPrivate] se aceptan además las redes privadas y CGNAT (un Home Assistant en la LAN o por Tailscale),
 * pero nunca loopback, enlace local (metadatos de la nube) ni los nombres internos. Los [Policy.trustedHosts]
 * (decisión explícita del operador) se aceptan sin mirar sus direcciones.
 *
 * Riesgo residual (DNS rebinding / TOCTOU): el motor CIO vuelve a resolver el nombre al conectar y no hay forma de
 * fijarle la IP ya comprobada (TLS necesita el nombre para SNI y para validar el certificado). Lo mitiga la caché DNS
 * de la JVM (`networkaddress.cache.ttl`, 30 s por defecto): la comprobación se hace justo antes de cada petición y de
 * cada redirección, así que la conexión reutiliza la respuesta ya validada salvo que la caché caduque entre medias.
 * Con HTTPS_PROXY quien resuelve y conecta es el proxy, y la comprobación sólo cubre la resolución local.
 */
object SafeUrl {
    /** Clase de una dirección IP. */
    enum class Kind { PUBLIC, PRIVATE, LOOPBACK, FORBIDDEN }

    data class Policy(
        /** Acepta redes privadas (RFC 1918, fc00::/7) y CGNAT. Nunca loopback, enlace local ni nombres internos. */
        val allowPrivate: Boolean = false,
        /** Hosts exactos que se aceptan sin comprobar sus direcciones (configurados por el operador). */
        val trustedHosts: Set<String> = emptySet(),
    ) {
        internal val trusted: Set<String> = trustedHosts.map { normalizeHost(it) }.filter { it.isNotEmpty() }.toSet()

        fun isTrusted(host: String): Boolean = normalizeHost(host) in trusted
    }

    /** URL rechazada. [blockedAddress] = el formato es válido pero el destino no está permitido (o no resuelve). */
    class UnsafeUrlException(message: String, val blockedAddress: Boolean = false) : IllegalArgumentException(message)

    /** URL comprobada: hay que usar [url] tal cual (no volver a interpretar el texto original). */
    data class Checked(val url: Url, val host: String, val addresses: List<InetAddress>, val trusted: Boolean)

    /** Nombres que nunca se aceptan salvo que el operador los ponga en [Policy.trustedHosts]. */
    val INTERNAL_NAMES = setOf(
        "localhost", "db", "kairo", "ntfy", "backup",
        "host.docker.internal", "gateway.docker.internal", "metadata", "metadata.google.internal", "instance-data",
    )

    const val BLOCKED_MESSAGE = "Esa dirección no está permitida (red local o privada, o no se pudo resolver)"

    /** Valida la URL y resuelve el host (en Dispatchers.IO). Lanza [UnsafeUrlException] si no se permite. */
    suspend fun check(raw: String, policy: Policy = Policy()): Checked =
        withContext(Dispatchers.IO) { checkBlocking(raw, policy) }

    /** Igual que [check], bloqueante. [resolver] sólo se cambia en pruebas. */
    fun checkBlocking(
        raw: String,
        policy: Policy = Policy(),
        resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): Checked {
        val url = parse(raw)
        val host = normalizeHost(url.host)
        if (policy.isTrusted(host)) return Checked(url, host, emptyList(), trusted = true)
        checkHostName(host, policy)
        val addresses = runCatching { resolver(host) }.getOrNull().orEmpty()
        if (addresses.isEmpty()) throw UnsafeUrlException(BLOCKED_MESSAGE, blockedAddress = true)
        if (!addresses.all { isAllowed(it, policy) }) throw UnsafeUrlException(BLOCKED_MESSAGE, blockedAddress = true)
        return Checked(url, host, addresses, trusted = false)
    }

    /**
     * Análisis sintáctico estricto. Se exige que el parser de Ktor (el que usa el cliente) y el de java.net.URI
     * coincidan en esquema, host y puerto, para que no haya dos lecturas distintas de la misma URL.
     */
    fun parse(raw: String): Url {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 4096 || s.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) throw syntax("URL inválida")
        val uri = runCatching { URI(s) }.getOrNull() ?: throw syntax("URL inválida")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw syntax("La URL debe empezar por http:// o https://")
        if (uri.rawUserInfo != null) throw syntax("La URL no puede incluir usuario ni contraseña")
        val uriHost = uri.host?.let(::normalizeHost)?.takeIf { it.isNotEmpty() } ?: throw syntax("URL sin un host válido")
        val url = runCatching { Url(s) }.getOrNull() ?: throw syntax("URL inválida")
        if (url.protocol.name.lowercase() != scheme) throw syntax("URL inválida")
        if (!url.encodedUser.isNullOrEmpty() || !url.encodedPassword.isNullOrEmpty()) throw syntax("La URL no puede incluir usuario ni contraseña")
        if (normalizeHost(url.host) != uriHost) throw syntax("URL ambigua")
        val uriPort = if (uri.port == -1) url.protocol.defaultPort else uri.port
        if (url.port != uriPort || uriPort !in 1..65535) throw syntax("URL ambigua")
        return url
    }

    /** Reglas sobre el nombre (sin DNS). */
    internal fun checkHostName(host: String, policy: Policy) {
        if (host.isEmpty() || host.contains('%')) throw syntax("URL sin un host válido")
        if (host in INTERNAL_NAMES || host.endsWith(".localhost")) throw UnsafeUrlException(BLOCKED_MESSAGE, blockedAddress = true)
        if (host.contains(':')) return // literal IPv6: se clasifica por su dirección
        val labels = host.split('.')
        if (labels.any { it.isEmpty() }) throw syntax("URL sin un host válido")
        // Cualquier etiqueta final numérica hace que algún parser lo lea como IPv4: sólo se acepta el formato canónico
        if (NUMERIC_LABEL.matches(labels.last()) && !CANONICAL_IPV4.matches(host)) throw syntax("Dirección IP ambigua")
        if (labels.size == 1 && !policy.allowPrivate) throw UnsafeUrlException(BLOCKED_MESSAGE, blockedAddress = true)
    }

    fun isAllowed(address: InetAddress, policy: Policy = Policy()): Boolean = when (classify(address)) {
        Kind.PUBLIC -> true
        Kind.PRIVATE -> policy.allowPrivate
        Kind.LOOPBACK, Kind.FORBIDDEN -> false
    }

    fun classify(address: InetAddress): Kind = when (address) {
        is Inet4Address -> classifyV4(address.address)
        is Inet6Address -> classifyV6(address.address)
        else -> Kind.FORBIDDEN
    }

    internal fun classifyV4(b: ByteArray): Kind {
        val a0 = b[0].toInt() and 0xff
        val a1 = b[1].toInt() and 0xff
        val a2 = b[2].toInt() and 0xff
        return when {
            a0 == 0 -> Kind.FORBIDDEN // 0.0.0.0/8 "esta red"
            a0 == 10 -> Kind.PRIVATE
            a0 == 100 && a1 in 64..127 -> Kind.PRIVATE // CGNAT / Tailscale
            a0 == 127 -> Kind.LOOPBACK
            a0 == 169 && a1 == 254 -> Kind.FORBIDDEN // enlace local, metadatos de la nube
            a0 == 172 && a1 in 16..31 -> Kind.PRIVATE
            a0 == 192 && a1 == 0 && a2 == 0 -> Kind.FORBIDDEN // asignaciones del IETF
            a0 == 192 && a1 == 0 && a2 == 2 -> Kind.FORBIDDEN // TEST-NET-1
            a0 == 192 && a1 == 88 && a2 == 99 -> Kind.FORBIDDEN // relé 6to4
            a0 == 192 && a1 == 168 -> Kind.PRIVATE
            a0 == 198 && a1 in 18..19 -> Kind.PRIVATE // pruebas de rendimiento
            a0 == 198 && a1 == 51 && a2 == 100 -> Kind.FORBIDDEN // TEST-NET-2
            a0 == 203 && a1 == 0 && a2 == 113 -> Kind.FORBIDDEN // TEST-NET-3
            a0 >= 224 -> Kind.FORBIDDEN // multicast, reservadas y broadcast
            else -> Kind.PUBLIC
        }
    }

    internal fun classifyV6(b: ByteArray): Kind {
        fun u(i: Int) = b[i].toInt() and 0xff
        fun zero(from: Int, to: Int) = (from until to).all { b[it].toInt() == 0 }
        fun v4(from: Int) = classifyV4(b.copyOfRange(from, from + 4))
        return when {
            zero(0, 15) && u(15) == 1 -> Kind.LOOPBACK // ::1
            zero(0, 10) && u(10) == 0xff && u(11) == 0xff -> v4(12) // ::ffff:a.b.c.d (IPv4 mapeada)
            zero(0, 8) && u(8) == 0xff && u(9) == 0xff && zero(10, 12) -> v4(12) // ::ffff:0:a.b.c.d
            zero(0, 12) -> Kind.FORBIDDEN // :: y ::a.b.c.d (compatible IPv4, obsoleta)
            u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xff && u(3) == 0x9b && zero(4, 12) -> v4(12) // 64:ff9b::/96 NAT64
            u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xff && u(3) == 0x9b && u(4) == 0x00 && u(5) == 0x01 -> Kind.PRIVATE // 64:ff9b:1::/48
            u(0) == 0x01 && u(1) == 0x00 && zero(2, 8) -> Kind.FORBIDDEN // 100::/64 descarte
            u(0) == 0x20 && u(1) == 0x01 && u(2) < 0x02 -> Kind.FORBIDDEN // 2001::/23 (Teredo, ORCHID...)
            u(0) == 0x20 && u(1) == 0x01 && u(2) == 0x0d && u(3) == 0xb8 -> Kind.FORBIDDEN // 2001:db8::/32 documentación
            u(0) == 0x20 && u(1) == 0x02 -> v4(2) // 2002::/16 6to4
            (u(0) and 0xfe) == 0xfc -> Kind.PRIVATE // fc00::/7 ULA
            u(0) == 0xfe && (u(1) and 0xc0) == 0x80 -> Kind.FORBIDDEN // fe80::/10 enlace local
            u(0) == 0xfe && (u(1) and 0xc0) == 0xc0 -> Kind.PRIVATE // fec0::/10 site-local (obsoleta)
            u(0) == 0xff -> Kind.FORBIDDEN // multicast
            (u(0) and 0xe0) == 0x20 -> Kind.PUBLIC // 2000::/3 unicast global
            else -> Kind.FORBIDDEN // sin asignar
        }
    }

    fun normalizeHost(host: String): String = host.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')

    private fun syntax(message: String) = UnsafeUrlException(message, blockedAddress = false)

    private val NUMERIC_LABEL = Regex("^(?:0x[0-9a-f]*|[0-9]+)$", RegexOption.IGNORE_CASE)
    private val CANONICAL_IPV4 = Regex("^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$")
}

/** Resultado de [safeGet]: cuerpo ya leído y recortado a `maxBytes`. */
class SafeFetch(val url: Url, val status: Int, val contentType: String?, val body: ByteArray, val truncated: Boolean)

private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/**
 * GET a una URL que eligió un usuario: valida cada salto con [SafeUrl.check], sigue a mano como mucho [maxRedirects]
 * redirecciones y lee como mucho [maxBytes] del cuerpo sin cargar el resto en memoria.
 * El cliente debe tener `followRedirects = false`: si no, Ktor seguiría las redirecciones sin validarlas.
 */
suspend fun HttpClient.safeGet(
    raw: String,
    policy: SafeUrl.Policy,
    maxBytes: Int,
    maxRedirects: Int = 3,
    configure: HttpRequestBuilder.() -> Unit = {},
): SafeFetch {
    var target = SafeUrl.check(raw, policy).url
    var hops = 0
    while (true) {
        val current = target
        val result: Any = prepareGet(current) { configure() }.execute { resp ->
            if (resp.status.value in REDIRECT_CODES) {
                resp.headers[HttpHeaders.Location] ?: ""
            } else {
                val (body, truncated) = resp.bodyAsChannel().readCapped(maxBytes)
                SafeFetch(current, resp.status.value, resp.headers[HttpHeaders.ContentType], body, truncated)
            }
        }
        if (result is SafeFetch) return result
        val location = (result as String).trim()
        if (location.isEmpty()) throw SafeUrl.UnsafeUrlException("Redirección sin destino")
        if (++hops > maxRedirects) throw SafeUrl.UnsafeUrlException("Demasiadas redirecciones")
        val next = runCatching { URI(current.toString()).resolve(location).toString() }.getOrNull()
            ?: throw SafeUrl.UnsafeUrlException("Redirección inválida")
        target = SafeUrl.check(next, policy).url
    }
}
