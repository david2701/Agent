package app.kairo.agent

import app.kairo.db.Db
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.domain.McpServer
import app.kairo.integrations.McpClient
import app.kairo.util.logSafe
import app.kairo.util.Crypto
import app.kairo.util.KairoJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/** Servidores MCP configurados por el usuario; sus herramientas se suman a las del agente. */
class McpRegistry(private val db: Db, private val crypto: Crypto, private val client: McpClient) {
    private val log = LoggerFactory.getLogger(McpRegistry::class.java)

    private data class Row(val server: McpServer, val headers: Map<String, String>)

    suspend fun list(userId: UUID): List<McpServer> = rows(userId).map { it.server }

    private suspend fun rows(userId: UUID): List<Row> = db.read {
        query("select * from mcp_servers where user_id = ? order by name", userId) {
            val enc = it.strOrNull("headers")
            val headers = enc?.let { h -> runCatching { KairoJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), crypto.decrypt(h)) }.getOrNull() } ?: emptyMap()
            Row(McpServer(it.uuid("id"), it.str("name"), it.str("url"), it.getBoolean("enabled"), headers.isNotEmpty(), it.instant("created_at")), headers)
        }
    }

    suspend fun create(userId: UUID, name: String, url: String, headers: Map<String, String>): McpServer {
        // Protección SSRF (ver SafeUrl): sólo hosts públicos salvo KAIRO_MCP_ALLOW_PRIVATE / KAIRO_MCP_ALLOWED_HOSTS
        client.validateUrl(url.trim())
        require(name.none { it.isISOControl() }) { "El nombre no puede tener saltos de línea ni caracteres de control" }
        val headerName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}$") // token de RFC 9110
        require(headers.all { (k, v) -> headerName.matches(k) && v.none { it == '\r' || it == '\n' || it == '\u0000' } }) { "Cabeceras inválidas" }
        val enc = headers.takeIf { it.isNotEmpty() }?.let { crypto.encrypt(KairoJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), it)) }
        val id = db.tx { one("insert into mcp_servers(user_id, name, url, headers) values (?, ?, ?, ?) returning id", userId, name.trim(), url.trim(), enc) { it.uuid("id") }!! }
        return list(userId).first { it.id == id }
    }

    suspend fun update(userId: UUID, id: UUID, enabled: Boolean?): McpServer? {
        db.tx { exec("update mcp_servers set enabled = coalesce(?, enabled) where user_id = ? and id = ?", enabled, userId, id) }
        return list(userId).firstOrNull { it.id == id }
    }

    suspend fun delete(userId: UUID, id: UUID): Boolean {
        rows(userId).firstOrNull { it.server.id == id }?.let { client.invalidate(it.server.url) }
        return db.tx { exec("delete from mcp_servers where user_id = ? and id = ?", userId, id) } > 0
    }

    suspend fun test(userId: UUID, id: UUID): List<String> {
        val row = rows(userId).firstOrNull { it.server.id == id } ?: throw IllegalArgumentException("Servidor no encontrado")
        client.invalidate(row.server.url)
        return client.listTools(row.server.url, row.headers).map { it.name }
    }

    /** Herramientas MCP como herramientas del agente, con nombre `mcp_<servidor>_<herramienta>`. */
    suspend fun tools(userId: UUID): List<Tool> {
        val out = ArrayList<Tool>()
        for (row in rows(userId).filter { it.server.enabled }) {
            val remote = runCatching { client.listTools(row.server.url, row.headers) }
                .onFailure { log.warn("MCP {} no disponible: {}", logSafe(row.server.name, 60), logSafe(it.message)) }
                .getOrNull() ?: continue
            val prefix = "mcp_" + slug(row.server.name) + "_"
            remote.forEach { t ->
                val name = (prefix + slug(t.name)).take(64)
                out += Tool(
                    name = name,
                    description = "[${row.server.name}] ${t.description}".take(1000),
                    parameters = t.inputSchema,
                    label = "Usando ${row.server.name}",
                ) { args ->
                    val text = client.callTool(row.server.url, row.headers, t.name, args)
                    ToolResult(
                        buildJsonObject { put("result", JsonPrimitive(text)) }, "${row.server.name}: ${t.name}",
                        untrusted = "resultado del servidor externo ${row.server.name}",
                    )
                }
            }
        }
        return out
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
}
