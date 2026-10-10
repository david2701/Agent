package app.kairo.agent

import app.kairo.domain.EntityRef
import app.kairo.domain.FileRef
import app.kairo.domain.User
import app.kairo.domain.UserSettings
import app.kairo.nan.FunctionSpec
import app.kairo.nan.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.util.UUID

data class GeoPoint(val latitude: Double, val longitude: Double)

/** Contexto de ejecución de una herramienta. */
class ToolContext(
    val user: User,
    val zone: ZoneId,
    val settings: UserSettings,
    /** Canal de origen: voice, chat, dashboard, share, briefing... (se guarda como `source`). */
    val source: String,
    val conversationId: UUID,
    val location: GeoPoint?,
    /** Capacidades del cliente: timer, alarm, location. */
    val capabilities: Set<String>,
    /** Archivos compartidos en este turno; se adjuntan a la primera nota que se cree. */
    val pendingAttachments: MutableList<UUID> = mutableListOf(),
) {
    val userId: UUID get() = user.id
}

data class ToolResult(
    /** Lo que ve el modelo. */
    val content: JsonElement,
    /** Lo que ve el usuario en la tarjeta de acción. */
    val summary: String,
    val ok: Boolean = true,
    val entity: EntityRef? = null,
    val deviceAction: JsonObject? = null,
    val attachment: FileRef? = null,
) {
    companion object {
        fun error(message: String) = ToolResult(buildJsonObject { put("error", message) }, message, ok = false)
    }
}

class Tool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    /** Texto corto que la UI muestra mientras corre ("Guardando nota"). */
    val label: String,
    val handler: suspend ToolContext.(JsonObject) -> ToolResult,
) {
    val spec: ToolSpec get() = ToolSpec(function = FunctionSpec(name, description, parameters))
}

/** DSL para declarar el JSON Schema de los parámetros. */
class ParamsBuilder {
    private val props = LinkedHashMap<String, JsonObject>()
    private val required = ArrayList<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) {
        props[name] = buildJsonObject {
            put("type", "string")
            put("description", description)
            enum?.let { e -> put("enum", buildJsonArray { e.forEach { add(JsonPrimitive(it)) } }) }
        }
        if (required) this.required += name
    }

    fun integer(name: String, description: String, required: Boolean = false) {
        props[name] = buildJsonObject { put("type", "integer"); put("description", description) }
        if (required) this.required += name
    }

    fun number(name: String, description: String, required: Boolean = false) {
        props[name] = buildJsonObject { put("type", "number"); put("description", description) }
        if (required) this.required += name
    }

    fun boolean(name: String, description: String, required: Boolean = false) {
        props[name] = buildJsonObject { put("type", "boolean"); put("description", description) }
        if (required) this.required += name
    }

    fun stringArray(name: String, description: String, required: Boolean = false) {
        props[name] = buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "string") })
        }
        if (required) this.required += name
    }

    fun intArray(name: String, description: String) {
        props[name] = buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject { put("type", "integer") })
        }
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props))
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

fun params(block: ParamsBuilder.() -> Unit): JsonObject = ParamsBuilder().apply(block).build()

class ToolRegistryBuilder {
    val tools = ArrayList<Tool>()

    fun tool(name: String, label: String, description: String, parameters: JsonObject, handler: suspend ToolContext.(JsonObject) -> ToolResult) {
        tools += Tool(name, description, parameters, label, handler)
    }
}
