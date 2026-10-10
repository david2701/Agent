package app.kairo.integrations

import app.kairo.util.proxyFromEnv
import app.kairo.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Push autoalojado sin Firebase con ntfy (https://ntfy.sh, o tu propio servidor ntfy).
 * Es un canal de respaldo: la app recibe los avisos principalmente por su WebSocket y por alarmas locales.
 */
class NtfyNotifier(private val cfg: AppConfig) {
    private val log = LoggerFactory.getLogger(NtfyNotifier::class.java)
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
        expectSuccess = false
    }

    val enabled: Boolean get() = cfg.ntfyUrl != null

    suspend fun send(topicOverride: String?, title: String, message: String, tags: List<String> = emptyList(), clickUrl: String? = null, priority: Int = 4) {
        val base = cfg.ntfyUrl ?: return
        val topic = topicOverride ?: cfg.ntfyTopic ?: return
        runCatching {
            http.post(base) {
                contentType(ContentType.Application.Json)
                cfg.ntfyToken?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("topic", topic)
                    put("title", title)
                    put("message", message)
                    put("priority", priority)
                    if (tags.isNotEmpty()) put("tags", buildJsonArray { tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    clickUrl?.let { put("click", it) }
                }.toString())
            }
        }.onFailure { log.warn("ntfy falló: {}", it.message) }
    }
}
