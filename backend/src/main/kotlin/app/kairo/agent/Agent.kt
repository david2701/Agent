@file:UseSerializers(UUIDSerializer::class)

package app.kairo.agent

import app.kairo.domain.ConversationsRepo
import app.kairo.domain.EntityRef
import app.kairo.domain.FamilyRepo
import app.kairo.domain.FileRef
import app.kairo.domain.ListsRepo
import app.kairo.domain.MemoriesRepo
import app.kairo.domain.PlacesRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.User
import app.kairo.nan.ChatChunk
import app.kairo.nan.ChatMessage
import app.kairo.nan.ChatOptions
import app.kairo.nan.ChatResult
import app.kairo.nan.NanClient
import app.kairo.nan.NanException
import app.kairo.nan.ToolCall
import app.kairo.nan.ToolCallFunction
import app.kairo.nan.ToolSpec
import app.kairo.util.KairoJson
import app.kairo.util.UUIDSerializer
import app.kairo.util.zoneOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/** Eventos que el agente emite por SSE a los clientes. El campo `type` los distingue. */
@Serializable
sealed class AgentEvent {
    @Serializable @SerialName("start")
    data class Start(val conversationId: UUID) : AgentEvent()

    @Serializable @SerialName("transcript")
    data class Transcript(val text: String) : AgentEvent()

    @Serializable @SerialName("delta")
    data class Delta(val text: String, val step: Int) : AgentEvent()

    @Serializable @SerialName("tool_start")
    data class ToolStart(val callId: String, val name: String, val label: String) : AgentEvent()

    @Serializable @SerialName("tool_end")
    data class ToolEnd(val callId: String, val name: String, val ok: Boolean, val summary: String, val entity: EntityRef? = null) : AgentEvent()

    @Serializable @SerialName("device_action")
    data class DeviceAction(val action: JsonObject) : AgentEvent()

    @Serializable @SerialName("attachment")
    data class Attachment(val file: FileRef) : AgentEvent()

    @Serializable @SerialName("done")
    data class Done(val conversationId: UUID, val messageId: UUID, val text: String, val spoken: String) : AgentEvent()

    @Serializable @SerialName("error")
    data class Error(val message: String) : AgentEvent()
}

data class AgentRequest(
    val user: User,
    val text: String,
    val conversationId: UUID?,
    val channel: String,
    val location: GeoPoint? = null,
    val capabilities: Set<String> = emptySet(),
    /** Contexto adicional que ve el modelo pero no se muestra como mensaje del usuario (p. ej. texto extraído de una imagen). */
    val hiddenContext: String? = null,
    val displayText: String? = null,
    val attachments: List<FileRef> = emptyList(),
)

/**
 * Bucle del agente: modelo de Nan con tool calling en streaming, hasta [MAX_STEPS] rondas.
 * Persiste toda la conversación (incluidas las llamadas a herramientas) para mantener el contexto.
 *
 * Contra la inyección de instrucciones: si el turno leyó contenido de otras personas o externo
 * ([ToolContext.tainted]), las acciones sensibles quedan pendientes en [confirmations] y solo se
 * ejecutan si el siguiente mensaje humano de la conversación es un «sí» ([Affirmative]).
 */
