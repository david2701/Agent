package app.kairo.util

import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.http.Url

/** Proxy de salida opcional tomado de HTTPS_PROXY (útil si el servidor sale por un proxy corporativo). */
fun proxyFromEnv(): ProxyConfig? {
    val raw = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: return null
    return runCatching { ProxyBuilder.http(Url(raw)) }.getOrNull()
}

/**
 * true si a [host] hay que llegar sin proxy: está en NO_PROXY o es una dirección local/privada
 * (un ntfy en la misma red, por ejemplo).
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

