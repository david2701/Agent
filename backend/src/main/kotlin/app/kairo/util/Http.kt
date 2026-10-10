package app.kairo.util

import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.http.Url

/** Proxy de salida opcional tomado de HTTPS_PROXY (útil si el servidor sale por un proxy corporativo). */
fun proxyFromEnv(): ProxyConfig? {
    val raw = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy") ?: return null
    return runCatching { ProxyBuilder.http(Url(raw)) }.getOrNull()
}