class Agent(
    private val nan: NanClient,
    private val builtin: BuiltinTools,
    private val mcp: McpRegistry,
    private val conversations: ConversationsRepo,
    private val memories: MemoriesRepo,
    private val places: PlacesRepo,
    private val lists: ListsRepo,
    private val settings: SettingsService,
    private val family: FamilyRepo,
    private val confirmations: Confirmations = Confirmations(),
) {
    private val log = LoggerFactory.getLogger(Agent::class.java)
    private val builtinTools = builtin.all()

    fun run(req: AgentRequest): Flow<AgentEvent> = channelFlow {
        val user = req.user
        val zone = zoneOf(user.timezone)
        try {
            val s = settings.get(user.id)
            val conv = req.conversationId?.let { conversations.get(user.id, it) }
                ?: conversations.create(user.id, (req.displayText ?: req.text).lineSequence().first().take(60))
            send(AgentEvent.Start(conv.id))

            val (ctxData, extra) = coroutineScope {
                val m = async { runCatching { memories.relevant(user.id, req.text) }.getOrDefault(emptyList()) }
                val p = async { places.list(user.id) }
                val l = async { lists.all(user.id) }
                val f = async { runCatching { family.info(user.id) }.getOrNull() }
                val x = async { runCatching { mcp.tools(user.id) }.getOrDefault(emptyList()) }
                Quad(m.await(), p.await(), l.await(), f.await()) to x.await()
            }
            val (mems, plcs, lsts, fam) = ctxData
            val tools = builtinTools + extra
            val byName = tools.associateBy { it.name }
            val specs: List<ToolSpec> = tools.map { it.spec }

            val history = conversations.history(conv.id)
            // Lo pendiente de confirmación sale siempre con este mensaje: o es un «sí» humano, o se descarta.
            val pending = confirmations.take(conv.id, user.id)
            val confirmed = pending.isNotEmpty() && isHumanReply(req) && Affirmative.matches(req.text)
            if (pending.isNotEmpty() && !confirmed) log.info("Acciones pendientes descartadas en {}: {}", conv.id, pending.map { it.tool })
            val userText = buildString {
                append(req.text)
                req.hiddenContext?.let { append("\n\n[Contexto adjunto]\n").append(Untrusted.wrap(it)) }
            }
            conversations.append(
                conv.id, ChatMessage.user(req.displayText ?: req.text),
                meta = buildJsonObject {
                    put("channel", req.channel)
                    if (req.attachments.isNotEmpty()) put("attachments", KairoJson.encodeToJsonElement(req.attachments))
                    req.hiddenContext?.let { put("context", it.take(4000)) }
                },
            )
            val messages = ArrayList<ChatMessage>()
            messages += ChatMessage.system(SystemPrompt.build(user, zone, req.channel, mems, plcs, lsts, fam))
            messages += history
            messages += ChatMessage.user(userText)

            val ctx = ToolContext(
                user, zone, s, req.channel, conv.id, req.location, req.capabilities, req.attachments.map { it.id }.toMutableList(),
                familyMembers = fam?.members.orEmpty(),
            )
            // Contenido externo en este mensaje, o datos ajenos que el modelo aún ve en el historial.
            if (req.hiddenContext != null) ctx.taint("contenido compartido desde otra app, una página web o una imagen")
            if (history.any { it.role == "tool" && it.text.contains(Untrusted.OPEN) }) ctx.taint(ToolPolicy.DEFAULT_TAINT_REASON)
            val opts = ChatOptions(
                model = s.chatModel,
                maxTokens = if (req.channel == "voice") 700 else 1800,
                reasoningEffort = if (s.fastMode || req.channel == "voice") "none" else null,
            )
            /** Resúmenes de lo que quedó pendiente en este turno; el servidor los añade a la respuesta. */
            val pendingNow = ArrayList<String>()

            suspend fun runCall(call: ToolCall, confirmedByUser: Boolean, destructiveInStep: Int) {
                val tool = byName[call.function.name]
                send(AgentEvent.ToolStart(call.id, call.function.name, tool?.label ?: "Trabajando"))
                val res = if (confirmedByUser) execute(tool, call, ctx) else guarded(tool, call, ctx, destructiveInStep, pendingNow)
                send(AgentEvent.ToolEnd(call.id, call.function.name, res.ok, res.summary, res.entity))
                res.deviceAction?.let { send(AgentEvent.DeviceAction(it)) }
                res.attachment?.let { send(AgentEvent.Attachment(it)) }
                res.untrusted?.let { ctx.taint(it) }
                // El modelo ve lo ajeno entre delimitadores; las tarjetas de la UI salen de `meta`, que no cambia.
                val content = res.content.toString().let { if (res.untrusted != null) Untrusted.wrap(it) else it }
                val toolMsg = ChatMessage.tool(call.id, call.function.name, content)
                messages += toolMsg
                conversations.append(
                    conv.id, toolMsg,
                    meta = buildJsonObject {
                        put("summary", res.summary)
                        put("ok", res.ok)
                        res.entity?.let { put("entity", KairoJson.encodeToJsonElement(it)) }
                        res.attachment?.let { put("attachment", KairoJson.encodeToJsonElement(it)) }
                        if (confirmedByUser) put("confirmed", true)
                    },
                )
            }

            if (confirmed) {
                // La persona dijo «sí»: el servidor ejecuta lo guardado (no el modelo) y el agente sigue con ese contexto.
                val calls = pending.map { ToolCall(newCallId(), function = ToolCallFunction(it.tool, it.args.toString())) }
                val msg = ChatMessage("assistant", JsonPrimitive(""), calls)
                messages += msg
                conversations.append(conv.id, msg.copy(content = null))
                log.info("Acciones confirmadas por {} en {}: {}", user.id, conv.id, pending.map { it.tool })
                calls.forEach { runCall(it, confirmedByUser = true, destructiveInStep = 1) }
            }

            var finalText = ""
            var step = 0
            while (true) {
                val forceAnswer = step >= MAX_STEPS
                val result = streamStep(messages, if (forceAnswer) emptyList() else specs, opts, s.fallbackModel) { delta ->
                    send(AgentEvent.Delta(delta, step))
                }
                val msg = result.message
                val calls = msg.toolCalls.orEmpty()
                if (calls.isEmpty() || forceAnswer) {
                    finalText = tidy(msg.text)
                    break
                }
                messages += msg
                // El texto previo a las herramientas ("voy a revisar...") no aporta al historial y a veces sale en inglés.
                conversations.append(conv.id, msg.copy(content = null, reasoningContent = null))
                val destructive = calls.count { c -> parseArgs(c)?.let { ToolPolicy.isDestructive(c.function.name, it) } == true }
                for (call in calls) runCall(call, confirmedByUser = false, destructiveInStep = destructive)
                step++
            }
            // La pregunta de confirmación la redacta el servidor: no depende de lo que diga el modelo.
            if (pendingNow.isNotEmpty()) {
                val ask = Confirmations.prompt(pendingNow)
                finalText = if (finalText.isEmpty()) ask else "$finalText\n\n$ask"
            }
            if (finalText.isEmpty()) finalText = "Listo."
            val id = conversations.append(conv.id, ChatMessage.assistant(finalText))
            send(AgentEvent.Done(conv.id, id, finalText, Speech.clean(finalText)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Error del agente", e)
            send(AgentEvent.Error(friendlyError(e)))
        }
    }

    /** Normaliza la respuesta final: sin guiones largos (la guía de estilo de Kairo no los usa). */
    private fun tidy(text: String): String = text.trim()
        .replace(Regex("(?m)^([ \\t]*)\u2014[ \\t]*"), "$1- ")
        .replace(Regex("[ \\t]*\u2014[ \\t]*(?=\\S)"), ", ")
        .replace("\u2014", "")
        .replace(Regex(",\\s*,"), ",")

    /** Ejecuta una ronda en streaming; si el modelo principal falla antes de emitir texto, reintenta con el de respaldo. */
    private suspend fun streamStep(
        messages: List<ChatMessage>,
        specs: List<ToolSpec>,
        opts: ChatOptions,
        fallback: String,
        onDelta: suspend (String) -> Unit,
    ): ChatResult {
        var emitted = false
        suspend fun attempt(o: ChatOptions): ChatResult {
            var finished: ChatResult? = null
            nan.chatStream(messages, specs, o).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Content -> { emitted = true; onDelta(chunk.text) }
                    is ChatChunk.Finished -> finished = chunk.result
                    is ChatChunk.Reasoning -> Unit
                }
            }
            return finished ?: throw NanException(502, "Respuesta vacía del modelo")
        }
        return try {
            attempt(opts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emitted || fallback == opts.model) throw e
            log.warn("Modelo {} falló ({}), usando {}", opts.model, e.message, fallback)
            attempt(opts.copy(model = fallback))
        }
    }

    private fun parseArgs(call: ToolCall): JsonObject? =
        runCatching { KairoJson.parseToJsonElement(call.function.arguments.ifBlank { "{}" }).jsonObject }.getOrNull()

    /** Solo un mensaje escrito o dictado por la persona puede confirmar (no lo compartido desde otra app). */
    private fun isHumanReply(req: AgentRequest): Boolean =
        req.channel != "share" && req.hiddenContext == null && req.attachments.isEmpty()

    private fun newCallId(): String = "call_" + UUID.randomUUID().toString().replace("-", "").take(24)

    /**
     * Ejecuta la herramienta salvo que [ToolPolicy] pida confirmación: entonces la guarda como pendiente
     * (en el servidor, no en el modelo) y le devuelve al modelo que necesita el «sí» de la persona.
     */
    private suspend fun guarded(tool: Tool?, call: ToolCall, ctx: ToolContext, destructiveInStep: Int, pendingNow: MutableList<String>): ToolResult {
        if (tool == null) return execute(tool, call, ctx)
        val args = parseArgs(call) ?: return execute(tool, call, ctx)
        // Camino rápido: el turno no leyó nada ajeno, así que lo pidió la persona.
        if (!ctx.tainted && destructiveInStep <= 1) return execute(tool, call, ctx)
        val facts = try {
            builtin.facts(ctx, tool.name, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("No pude revisar {} antes de ejecutarla: {}", tool.name, e.message)
            null
        }
        // Si no se pudo revisar, lo seguro es pedir confirmación.
        val decision = facts?.let { ToolPolicy.decide(it, ctx.tainted, destructiveInStep, ctx.taintReason) }
            ?: Decision.NeedsConfirmation(ctx.taintReason ?: ToolPolicy.DEFAULT_TAINT_REASON)
        if (decision !is Decision.NeedsConfirmation) return execute(tool, call, ctx)
        val summary = ToolPolicy.describe(facts ?: ActionFacts(tool.name, args, ctx.userId))
        confirmations.add(ctx.conversationId, ctx.userId, PendingAction(tool.name, args, summary))
        pendingNow += summary
        log.info("Acción {} pendiente de confirmación en {} ({})", tool.name, ctx.conversationId, decision.reason)
        return ToolResult(
            buildJsonObject {
                put("needs_confirmation", true)
                put("pendiente", summary)
                put("motivo", decision.reason)
                put("instruccion", "Necesita tu confirmación: $summary. Pídele al usuario que responda «sí» para hacerlo. Aún no se hizo.")
            },
            "Necesita tu confirmación: $summary",
            ok = false,
        )
    }

    private suspend fun execute(tool: Tool?, call: ToolCall, ctx: ToolContext): ToolResult {
        if (tool == null) return ToolResult.error("Herramienta desconocida: ${call.function.name}")
        val args = parseArgs(call) ?: return ToolResult.error("Argumentos inválidos (JSON mal formado)")
        return try {
            tool.handler(ctx, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            ToolResult.error(e.message ?: "Datos inválidos")
        } catch (e: Exception) {
            log.error("Fallo en herramienta {}", call.function.name, e)
            ToolResult.error("La acción falló: ${e.message?.take(160)}")
        }
    }

    private fun friendlyError(e: Exception): String = when {
        e is NanException && e.status == 401 -> "La clave de Nan no es válida o no tiene acceso a ese modelo."
        e is NanException && e.status == 429 -> "Nan está limitando las peticiones. Intenta en unos segundos."
        e is NanException -> "El modelo no respondió correctamente (${e.status}). Intenta de nuevo."
        e.message?.contains("timeout", true) == true -> "El modelo tardó demasiado. Intenta de nuevo."
        else -> "Algo salió mal: ${e.message?.take(140)}"
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        const val MAX_STEPS = 6
    }
}

/** Limpieza de texto para síntesis de voz. */
object Speech {
    fun clean(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("(\\*\\*|__|\\*|_|~~)"), "")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
}
