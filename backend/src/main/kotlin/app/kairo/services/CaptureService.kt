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
import app.kairo.util.M
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
 * "Save to Kairo" (share): images (with OCR through vision), links and text.
 * The content becomes context and the agent decides what to save (note, reminder, event...).
 */
class CaptureService(
    private val agent: Agent,
    private val nan: NanClient,
    private val files: FilesRepo,
    private val settings: SettingsService,
) {
    private val log = LoggerFactory.getLogger(CaptureService::class.java)

    /** No automatic redirects: [safeGet] follows them by hand and validates every hop. */
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
            val id = files.store(user.id, image, type, "capture")
            attachments += files.ref(id, type)
            context.appendLine("Shared image. Analysis:")
            context.appendLine(describeImage(user, image, type))
        }
        url?.takeIf { it.isNotBlank() }?.let { u ->
            context.appendLine("Shared link: $u")
            fetchLinkPreview(u)?.let { context.appendLine(it) }
        }
        val lang = settings.lang(user.id)
        val instruction = text?.takeIf { it.isNotBlank() }
            ?: "Save this in a useful way. Create a note with the important parts (clear title and tags). " +
            "If it contains a deadline, appointment, flight, payment or event, also create the matching reminder or event. Then summarize in one sentence what you saved."
        val display = text?.takeIf { it.isNotBlank() } ?: when {
            image != null -> M.CAPTURE_SHARED_IMAGE.t(lang)
            url != null -> M.CAPTURE_SHARED_LINK.t(lang, url)
            else -> M.CAPTURE_SHARED_CONTENT.t(lang)
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
                    "Analyze this image for a personal assistant. 1) Describe it in one or two sentences. " +
                        "2) Faithfully transcribe ALL the visible text (keep it in its original language). 3) If it is an invoice, receipt, ticket, invitation, " +
                        "prescription or document, extract dates, times, amounts, places, names and key data as a list. " +
                        "Answer in ${settings.langOf(s).englishName}, without inventing anything.",
                    dataUrl,
                ),
            ),
            opts = ChatOptions(model = s.visionModel, maxTokens = 1200, reasoningEffort = "none"),
        )
        return res.message.text.trim()
    }

    /**
     * Link preview with SSRF protection (see [SafeUrl]): only public hosts on every hop,
     * at most [MAX_REDIRECTS] redirects, [MAX_BYTES] of body and [TOTAL_TIMEOUT_MS] in total.
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
            log.info("Link preview discarded: {}", logSafe(e.message ?: e.javaClass.simpleName, 200))
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
                title?.let { appendLine("Title: ${decode(it)}") }
                desc?.let { appendLine("Description: ${decode(it)}") }
                if (body.isNotBlank()) appendLine("Text: ${decode(body)}")
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
