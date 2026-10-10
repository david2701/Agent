package app.kairo.agent

import app.kairo.domain.EntityRef
import app.kairo.domain.FileRef
import app.kairo.domain.Member
import app.kairo.domain.Text
import app.kairo.domain.User
import app.kairo.domain.UserSettings
import app.kairo.util.Lang
import app.kairo.util.Localizable
import app.kairo.util.M
import app.kairo.util.Say
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

/** Execution context of a tool. */
class ToolContext(
    val user: User,
    val zone: ZoneId,
    val settings: UserSettings,
    /** Source channel: voice, chat, dashboard, share, briefing... (stored as `source`). */
    val source: String,
    val conversationId: UUID,
    val location: GeoPoint?,
    /** Client capabilities: timer, alarm, location. */
    val capabilities: Set<String>,
    /** Files shared in this turn; they are attached to the first note that gets created. */
    val pendingAttachments: MutableList<UUID> = mutableListOf(),
    /** Family members (the user included). */
    val familyMembers: List<Member> = emptyList(),
    /** Language of the texts the user sees (tool cards, errors, confirmations). */
    val lang: Lang = Lang.DEFAULT,
) {
    val userId: UUID get() = user.id

    /** A catalog message in the user's language. */
    fun t(msg: M, vararg args: Any?): String = Say(msg, args.toList()).text(lang)

    /**
     * The turn has already read content that someone else may have written or that comes from outside (family
     * notes and lists, reminders set for you, Google Calendar, shared pages, MCP).
     * From then on, sensitive actions are not executed without the person's confirmation (see [ToolPolicy]).
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

    /** If we do not know who is in the family, assume there are more people (the safe choice). */
    val hasOthers: Boolean get() = familyMembers.isEmpty() || familyMembers.any { it.id != userId }

    /**
     * The content may have been written by someone who is not the user: it belongs to another member, it is a
     * family item (anyone at home can edit it) or it came from outside (shared from another app, Google Calendar).
     */
    fun fromOthers(ownerId: UUID?, shared: Boolean, source: String? = null): Boolean =
        (ownerId != null && ownerId != userId) || (shared && hasOthers) || source in EXTERNAL_SOURCES

    /** Short name of a member, or null if it is the user. */
    fun memberName(id: UUID?): String? = id?.takeIf { it != userId }?.let { m ->
        familyMembers.firstOrNull { it.id == m }?.displayName?.let { Untrusted.clean(it, 60).split(' ').firstOrNull() }?.ifEmpty { null }
            ?: t(M.ANOTHER_MEMBER)
    }

    /** Resolves a name the user said ("Vale", "Mia", "me", "yo"). */
    fun findMember(name: String): Member? {
        val n = Text.normalize(name)
        if (n in SELF_NAMES) return familyMembers.firstOrNull { it.id == userId }
        familyMembers.firstOrNull { Text.normalize(it.displayName) == n }?.let { return it }
        val first = familyMembers.filter { Text.normalize(it.displayName).split(' ').first() == n.split(' ').first() }
        if (first.size == 1) return first[0]
        return familyMembers.filter { Text.normalize(it.displayName).startsWith(n) }.singleOrNull()
    }

    companion object {
        /** Sources whose text the user did not write: "Save to Kairo" (web, images) and Google Calendar. */
        val EXTERNAL_SOURCES = setOf("share", "google")

        /** The ways a person refers to themselves (English and Spanish), normalized. */
        private val SELF_NAMES = setOf("me", "myself", "i", "yo", "mi", "a mi")
    }
}

data class ToolResult(
    /** What the model sees. */
    val content: JsonElement,
    /** What the user sees on the action card. */
    val summary: String,
    val ok: Boolean = true,
    val entity: EntityRef? = null,
    val deviceAction: JsonObject? = null,
    val attachment: FileRef? = null,
    /**
     * If not null, the result carries content from other people or from outside (the reason, in English):
     * the model receives it between data delimiters and the turn is marked ([ToolContext.taint]).
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
    /** Short text the UI shows while it runs ("Saving note"), rendered in the user's language. */
    val label: Localizable,
    val handler: suspend ToolContext.(JsonObject) -> ToolResult,
) {
    val spec: ToolSpec get() = ToolSpec(function = FunctionSpec(name, description, parameters))
}

/** DSL to declare the JSON Schema of the parameters. */
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

    fun tool(name: String, label: M, description: String, parameters: JsonObject, handler: suspend ToolContext.(JsonObject) -> ToolResult) {
        tools += Tool(name, description, parameters, Say(label), handler)
    }
}
