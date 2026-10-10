package app.kairo.integrations

import app.kairo.util.proxyFromEnv
import app.kairo.util.KairoJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class McpTool(val name: String, val description: String, val inputSchema: JsonObject)

class McpException(message: String) : RuntimeException(message)

/**
 * Cliente mínimo de Model Context Protocol sobre "Streamable HTTP" (JSON-RPC 2.0).
 * Permite conectar servidores MCP remotos (Notion, Home Assistant, etc.) como herramientas del agente.
 */
class McpClient {
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
        expectSuccess = false
    }
    private val ids = AtomicInteger(1)
    private val sessions = ConcurrentHashMap<String, String>()
    private val toolCache = ConcurrentHashMap<String, Pair<Long, List<McpTool>>>()

    suspend fun listTools(url: String, headers: Map<String, String>): List<McpTool> {
        toolCache[url]?.let { (at, tools) -> if (System.currentTimeMillis() - at < 5 * 60_000) return tools }
        ensureSession(url, headers)
        val result = rpc(url, headers, "tools/list", JsonObject(emptyMap()))
        val tools = (result["tools"] as? JsonArray)?.map { t ->
            val o = t.jsonObject
            McpTool(
                name = o["name"]!!.jsonPrimitive.content,
                description = (o["description"] as? JsonPrimitive)?.content ?: "",
                inputSchema = (o["inputSchema"] as? JsonObject) ?: buildJsonObject { put("type", "object") },
            )
        } ?: emptyList()
        toolCache[url] = System.currentTimeMillis() to tools
        return tools
    }

    suspend fun callTool(url: String, headers: Map<String, String>, name: String, arguments: JsonObject): String {
        ensureSession(url, headers)
        val result = rpc(url, headers, "tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        val content = (result["content"] as? JsonArray)?.joinToString("\n") { c ->
            val o = c.jsonObject
            when ((o["type"] as? JsonPrimitive)?.content) {
                "text" -> o["text"]?.jsonPrimitive?.content ?: ""
                else -> o.toString()
            }
        } ?: result.toString()
        return content.take(12_000)
    }

    fun invalidate(url: String) {
        sessions.remove(url)
        toolCache.remove(url)
    }

    private suspend fun ensureSession(url: String, headers: Map<String, String>) {
        if (sessions.containsKey(url)) return
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
        parse(resp)
        sessions[url] = resp.headers["Mcp-Session-Id"] ?: ""
        post(url, headers, sessions[url], buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        })
    }

    private suspend fun rpc(url: String, headers: Map<String, String>, method: String, params: JsonObject): JsonObject {
        val resp = post(url, headers, sessions[url], buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.getAndIncrement())
            put("method", method)
            put("params", params)
        })
        if (resp.status.value == 404) {
            invalidate(url)
            throw McpException("La sesión MCP expiró, vuelve a intentarlo")
        }
        return parse(resp)
    }

    private suspend fun post(url: String, headers: Map<String, String>, session: String?, body: JsonObject): HttpResponse =
        http.post(url) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("MCP-Protocol-Version", "2025-06-18")
            session?.takeIf { it.isNotEmpty() }?.let { header("Mcp-Session-Id", it) }
            headers.forEach { (k, v) -> header(k, v) }
            setBody(body.toString())
        }

    /** Acepta respuesta JSON directa o un stream SSE con el mensaje JSON-RPC. */
    private suspend fun parse(resp: HttpResponse): JsonObject {
        if (!resp.status.isSuccess()) throw McpException("Servidor MCP respondió ${resp.status.value}")
        val text = resp.bodyAsText()
        if (text.isBlank()) return JsonObject(emptyMap())
        val json = if (resp.headers["Content-Type"]?.contains("text/event-stream") == true) {
            text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                .lastOrNull { it.contains("\"result\"") || it.contains("\"error\"") } ?: "{}"
        } else text
        val obj = KairoJson.parseToJsonElement(json).jsonObject
        (obj["error"] as? JsonObject)?.let { throw McpException((it["message"] as? JsonPrimitive)?.content ?: "Error MCP") }
        return obj["result"]?.jsonObject ?: JsonObject(emptyMap())
    }
}
