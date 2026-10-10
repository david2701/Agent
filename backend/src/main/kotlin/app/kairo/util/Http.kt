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

/** Proxy de salida opcional tomado de HTTPS_PROXY (útil si el servidor sale por un proxy corporativo). */
fun proxyFromEnv(): ProxyConfig? {
    val raw = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: return null
    return runCatching { ProxyBuilder.http(Url(raw)) }.getOrNull()
}

/**
 * Lo mismo que [proxyFromEnv] para el cliente HTTP del JDK (APNs necesita HTTP/2, que el motor CIO no tiene).
 * Sin HTTPS_PROXY usa el selector por defecto de la JVM (propiedades https.proxyHost, etc.).
 */
fun jdkProxySelector(): ProxySelector =
    (proxyFromEnv()?.address() as? InetSocketAddress)?.let { ProxySelector.of(it) }
        ?: ProxySelector.getDefault()
        ?: HttpClient.Builder.NO_PROXY

/**
 * true si a [host] hay que llegar sin proxy: está en NO_PROXY o es una dirección local/privada
 * (un ntfy en la misma red, por ejemplo). Sólo debe usarse con hosts que configuró el operador
 * (el ntfy propio): nunca con URLs elegidas por un usuario.
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
 * Lee como mucho [max] bytes del canal, sin cargar el resto en memoria.
 * Devuelve los bytes y si quedaba más contenido (truncated).
 */
suspend fun ByteReadChannel.readCapped(max: Int): Pair<ByteArray, Boolean> {
    require(max >= 0)
    val limit = max.toLong() + 1 // un byte de más para saber si se recortó
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
 * Texto apto para el log: escapa CR/LF y otros caracteres de control (evita inyectar líneas falsas en el log)
 * y lo recorta a [max] caracteres.
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
