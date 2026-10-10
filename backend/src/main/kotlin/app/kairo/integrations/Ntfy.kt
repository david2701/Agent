package app.kairo.integrations

import app.kairo.util.proxyFromEnv
import app.kairo.config.AppConfig
import app.kairo.util.logSafe
import app.kairo.util.readCapped
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Self-hosted push without Firebase through ntfy (https://ntfy.sh, or your own ntfy server).
 * It is a backup channel: the app receives notices mainly through its WebSocket and local alarms.
 */
class NtfyNotifier(private val cfg: AppConfig) {
    private val log = LoggerFactory.getLogger(NtfyNotifier::class.java)
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
        followRedirects = false
        expectSuccess = false
    }

    val enabled: Boolean get() = cfg.ntfyUrl != null

    /**
     * Publishes to [topicOverride] (or to NTFY_TOPIC). NTFY_TOKEN is only attached with [authenticated]: the token belongs
     * to the owner and must not be usable to publish to topics another member chooses.
     */
    suspend fun send(
        topicOverride: String?,
        title: String,
        message: String,
        tags: List<String> = emptyList(),
        clickUrl: String? = null,
        priority: Int = 4,
        authenticated: Boolean = topicOverride == null,
    ) {
        val base = cfg.ntfyUrl ?: return
        val topic = topicOverride ?: cfg.ntfyTopic ?: return
        runCatching {
            http.preparePost(base) {
                contentType(ContentType.Application.Json)
                if (authenticated) cfg.ntfyToken?.let { header("Authorization", "Bearer $it") }
                setBody(buildJsonObject {
                    put("topic", topic)
                    put("title", title)
                    put("message", message)
                    put("priority", priority)
                    if (tags.isNotEmpty()) put("tags", buildJsonArray { tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                    clickUrl?.let { put("click", it) }
                }.toString())
            }.execute { resp ->
                resp.bodyAsChannel().readCapped(MAX_RESPONSE_BYTES)
                if (resp.status.value !in 200..299) log.warn("ntfy responded {}", resp.status.value)
            }
        }.onFailure { log.warn("ntfy failed: {}", logSafe(it.message)) }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
