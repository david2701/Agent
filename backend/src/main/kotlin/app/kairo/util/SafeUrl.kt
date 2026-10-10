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
 * SSRF protection for requests to URLs chosen by a user (link previews, MCP servers, Web Push endpoints).
 *
 * A URL passes if:
 * - it is http or https, without `user:password@` and without ambiguous IP forms (`2130706433`, `0x7f.1`, `0177.0.0.1`, `127.1`);
 * - the host is not an internal name (`localhost`, the docker-compose services `db`, `kairo`, `ntfy`, `backup`...);
 * - ALL the addresses it resolves to are public: no loopback, link-local (169.254/16, fe80::/10),
 *   private networks (10/8, 172.16/12, 192.168/16, fc00::/7), CGNAT (100.64/10), 0.0.0.0/8, multicast, broadcast,
 *   reserved or documentation ranges, nor their IPv4-mapped / NAT64 / 6to4 forms.
 *
 * With [Policy.allowPrivate] private networks and CGNAT are accepted too (a Home Assistant on the LAN or over Tailscale),
 * but never loopback, link-local (cloud metadata) or the internal names. The [Policy.trustedHosts]
 * (an explicit operator decision) are accepted without looking at their addresses.
 *
 * Residual risk (DNS rebinding / TOCTOU): the CIO engine resolves the name again when connecting and there is no way to
 * pin the IP that was already checked (TLS needs the name for SNI and to validate the certificate). It is mitigated by the
 * JVM DNS cache (`networkaddress.cache.ttl`, 30 s by default): the check happens right before every request and every
 * redirect, so the connection reuses the already validated answer unless the cache expires in between.
 * With HTTPS_PROXY the proxy is the one that resolves and connects, and the check only covers local resolution.
 */
object SafeUrl {
    /** Class of an IP address. */
    enum class Kind { PUBLIC, PRIVATE, LOOPBACK, FORBIDDEN }

    data class Policy(
        /** Accepts private networks (RFC 1918, fc00::/7) and CGNAT. Never loopback, link-local or internal names. */
        val allowPrivate: Boolean = false,
        /** Exact hosts accepted without checking their addresses (configured by the operator). */
        val trustedHosts: Set<String> = emptySet(),
    ) {
        internal val trusted: Set<String> = trustedHosts.map { normalizeHost(it) }.filter { it.isNotEmpty() }.toSet()

        fun isTrusted(host: String): Boolean = normalizeHost(host) in trusted
    }

    /**
     * Rejected URL. [blockedAddress] = the format is valid but the destination is not allowed (or does not resolve).
     * It is an [InvalidInput], so the message reaches the user in their language.
     */
    class UnsafeUrlException(msg: M, val blockedAddress: Boolean = false) : InvalidInput(msg)

    /** Checked URL: [url] must be used as is (do not interpret the original text again). */
    data class Checked(val url: Url, val host: String, val addresses: List<InetAddress>, val trusted: Boolean)

    /** Names that are never accepted unless the operator puts them in [Policy.trustedHosts]. */
    val INTERNAL_NAMES = setOf(
        "localhost", "db", "kairo", "ntfy", "backup",
        "host.docker.internal", "gateway.docker.internal", "metadata", "metadata.google.internal", "instance-data",
    )

    /** English text of the "blocked address" error (the user sees it in their own language). */
    val BLOCKED_MESSAGE: String = M.SAFE_BLOCKED.en

    /** Validates the URL and resolves the host (on Dispatchers.IO). Throws [UnsafeUrlException] if it is not allowed. */
    suspend fun check(raw: String, policy: Policy = Policy()): Checked =
        withContext(Dispatchers.IO) { checkBlocking(raw, policy) }

