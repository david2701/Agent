package app.kairo.integrations

import app.kairo.util.Crypto
import app.kairo.util.KairoJson
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

/** Error de MCP. El mensaje se muestra al usuario y al modelo: nunca lleva detalles del servidor remoto. */
class McpException(message: String) : RuntimeException(message)

/**
 * Cliente mínimo de Model Context Protocol sobre "Streamable HTTP" (JSON-RPC 2.0).
 * Permite conectar servidores MCP remotos (Notion, Home Assistant, etc.) como herramientas del agente.
 *
 * Seguridad:
 * - Cada petición valida la URL con [SafeUrl]: sólo hosts públicos, salvo `KAIRO_MCP_ALLOW_PRIVATE=true`
 *   (servidores en la LAN, como Home Assistant) y los hosts de `KAIRO_MCP_ALLOWED_HOSTS`.
 * - No sigue redirecciones (un 3xx es un error).
 * - Las respuestas se leen con un límite de [MAX_RESPONSE_BYTES].
 * - Los errores que ve el usuario son genéricos (no sirven para escanear puertos); el detalle va sólo al log.
 * - Las sesiones y la caché de herramientas se separan por URL y cabeceras (credenciales), así dos usuarios
 *   con la misma URL nunca comparten la sesión que abrió el otro.
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
     * Comprueba una URL antes de guardarla. Lanza IllegalArgumentException con un mensaje para el usuario.
     */
    suspend fun validateUrl(url: String) {
        try {
            SafeUrl.check(url, policy)
        } catch (e: SafeUrl.UnsafeUrlException) {
            if (!e.blockedAddress) throw IllegalArgumentException(e.message)
            throw IllegalArgumentException(
                if (policy.allowPrivate) {
                    "Esa dirección no está permitida para servidores MCP"
                } else {
                    "Esa URL apunta a la red local o no se puede resolver. Para un servidor de tu red (p. ej. Home Assistant), " +
                        "el administrador debe activar KAIRO_MCP_ALLOW_PRIVATE=true en el servidor"
                },
            )
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
            log.warn("MCP {}: lista de herramientas inválida: {}", hostOf(url), logSafe(it.message))
            throw McpException(GENERIC_ERROR)
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

    /** Olvida las sesiones y herramientas de [url] (de cualquier usuario o credenciales). */
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
            throw McpException("La sesión MCP expiró, vuelve a intentarlo")
        }
        return parse(url, resp)
    }

    private class Reply(val status: Int, val contentType: String?, val sessionId: String?, val body: String)

    private suspend fun post(url: String, headers: Map<String, String>, session: String?, body: JsonObject): Reply {
        val target = try {
            SafeUrl.check(url, policy).url
        } catch (e: SafeUrl.UnsafeUrlException) {
            log.warn("MCP {}: URL rechazada: {}", hostOf(url), logSafe(e.message))
            throw McpException(GENERIC_ERROR)
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
                    log.warn("MCP {}: respuesta de más de {} bytes", hostOf(url), MAX_RESPONSE_BYTES)
                    throw McpException(GENERIC_ERROR)
                }
                Reply(resp.status.value, resp.headers[HttpHeaders.ContentType], resp.headers["Mcp-Session-Id"], bytes.toString(Charsets.UTF_8))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            log.warn("MCP {}: no se pudo conectar: {}", hostOf(url), logSafe(e.message ?: e.javaClass.simpleName))
            throw McpException(GENERIC_ERROR)
        }
    }

    /** Acepta respuesta JSON directa o un stream SSE con el mensaje JSON-RPC. */
    private fun parse(url: String, resp: Reply): JsonObject {
        if (resp.status !in 200..299) {
            log.warn("MCP {}: respondió {}", hostOf(url), resp.status)
            throw McpException(GENERIC_ERROR)
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
            log.warn("MCP {}: respuesta que no es JSON-RPC: {}", hostOf(url), logSafe(it.message))
            throw McpException(GENERIC_ERROR)
        }
        (obj["error"] as? JsonObject)?.let { err ->
            val code = (err["code"] as? JsonPrimitive)?.intOrNull
            log.warn("MCP {}: error {} {}", hostOf(url), code, logSafe((err["message"] as? JsonPrimitive)?.content))
            // Sólo el código numérico de JSON-RPC (p. ej. -32602 parámetros inválidos): el texto puede venir de cualquier servicio
            throw McpException("El servidor MCP rechazó la petición" + (code?.let { " (código $it)" } ?: ""))
        }
        return obj["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun hostOf(url: String): String = logSafe(runCatching { java.net.URI(url.trim()).host }.getOrNull() ?: "?", 120)

    companion object {
        const val GENERIC_ERROR = "No se pudo conectar con el servidor MCP"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        /**
         * `KAIRO_MCP_ALLOW_PRIVATE=true`: acepta servidores MCP en la red local / privada (Home Assistant en la LAN).
         * `KAIRO_MCP_ALLOWED_HOSTS=host1,host2`: hosts exactos que se aceptan siempre (incluso loopback o un servicio de docker).
         */
        fun policyFromEnv(env: Map<String, String> = System.getenv()): SafeUrl.Policy = SafeUrl.Policy(
            allowPrivate = env["KAIRO_MCP_ALLOW_PRIVATE"]?.trim()?.toBooleanStrictOrNull() ?: false,
            trustedHosts = env["KAIRO_MCP_ALLOWED_HOSTS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )

        private fun urlPrefix(url: String) = url.trim() + "\u0000"

        /** Sesión por URL y cabeceras: las credenciales van en las cabeceras, así que no se comparte entre usuarios. */
        internal fun cacheKey(url: String, headers: Map<String, String>): String =
            urlPrefix(url) + Crypto.sha256(headers.toSortedMap().entries.joinToString("\u0000") { (k, v) -> "${k.lowercase()}\u0001$v" })
    }
}
