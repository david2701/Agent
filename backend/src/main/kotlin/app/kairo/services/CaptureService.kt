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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
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
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 8_000 }
        followRedirects = true
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

    /** Vista previa de un enlace con protección SSRF (no se permiten direcciones privadas). */
    private suspend fun fetchLinkPreview(raw: String): String? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        val safe = withContext(Dispatchers.IO) {
            runCatching { InetAddress.getAllByName(uri.host).none { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress } }.getOrDefault(false)
        }
        if (!safe) return null
        return runCatching {
            val resp = http.get(uri.toString()) { header("User-Agent", "Mozilla/5.0 (compatible; KairoBot/1.0)") }
            if (!resp.status.isSuccess()) return null
            val ch = resp.bodyAsChannel()
            val buf = ByteArray(512 * 1024)
            var total = 0
            while (total < buf.size) {
                val n = ch.readAvailable(buf, total, buf.size - total)
                if (n <= 0) break
                total += n
            }
            val html = String(buf, 0, total, Charsets.UTF_8)
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
}
