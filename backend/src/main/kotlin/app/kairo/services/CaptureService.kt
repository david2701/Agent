package app.kairo.services

import app.kairo.util.proxyFromEnv
import app.kairo.agent.Agent
import app.kairo.agent.AgentEvent
import app.kairo.agent.AgentRequest
import app.kairo.agent.GeoPoint
import app.kairo.domain.FilesRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.User
import app.kairo.nan.ChatMessage
import app.kairo.nan.ChatOptions
import app.kairo.nan.NanClient
import app.kairo.util.SafeUrl
import app.kairo.util.logSafe
import app.kairo.util.safeGet
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.UUID

/**
 * "Compartir con Kairo": imágenes (con OCR por visión), enlaces y texto.
 * El contenido se convierte en contexto y el agente decide qué guardar (nota, recordatorio, evento...).
 */
class CaptureService(
    private val agent: Agent,
    private val nan: NanClient,
    private val files: FilesRepo,
    private val settings: SettingsService,
) {
    private val log = LoggerFactory.getLogger(CaptureService::class.java)

    /** Sin redirecciones automáticas: [safeGet] las sigue a mano y valida cada salto. */
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
        followRedirects = false
        expectSuccess = false
    }

    suspend fun capture(
        user: User,
        text: String?,
        url: String?,
        image: ByteArray?,
        imageType: String?,
        conversationId: UUID?,
        location: GeoPoint?,
        capabilities: Set<String>,
    ): Flow<AgentEvent> {
        val context = StringBuilder()
        val attachments = ArrayList<app.kairo.domain.FileRef>()
        if (image != null) {
            val type = imageType ?: "image/jpeg"
            val id = files.store(user.id, image, type, "captura")
            attachments += files.ref(id, type)
            context.appendLine("Imagen compartida. Análisis:")
            context.appendLine(describeImage(user, image, type))
        }
        url?.takeIf { it.isNotBlank() }?.let { u ->
            context.appendLine("Enlace compartido: $u")
            fetchLinkPreview(u)?.let { context.appendLine(it) }
        }
        val instruction = text?.takeIf { it.isNotBlank() }
            ?: "Guarda esto de forma útil. Crea una nota con lo importante (título claro y etiquetas). " +
            "Si contiene una fecha límite, cita, vuelo, pago o evento, crea también el recordatorio o el evento correspondiente. Luego resume en una frase qué guardaste."
        val display = text?.takeIf { it.isNotBlank() } ?: when {
            image != null -> "Compartí una imagen"
            url != null -> "Compartí un enlace: $url"
            else -> "Compartí contenido"
        }
        return agent.run(
            AgentRequest(
                user = user,
                text = instruction,
                displayText = display,
                conversationId = conversationId,
                channel = "share",
                location = location,
                capabilities = capabilities,
                hiddenContext = context.toString().take(8000).ifBlank { null },
                attachments = attachments,
            ),
        )
    }

    suspend fun describeImage(user: User, image: ByteArray, type: String): String {
        val s = settings.get(user.id)
        val dataUrl = "data:$type;base64," + Base64.getEncoder().encodeToString(image)
        val res = nan.chat(
            listOf(
                ChatMessage.userWithImage(
                    "Analiza esta imagen para un asistente personal. 1) Descríbela en una o dos frases. " +
                        "2) Transcribe fielmente TODO el texto visible. 3) Si es factura, recibo, boleto, invitación, receta o documento, " +
                        "extrae fechas, horas, montos, lugares, nombres y datos clave en una lista. Responde en español, sin inventar.",
                    dataUrl,
                ),
            ),
            opts = ChatOptions(model = s.visionModel, maxTokens = 1200, reasoningEffort = "none"),
        )
        return res.message.text.trim()
    }

    /**
     * Vista previa de un enlace con protección SSRF (ver [SafeUrl]): sólo hosts públicos en cada salto,
     * como mucho [MAX_REDIRECTS] redirecciones, [MAX_BYTES] de cuerpo y [TOTAL_TIMEOUT_MS] en total.
     */
    private suspend fun fetchLinkPreview(raw: String): String? {
        val fetched = try {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                http.safeGet(raw, SafeUrl.Policy(), maxBytes = MAX_BYTES, maxRedirects = MAX_REDIRECTS) {
                    header("User-Agent", "Mozilla/5.0 (compatible; KairoBot/1.0)")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("Vista previa de enlace descartada: {}", logSafe(e.message ?: e.javaClass.simpleName, 200))
            null
        } ?: return null
        if (fetched.status !in 200..299) return null
        return runCatching {
            val html = String(fetched.body, Charsets.UTF_8)
            val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()
            val desc = Regex("<meta[^>]+(?:name|property)=[\"'](?:og:)?description[\"'][^>]*content=[\"']([^\"']*)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            val body = html.replace(Regex("<script[\\s\\S]*?</script>|<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ").replace(Regex("&nbsp;|&#160;"), " ").replace(Regex("\\s+"), " ").trim().take(2500)
            buildString {
                title?.let { appendLine("Título: ${decode(it)}") }
                desc?.let { appendLine("Descripción: ${decode(it)}") }
                if (body.isNotBlank()) appendLine("Texto: ${decode(body)}")
            }
        }.getOrNull()
    }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private companion object {
        const val MAX_BYTES = 512 * 1024
        const val MAX_REDIRECTS = 3
        const val TOTAL_TIMEOUT_MS = 12_000L
    }
}