    /** Same as [check], blocking. [resolver] is only replaced in tests. */
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
        if (addresses.isEmpty()) throw UnsafeUrlException(M.SAFE_BLOCKED, blockedAddress = true)
        if (!addresses.all { isAllowed(it, policy) }) throw UnsafeUrlException(M.SAFE_BLOCKED, blockedAddress = true)
        return Checked(url, host, addresses, trusted = false)
    }

    /**
     * Strict syntactic analysis. Ktor's parser (the one the client uses) and java.net.URI must agree on
     * scheme, host and port, so there are no two different readings of the same URL.
     */
    fun parse(raw: String): Url {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 4096 || s.any { it.isISOControl() || it.isWhitespace() || it == '\\' }) throw syntax(M.SAFE_INVALID_URL)
        val uri = runCatching { URI(s) }.getOrNull() ?: throw syntax(M.SAFE_INVALID_URL)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw syntax(M.SAFE_BAD_SCHEME)
        if (uri.rawUserInfo != null) throw syntax(M.SAFE_NO_CREDENTIALS)
        val uriHost = uri.host?.let(::normalizeHost)?.takeIf { it.isNotEmpty() } ?: throw syntax(M.SAFE_NO_HOST)
        val url = runCatching { Url(s) }.getOrNull() ?: throw syntax(M.SAFE_INVALID_URL)
        if (url.protocol.name.lowercase() != scheme) throw syntax(M.SAFE_INVALID_URL)
        if (!url.encodedUser.isNullOrEmpty() || !url.encodedPassword.isNullOrEmpty()) throw syntax(M.SAFE_NO_CREDENTIALS)
        if (normalizeHost(url.host) != uriHost) throw syntax(M.SAFE_AMBIGUOUS)
        val uriPort = if (uri.port == -1) url.protocol.defaultPort else uri.port
        if (url.port != uriPort || uriPort !in 1..65535) throw syntax(M.SAFE_AMBIGUOUS)
        return url
    }

    /** Rules about the name (no DNS). */
    internal fun checkHostName(host: String, policy: Policy) {
        if (host.isEmpty() || host.contains('%')) throw syntax(M.SAFE_NO_HOST)
        if (host in INTERNAL_NAMES || host.endsWith(".localhost")) throw UnsafeUrlException(M.SAFE_BLOCKED, blockedAddress = true)
        if (host.contains(':')) return // literal IPv6: classified by its address
        val labels = host.split('.')
        if (labels.any { it.isEmpty() }) throw syntax(M.SAFE_NO_HOST)
        // Any numeric final label makes some parser read it as IPv4: only the canonical format is accepted
        if (NUMERIC_LABEL.matches(labels.last()) && !CANONICAL_IPV4.matches(host)) throw syntax(M.SAFE_AMBIGUOUS_IP)
        if (labels.size == 1 && !policy.allowPrivate) throw UnsafeUrlException(M.SAFE_BLOCKED, blockedAddress = true)
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
            a0 == 0 -> Kind.FORBIDDEN // 0.0.0.0/8 "this network"
            a0 == 10 -> Kind.PRIVATE
            a0 == 100 && a1 in 64..127 -> Kind.PRIVATE // CGNAT / Tailscale
            a0 == 127 -> Kind.LOOPBACK
            a0 == 169 && a1 == 254 -> Kind.FORBIDDEN // link-local, cloud metadata
            a0 == 172 && a1 in 16..31 -> Kind.PRIVATE
            a0 == 192 && a1 == 0 && a2 == 0 -> Kind.FORBIDDEN // IETF assignments
            a0 == 192 && a1 == 0 && a2 == 2 -> Kind.FORBIDDEN // TEST-NET-1
            a0 == 192 && a1 == 88 && a2 == 99 -> Kind.FORBIDDEN // 6to4 relay
            a0 == 192 && a1 == 168 -> Kind.PRIVATE
            a0 == 198 && a1 in 18..19 -> Kind.PRIVATE // benchmarking
            a0 == 198 && a1 == 51 && a2 == 100 -> Kind.FORBIDDEN // TEST-NET-2
            a0 == 203 && a1 == 0 && a2 == 113 -> Kind.FORBIDDEN // TEST-NET-3
            a0 >= 224 -> Kind.FORBIDDEN // multicast, reserved and broadcast
            else -> Kind.PUBLIC
        }
    }

    internal fun classifyV6(b: ByteArray): Kind {
        fun u(i: Int) = b[i].toInt() and 0xff
        fun zero(from: Int, to: Int) = (from until to).all { b[it].toInt() == 0 }
        fun v4(from: Int) = classifyV4(b.copyOfRange(from, from + 4))
        return when {
            zero(0, 15) && u(15) == 1 -> Kind.LOOPBACK // ::1
            zero(0, 10) && u(10) == 0xff && u(11) == 0xff -> v4(12) // ::ffff:a.b.c.d (mapped IPv4)
            zero(0, 8) && u(8) == 0xff && u(9) == 0xff && zero(10, 12) -> v4(12) // ::ffff:0:a.b.c.d
            zero(0, 12) -> Kind.FORBIDDEN // :: and ::a.b.c.d (IPv4-compatible, obsolete)
            u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xff && u(3) == 0x9b && zero(4, 12) -> v4(12) // 64:ff9b::/96 NAT64
            u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xff && u(3) == 0x9b && u(4) == 0x00 && u(5) == 0x01 -> Kind.PRIVATE // 64:ff9b:1::/48
            u(0) == 0x01 && u(1) == 0x00 && zero(2, 8) -> Kind.FORBIDDEN // 100::/64 discard
            u(0) == 0x20 && u(1) == 0x01 && u(2) < 0x02 -> Kind.FORBIDDEN // 2001::/23 (Teredo, ORCHID...)
            u(0) == 0x20 && u(1) == 0x01 && u(2) == 0x0d && u(3) == 0xb8 -> Kind.FORBIDDEN // 2001:db8::/32 documentation
            u(0) == 0x20 && u(1) == 0x02 -> v4(2) // 2002::/16 6to4
            (u(0) and 0xfe) == 0xfc -> Kind.PRIVATE // fc00::/7 ULA
            u(0) == 0xfe && (u(1) and 0xc0) == 0x80 -> Kind.FORBIDDEN // fe80::/10 link-local
            u(0) == 0xfe && (u(1) and 0xc0) == 0xc0 -> Kind.PRIVATE // fec0::/10 site-local (obsolete)
            u(0) == 0xff -> Kind.FORBIDDEN // multicast
            (u(0) and 0xe0) == 0x20 -> Kind.PUBLIC // 2000::/3 global unicast
            else -> Kind.FORBIDDEN // unassigned
        }
    }

    fun normalizeHost(host: String): String = host.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')

    private fun syntax(message: M) = UnsafeUrlException(message, blockedAddress = false)

    private val NUMERIC_LABEL = Regex("^(?:0x[0-9a-f]*|[0-9]+)$", RegexOption.IGNORE_CASE)
    private val CANONICAL_IPV4 = Regex("^(?:(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$")
}

/** Result of [safeGet]: body already read and trimmed to `maxBytes`. */
class SafeFetch(val url: Url, val status: Int, val contentType: String?, val body: ByteArray, val truncated: Boolean)

private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/**
 * GET to a URL a user chose: validates every hop with [SafeUrl.check], follows at most [maxRedirects]
 * redirects by hand and reads at most [maxBytes] of the body without loading the rest in memory.
 * The client must have `followRedirects = false`: otherwise Ktor would follow redirects without validating them.
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
        if (location.isEmpty()) throw SafeUrl.UnsafeUrlException(M.SAFE_REDIRECT_EMPTY)
        if (++hops > maxRedirects) throw SafeUrl.UnsafeUrlException(M.SAFE_TOO_MANY_REDIRECTS)
        val next = runCatching { URI(current.toString()).resolve(location).toString() }.getOrNull()
            ?: throw SafeUrl.UnsafeUrlException(M.SAFE_BAD_REDIRECT)
        target = SafeUrl.check(next, policy).url
    }
}
