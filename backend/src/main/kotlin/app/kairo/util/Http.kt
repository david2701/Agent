package app.kairo.util

import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.http.HttpClient

/** Optional outbound proxy taken from HTTPS_PROXY (useful if the server goes out through a corporate proxy). */
fun proxyFromEnv(): ProxyConfig? {
    val raw = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: return null
    return runCatching { ProxyBuilder.http(Url(raw)) }.getOrNull()
}

/**
 * Same as [proxyFromEnv] for the JDK HTTP client (APNs needs HTTP/2, which the CIO engine does not have).
 * Without HTTPS_PROXY it uses the JVM default selector (https.proxyHost properties, etc.).
 */
fun jdkProxySelector(): ProxySelector =
    (proxyFromEnv()?.address() as? InetSocketAddress)?.let { ProxySelector.of(it) }
        ?: ProxySelector.getDefault()
        ?: HttpClient.Builder.NO_PROXY

/**
 * true if [host] must be reached without a proxy: it is in NO_PROXY or is a local/private address
 * (an ntfy on the same network, for example). It must only be used with hosts the operator configured
 * (their own ntfy): never with URLs chosen by a user.
 */
fun bypassProxy(host: String): Boolean {
    val h = host.lowercase().trim('[', ']')
    if (h == "localhost" || h == "::1" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")) return true
    if (Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(h)) return true
    val list = (System.getenv("NO_PROXY") ?: System.getenv("no_proxy") ?: "").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    return list.any { e ->
        e == "*" || h == e || (e.startsWith("*.") && h.endsWith(e.removePrefix("*"))) || (e.startsWith(".") && h.endsWith(e))
    }
}

/**
 * Reads at most [max] bytes from the channel, without loading the rest in memory.
 * Returns the bytes and whether more content was left (truncated).
 */
suspend fun ByteReadChannel.readCapped(max: Int): Pair<ByteArray, Boolean> {
    require(max >= 0)
    val limit = max.toLong() + 1 // one extra byte to know whether it was trimmed
    val out = ByteArrayOutputStream(minOf(limit, 64L * 1024).toInt())
    val buf = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val n = readAvailable(buf, 0, minOf(buf.size.toLong(), limit - out.size()).toInt())
        if (n <= 0) break
        out.write(buf, 0, n)
    }
    val bytes = out.toByteArray()
    return if (bytes.size > max) bytes.copyOf(max) to true else bytes to false
}

/**
 * Text fit for the log: escapes CR/LF and other control characters (prevents injecting fake lines into the log)
 * and trims it to [max] characters.
 */
fun logSafe(value: Any?, max: Int = 300): String {
    val s = value?.toString() ?: return "null"
    val sb = StringBuilder(minOf(s.length, max) + 8)
    for (c in s) {
        if (sb.length >= max) { sb.append('…'); break }
        when {
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.isISOControl() || c == '\u2028' || c == '\u2029' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
