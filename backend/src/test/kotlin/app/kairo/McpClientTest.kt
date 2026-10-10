package app.kairo

import app.kairo.integrations.McpClient
import app.kairo.integrations.McpException
import app.kairo.util.SafeUrl
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class McpClientTest {
    private lateinit var server: HttpServer
    private var port = 0
    /** Requests received: "path jsonrpc-method session authorization". */
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    private fun HttpExchange.reply(status: Int, body: String, type: String = "application/json", headers: Map<String, String> = emptyMap()) {
        responseHeaders.add("Content-Type", type)
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) } else close()
    }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        port = server.address.port
        var sessions = 0
        server.createContext("/mcp") { ex ->
            val req = ex.requestBody.readAllBytes().decodeToString()
            val method = Regex("\"method\":\"([^\"]+)\"").find(req)?.groupValues?.get(1)
            log += "/mcp $method ${ex.requestHeaders.getFirst("Mcp-Session-Id")} ${ex.requestHeaders.getFirst("Authorization")}"
            when (method) {
                "initialize" -> ex.reply(200, """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""", headers = mapOf("Mcp-Session-Id" to "s${++sessions}"))
                "notifications/initialized" -> ex.reply(202, "")
                "tools/list" -> ex.reply(
                    200,
                    "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"lights\",\"description\":\"Turns on\"}]}}\n\n",
                    type = "text/event-stream",
                )
                "tools/call" -> ex.reply(200, """{"jsonrpc":"2.0","id":3,"error":{"code":-32602,"message":"internal secret: /etc/shadow"}}""")
                else -> ex.reply(400, "")
            }
        }
        server.createContext("/redirect") { ex ->
            log += "/redirect"
            ex.responseHeaders.add("Location", "http://127.0.0.1:$port/mcp")
            ex.sendResponseHeaders(307, -1)
            ex.close()
        }
        server.createContext("/error") { ex ->
            log += "/error"
            ex.reply(500, "Traceback: password=hunter2 at 10.0.0.5")
        }
        server.createContext("/huge") { ex ->
            log += "/huge"
            ex.sendResponseHeaders(200, 0)
            runCatching { ex.responseBody.use { out -> val chunk = ByteArray(64 * 1024) { ' '.code.toByte() }; repeat(48) { out.write(chunk) } } }
        }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    // Without the environment proxy: the test server is local
    private val trusted = McpClient(SafeUrl.Policy(trustedHosts = setOf("127.0.0.1")), outboundProxy = null)

    @Test
    fun `lists tools and separates sessions by credentials`() = runBlocking {
        val url = "http://127.0.0.1:$port/mcp"
        val a = trusted.listTools(url, mapOf("Authorization" to "Bearer A"))
        assertEquals(listOf("lights"), a.map { it.name })
        // Another user with the same URL but other credentials: own session, it does not reuse the first one's
        trusted.listTools(url, mapOf("Authorization" to "Bearer B"))
        val inits = log.filter { it.contains(" initialize ") }
        assertEquals(2, inits.size)
        assertTrue(log.any { it.startsWith("/mcp tools/list s1 Bearer A") })
        assertTrue(log.any { it.startsWith("/mcp tools/list s2 Bearer B") })
        assertFalse(log.any { it.contains("s1 Bearer B") || it.contains("s2 Bearer A") })
        // Cache per credentials: repeating does not call the server again
        val before = log.size
        trusted.listTools(url, mapOf("Authorization" to "Bearer A"))
        assertEquals(before, log.size)
    }

    @Test
    fun `errors do not expose server details`() = runBlocking {
        val err = assertFailsWith<McpException> { trusted.listTools("http://127.0.0.1:$port/error", emptyMap()) }
        assertEquals(McpClient.GENERIC_ERROR, err.message)
        val closed = assertFailsWith<McpException> { trusted.listTools("http://127.0.0.1:1/mcp", emptyMap()) }
        assertEquals(McpClient.GENERIC_ERROR, closed.message)
        val rpc = assertFailsWith<McpException> { trusted.callTool("http://127.0.0.1:$port/mcp", emptyMap(), "lights", kotlinx.serialization.json.JsonObject(emptyMap())) }
        assertFalse(rpc.message!!.contains("secret"))
        assertTrue(rpc.message!!.contains("-32602"))
    }

    @Test
    fun `does not follow redirects`() = runBlocking {
        val err = assertFailsWith<McpException> { trusted.listTools("http://127.0.0.1:$port/redirect", emptyMap()) }
        assertEquals(McpClient.GENERIC_ERROR, err.message)
        assertEquals(listOf("/redirect"), log.toList())
    }

    @Test
    fun `huge responses are cut`() = runBlocking {
        val err = assertFailsWith<McpException> { trusted.listTools("http://127.0.0.1:$port/huge", emptyMap()) }
        assertEquals(McpClient.GENERIC_ERROR, err.message)
    }

    @Test
    fun `without permission it does not connect to local addresses`() = runBlocking {
        val strict = McpClient(SafeUrl.Policy(), outboundProxy = null)
        assertFailsWith<McpException> { strict.listTools("http://127.0.0.1:$port/mcp", emptyMap()) }
        assertFailsWith<IllegalArgumentException> { strict.validateUrl("http://192.168.1.20:8123/mcp_server/sse") }
        assertFailsWith<IllegalArgumentException> { strict.validateUrl("ftp://8.8.8.8/") }
        strict.validateUrl("https://8.8.8.8/mcp")
        val lan = McpClient(McpClient.policyFromEnv(mapOf("KAIRO_MCP_ALLOW_PRIVATE" to "true")), outboundProxy = null)
        lan.validateUrl("http://192.168.1.20:8123/mcp_server/sse")
        val e = assertFailsWith<IllegalArgumentException> { lan.validateUrl("http://127.0.0.1:8123/") }
        assertFalse(e.message!!.contains("KAIRO_MCP_ALLOW_PRIVATE"))
        assertFailsWith<IllegalArgumentException> { lan.validateUrl("http://db:5432/") }
        assertTrue(log.isEmpty())
    }

    @Test
    fun `policy from the environment`() {
        val p = McpClient.policyFromEnv(mapOf("KAIRO_MCP_ALLOW_PRIVATE" to "true", "KAIRO_MCP_ALLOWED_HOSTS" to " mcp-notion , LocalHost "))
        assertTrue(p.allowPrivate)
        assertTrue(p.isTrusted("mcp-notion"))
        assertTrue(p.isTrusted("localhost"))
        assertFalse(McpClient.policyFromEnv(emptyMap()).allowPrivate)
        assertFalse(McpClient.policyFromEnv(mapOf("KAIRO_MCP_ALLOW_PRIVATE" to "yes")).allowPrivate)
    }

    @Test
    fun `session key depends on url and headers`() {
        val url = "https://mcp.example.com/"
        assertEquals(McpClient.cacheKey(url, mapOf("A" to "1", "B" to "2")), McpClient.cacheKey(url, mapOf("B" to "2", "A" to "1")))
        assertNotEquals(McpClient.cacheKey(url, mapOf("Authorization" to "x")), McpClient.cacheKey(url, mapOf("Authorization" to "y")))
        assertNotEquals(McpClient.cacheKey(url, emptyMap()), McpClient.cacheKey("https://other.example.com/", emptyMap()))
    }
}
