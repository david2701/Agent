package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.Jsonb
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.nan.ChatMessage
import app.kairo.nan.ToolCall
import app.kairo.util.KairoJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.UUID

class ConversationsRepo(private val db: Db) {

    suspend fun list(userId: UUID, limit: Int = 50): List<Conversation> = db.read {
        query(
            """select c.*, (select content from messages m where m.conversation_id = c.id and m.role = 'user'
                 order by created_at desc limit 1) as preview
               from conversations c where c.user_id = ? order by c.updated_at desc limit ?""",
            userId, limit,
        ) { Conversation(it.uuid("id"), it.str("title"), it.instant("created_at"), it.instant("updated_at"), it.strOrNull("preview")?.take(140)) }
    }

    suspend fun get(userId: UUID, id: UUID): Conversation? = db.read {
        one("select * from conversations where user_id = ? and id = ?", userId, id) {
            Conversation(it.uuid("id"), it.str("title"), it.instant("created_at"), it.instant("updated_at"))
        }
    }

    suspend fun create(userId: UUID, title: String = ""): Conversation = db.tx {
        one("insert into conversations(user_id, title) values (?, ?) returning *", userId, title.take(120)) {
            Conversation(it.uuid("id"), it.str("title"), it.instant("created_at"), it.instant("updated_at"))
        }!!
    }

    suspend fun setTitle(id: UUID, title: String) = db.tx { exec("update conversations set title = ? where id = ?", title.take(120), id) }

    suspend fun delete(userId: UUID, id: UUID): Boolean = db.tx { exec("delete from conversations where user_id = ? and id = ?", userId, id) } > 0

    suspend fun append(conversationId: UUID, msg: ChatMessage, meta: JsonObject? = null): UUID = db.tx {
        exec("update conversations set updated_at = now() where id = ?", conversationId)
        one(
            """insert into messages(conversation_id, role, content, tool_calls, tool_call_id, tool_name, reasoning_content, meta)
               values (?, ?, ?, ?, ?, ?, ?, ?) returning id""",
            conversationId, msg.role, msg.text,
            msg.toolCalls?.let { Jsonb(KairoJson.encodeToString(ListSerializer(ToolCall.serializer()), it)) },
            msg.toolCallId, msg.name, msg.reasoningContent, meta?.let { Jsonb(it.toString()) },
        ) { it.uuid("id") }!!
    }

    /** Mensajes visibles en la UI (usuario, asistente y tarjetas de acciones). */
    suspend fun messages(userId: UUID, conversationId: UUID, limit: Int = 200): List<StoredMessage> = db.read {
        query(
            """select m.* from messages m join conversations c on c.id = m.conversation_id
               where c.user_id = ? and m.conversation_id = ? order by m.created_at limit ?""",
            userId, conversationId, limit,
        ) {
            StoredMessage(
                id = it.uuid("id"),
                role = it.str("role"),
                content = it.str("content"),
                toolName = it.strOrNull("tool_name"),
                meta = it.strOrNull("meta")?.let { m -> KairoJson.parseToJsonElement(m).jsonObject },
                createdAt = it.instant("created_at"),
            )
        }.filter { it.role != "assistant" || it.content.isNotBlank() }
    }

    /**
     * Historial para el modelo: últimos mensajes, comenzando siempre en un mensaje de usuario para
     * no cortar una secuencia assistant(tool_calls) -> tool.
     */
    suspend fun history(conversationId: UUID, maxMessages: Int = 24): List<ChatMessage> {
        val rows = db.read {
            query(
                "select * from (select * from messages where conversation_id = ? order by created_at desc limit ?) t order by created_at",
                conversationId, maxMessages,
            ) {
                val calls = it.strOrNull("tool_calls")?.let { j -> KairoJson.decodeFromString(ListSerializer(ToolCall.serializer()), j) }
                ChatMessage(
                    role = it.str("role"),
                    content = JsonPrimitive(it.str("content")),
                    toolCalls = calls?.takeIf { c -> c.isNotEmpty() },
                    toolCallId = it.strOrNull("tool_call_id"),
                    name = it.strOrNull("tool_name"),
                )
            }
        }
        val start = rows.indexOfFirst { it.role == "user" }
        return if (start < 0) emptyList() else rows.drop(start)
    }
}
