package app.kairo.agent

import app.kairo.domain.EntityRef
import app.kairo.domain.FileRef
import app.kairo.domain.Member
import app.kairo.domain.Text
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
    /** Miembros de la familia (incluido el usuario). */
    val familyMembers: List<Member> = emptyList(),
) {
    val userId: UUID get() = user.id

    /**
     * El turno ya leyó contenido que pudo escribir otra persona o que viene de fuera (notas y listas
     * familiares, recordatorios que te programaron, Google Calendar, páginas compartidas, MCP).
     * Desde entonces las acciones sensibles no se ejecutan sin la confirmación de la persona (ver [ToolPolicy]).
     */
    var tainted: Boolean = false
        private set
    var taintReason: String? = null
        private set

    fun taint(reason: String) {
        if (!tainted) {
            tainted = true
            taintReason = reason
        }
    }

    /** Si no sabemos quién está en la familia, se asume que hay más gente (lo seguro). */
    val hasOthers: Boolean get() = familyMembers.isEmpty() || familyMembers.any { it.id != userId }

    /**
     * El contenido pudo escribirlo alguien que no es el usuario: es de otro miembro, es familiar
     * (lo edita cualquiera en casa) o llegó de fuera (compartido desde otra app, Google Calendar).
     */
    fun fromOthers(ownerId: UUID?, shared: Boolean, source: String? = null): Boolean =
        (ownerId != null && ownerId != userId) || (shared && hasOthers) || source in EXTERNAL_SOURCES

    /** Nombre corto de un miembro, o null si es el propio usuario. */
    fun memberName(id: UUID?): String? = id?.takeIf { it != userId }?.let { m ->
        familyMembers.firstOrNull { it.id == m }?.displayName?.let { Untrusted.clean(it, 60).split(' ').firstOrNull() }?.ifEmpty { null }
            ?: "otro miembro"
    }

    /** Resuelve un nombre dicho por el usuario ("Vale", "Sofía", "yo"). */
    fun findMember(name: String): Member? {
        val n = Text.normalize(name)
        if (n in setOf("yo", "mi", "me", "a mi")) return familyMembers.firstOrNull { it.id == userId }
        familyMembers.firstOrNull { Text.normalize(it.displayName) == n }?.let { return it }
        val first = familyMembers.filter { Text.normalize(it.displayName).split(' ').first() == n.split(' ').first() }
        if (first.size == 1) return first[0]
        return familyMembers.filter { Text.normalize(it.displayName).startsWith(n) }.singleOrNull()
    }

    companion object {
        /** Orígenes cuyo texto no escribió el usuario: "Compartir con Kairo" (web, imágenes) y Google Calendar. */
        val EXTERNAL_SOURCES = setOf("share", "google")
    }
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
    /**
     * Si no es null, el resultado trae contenido de otras personas o externo (el motivo, en español):
     * el modelo lo recibe entre delimitadores de datos y el turno queda marcado ([ToolContext.taint]).
     */
    val untrusted: String? = null,
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
