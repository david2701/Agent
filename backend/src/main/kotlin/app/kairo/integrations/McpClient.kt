package app.kairo.integrations

import app.kairo.util.Crypto
import app.kairo.util.KairoJson
import app.kairo.util.Lang
import app.kairo.util.Localizable
import app.kairo.util.M
import app.kairo.util.Say
import app.kairo.util.InvalidInput
import app.kairo.util.SafeUrl
import app.kairo.util.logSafe
import app.kairo.util.proxyFromEnv
import app.kairo.util.readCapped
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class McpTool(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * MCP error. The message is shown to the user and to the model: it never carries details of the remote server.
 * [text] renders it in the reader's language; [message] is the English text.
 */
class McpException(val msg: M, val args: List<Any?> = emptyList()) :
    RuntimeException(Say(msg, args).text(Lang.EN)), Localizable {
    override fun text(lang: Lang): String = Say(msg, args).text(lang)
}

/**
 * Minimal Model Context Protocol client over "Streamable HTTP" (JSON-RPC 2.0).
 * It lets remote MCP servers (Notion, Home Assistant, etc.) be connected as agent tools.
 *
 * Security:
 * - Every request validates the URL with [SafeUrl]: public hosts only, except `KAIRO_MCP_ALLOW_PRIVATE=true`
 *   (servers on the LAN, like Home Assistant) and the hosts of `KAIRO_MCP_ALLOWED_HOSTS`.
 * - It does not follow redirects (a 3xx is an error).
 * - Responses are read with a limit of [MAX_RESPONSE_BYTES].
 * - The errors the user sees are generic (they cannot be used to scan ports); the detail goes only to the log.
 * - Sessions and the tool cache are separated by URL and headers (credentials), so two users
 *   with the same URL never share the session the other one opened.
 */
class McpClient(
    private val policy: SafeUrl.Policy = policyFromEnv(),
    outboundProxy: ProxyConfig? = proxyFromEnv(),
) {
    private val log = LoggerFactory.getLogger(McpClient::class.java)
    private val http = HttpClient(CIO) {
        engine { outboundProxy?.let { proxy = it } }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
        followRedirects = false
        expectSuccess = false
    }
    private val ids = AtomicInteger(1)
    private val sessions = ConcurrentHashMap<String, String>()
    private val toolCache = ConcurrentHashMap<String, Pair<Long, List<McpTool>>>()

    /**
     * Checks a URL before saving it. Throws an [InvalidInput] with a message for the user.
     */
    suspend fun validateUrl(url: String) {
        try {
            SafeUrl.check(url, policy)
        } catch (e: SafeUrl.UnsafeUrlException) {
            if (!e.blockedAddress) throw e
            throw InvalidInput(if (policy.allowPrivate) M.MCP_ADDRESS_NOT_ALLOWED else M.MCP_LOCAL_NETWORK)
        }
    }

    suspend fun listTools(url: String, headers: Map<String, String>): List<McpTool> {
        val key = cacheKey(url, headers)
        toolCache[key]?.let { (at, tools) -> if (System.currentTimeMillis() - at < 5 * 60_000) return tools }
        ensureSession(key, url, headers)
        val result = rpc(key, url, headers, "tools/list", JsonObject(emptyMap()))
        val tools = runCatching {
            (result["tools"] as? JsonArray)?.map { t ->
                val o = t.jsonObject
                McpTool(
                    name = o["name"]!!.jsonPrimitive.content,
                    description = (o["description"] as? JsonPrimitive)?.content ?: "",
                    inputSchema = (o["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") },
                )
            } ?: emptyList()
        }.getOrElse {
            log.warn("MCP {}: invalid tool list: {}", hostOf(url), logSafe(it.message))
            throw McpException(M.MCP_CONNECT_FAILED)
        }
        toolCache[key] = System.currentTimeMillis() to tools
        return tools
    }

    suspend fun callTool(url: String, headers: Map<String, String>, name: String, arguments: JsonObject): String {
        val key = cacheKey(url, headers)
        ensureSession(key, url, headers)
        val result = rpc(key, url, headers, "tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        val content = (result["content"] as? JsonArray)?.joinToString("\n") { c ->
            val o = c as? JsonObject ?: return@joinToString c.toString()
            when ((o["type"] as? JsonPrimitive)?.content) {
                "text" -> (o["text"] as? JsonPrimitive)?.content ?: ""
                else -> o.toString()
            }
        } ?: result.toString()
        return content.take(12_000)
    }

    /** Forgets the sessions and tools of [url] (for any user or credentials). */
    fun invalidate(url: String) {
        val prefix = urlPrefix(url)
        sessions.keys.removeIf { it.startsWith(prefix) }
        toolCache.keys.removeIf { it.startsWith(prefix) }
    }

    private fun invalidateKey(key: String) {
        sessions.remove(key)
        toolCache.remove(key)
    }

    private suspend fun ensureSession(key: String, url: String, headers: Map<String, String>) {
        if (sessions.containsKey(key)) return
        val resp = post(url, headers, null, buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.getAndIncrement())
            put("method", "initialize")
            put("params", buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("capabilities", JsonObject(emptyMap()))
                put("clientInfo", buildJsonObject { put("name", "kairo"); put("version", "1.0") })
            })
        })
        parse(url, resp)
        val session = resp.sessionId ?: ""
        sessions[key] = session
        post(url, headers, session, buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        })
    }

    private suspend fun rpc(key: String, url: String, headers: Map<String, String>, method: String, params: JsonObject): JsonObject {
        val resp = post(url, headers, sessions[key], buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.getAndIncrement())
            put("method", method)
            put("params", params)
        })
        if (resp.status == 404) {
            invalidateKey(key)
            throw McpException(M.MCP_SESSION_EXPIRED)
        }
        return parse(url, resp)
    }

    private class Reply(val status: Int, val contentType: String?, val sessionId: String?, val body: String)

    private suspend fun post(url: String, headers: Map<String, String>, session: String?, body: JsonObject): Reply {
        val target = try {
            SafeUrl.check(url, policy).url
        } catch (e: SafeUrl.UnsafeUrlException) {
            log.warn("MCP {}: URL rejected: {}", hostOf(url), logSafe(e.message))
            throw McpException(M.MCP_CONNECT_FAILED)
        }
        return try {
            http.preparePost(target) {
                contentType(ContentType.Application.Json)
                header("Accept", "application/json, text/event-stream")
                header("MCP-Protocol-Version", "2025-06-18")
                session?.takeIf { it.isNotEmpty() }?.let { header("Mcp-Session-Id", it) }
                headers.forEach { (k, v) -> header(k, v) }
                setBody(body.toString())
            }.execute { resp ->
                val (bytes, truncated) = resp.bodyAsChannel().readCapped(MAX_RESPONSE_BYTES)
                if (truncated) {
                    log.warn("MCP {}: response of more than {} bytes", hostOf(url), MAX_RESPONSE_BYTES)
                    throw McpException(M.MCP_CONNECT_FAILED)
                }
                Reply(resp.status.value, resp.headers[HttpHeaders.ContentType], resp.headers["Mcp-Session-Id"], bytes.toString(Charsets.UTF_8))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            log.warn("MCP {}: could not connect: {}", hostOf(url), logSafe(e.message ?: e.javaClass.simpleName))
            throw McpException(M.MCP_CONNECT_FAILED)
        }
    }

    /** Accepts a direct JSON response or an SSE stream carrying the JSON-RPC message. */
    private fun parse(url: String, resp: Reply): JsonObject {
        if (resp.status !in 200..299) {
            log.warn("MCP {}: responded {}", hostOf(url), resp.status)
            throw McpException(M.MCP_CONNECT_FAILED)
        }
        val text = resp.body
        if (text.isBlank()) return JsonObject(emptyMap())
        val obj = runCatching {
            val json = if (resp.contentType?.contains("text/event-stream") == true) {
                text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.contains("\"result\"") || it.contains("\"error\"") } ?: "{}"
            } else text
            KairoJson.parseToJsonElement(json).jsonObject
        }.getOrElse {
            log.warn("MCP {}: response that is not JSON-RPC: {}", hostOf(url), logSafe(it.message))
            throw McpException(M.MCP_CONNECT_FAILED)
        }
        (obj["error"] as? JsonObject)?.let { err ->
            val code = (err["code"] as? JsonPrimitive)?.intOrNull
            log.warn("MCP {}: error {} {}", hostOf(url), code, logSafe((err["message"] as? JsonPrimitive)?.content))
            // Only the numeric JSON-RPC code (e.g. -32602 invalid params): the text can come from any service
            throw if (code != null) McpException(M.MCP_REJECTED_CODE, listOf(code)) else McpException(M.MCP_REJECTED)
        }
        return obj["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun hostOf(url: String): String = logSafe(runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: "?", 120)

    companion object {
        /** English text of the generic connection error (the user sees it in their own language). */
        val GENERIC_ERROR: String = M.MCP_CONNECT_FAILED.en
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        /**
         * `KAIRO_MCP_ALLOW_PRIVATE=true`: accepts MCP servers on the local / private network (Home Assistant on the LAN).
         * `KAIRO_MCP_ALLOWED_HOSTS=host1,host2`: exact hosts that are always accepted (even loopback or a docker service).
         */
        fun policyFromEnv(env: Map<String, String> = System.getenv()): SafeUrl.Policy = SafeUrl.Policy(
            allowPrivate = env["KAIRO_MCP_ALLOW_PRIVATE"]?.trim()?.toBooleanStrictOrNull() ?: false,
            trustedHosts = env["KAIRO_MCP_ALLOWED_HOSTS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )

        private fun urlPrefix(url: String) = url.trim() + "\u0000"

        /** Session per URL and headers: credentials travel in the headers, so it is not shared between users. */
        internal fun cacheKey(url: String, headers: Map<String, String>): String =
            urlPrefix(url) + Crypto.sha256(headers.toSortedMap().entries.joinToString("\u0000") { (k, v) -> "${k.lowercase()}\u0001$v" })
    }
}
