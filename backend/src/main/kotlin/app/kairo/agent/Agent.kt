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
import app.kairo.util.InvalidInput
import app.kairo.util.KairoJson
import app.kairo.util.Lang
import app.kairo.util.M
import app.kairo.util.UUIDSerializer
import app.kairo.util.logSafe
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

/** Events the agent emits over SSE to the clients. The `type` field tells them apart. */
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
    /** Additional context the model sees but that is not shown as a user message (e.g. text extracted from an image). */
    val hiddenContext: String? = null,
    val displayText: String? = null,
    val attachments: List<FileRef> = emptyList(),
)

/**
 * Agent loop: Nan model with streaming tool calling, up to [MAX_STEPS] rounds.
 * It persists the whole conversation (tool calls included) to keep the context.
 *
 * Against instruction injection: if the turn read content from other people or from outside
 * ([ToolContext.tainted]), sensitive actions stay pending in [confirmations] and only run
 * if the next human message of the conversation is a "yes" ([Affirmative], English or Spanish).
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
        // Language of the texts the user sees (tool cards, confirmations, errors); known once settings are read
        var lang: Lang = settings.defaultLang
        try {
            val s = settings.get(user.id)
            lang = settings.langOf(s)
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
            // What is pending confirmation always leaves with this message: either it is a human "yes", or it is discarded.
            val pending = confirmations.take(conv.id, user.id)
            val confirmed = pending.isNotEmpty() && isHumanReply(req) && Affirmative.matches(req.text)
            if (pending.isNotEmpty() && !confirmed) log.info("Pending actions discarded in {}: {}", conv.id, pending.map { it.tool })
            val userText = buildString {
                append(req.text)
                req.hiddenContext?.let { append("\n\n[Attached context]\n").append(Untrusted.wrap(it)) }
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
            messages += ChatMessage.system(SystemPrompt.build(user, zone, req.channel, mems, plcs, lsts, fam, s.language))
            messages += history
            messages += ChatMessage.user(userText)

            val ctx = ToolContext(
                user, zone, s, req.channel, conv.id, req.location, req.capabilities, req.attachments.map { it.id }.toMutableList(),
                familyMembers = fam?.members.orEmpty(), lang = lang,
            )
            // External content in this message, or other people's data the model still sees in the history.
            if (req.hiddenContext != null) ctx.taint("content shared from another app, a web page or an image")
            if (history.any { it.role == "tool" && it.text.contains(Untrusted.OPEN) }) ctx.taint(ToolPolicy.DEFAULT_TAINT_REASON)
            val opts = ChatOptions(
                model = s.chatModel,
                maxTokens = if (req.channel == "voice") 700 else 1800,
                reasoningEffort = if (s.fastMode || req.channel == "voice") "none" else null,
            )
            /** Summaries of what was left pending in this turn; the server appends them to the reply. */
            val pendingNow = ArrayList<String>()

            suspend fun runCall(call: ToolCall, confirmedByUser: Boolean, destructiveInStep: Int) {
                val tool = byName[call.function.name]
                send(AgentEvent.ToolStart(call.id, call.function.name, tool?.label?.text(lang) ?: M.TOOL_WORKING.t(lang)))
                val res = if (confirmedByUser) execute(tool, call, ctx) else guarded(tool, call, ctx, destructiveInStep, pendingNow)
                send(AgentEvent.ToolEnd(call.id, call.function.name, res.ok, res.summary, res.entity))
                res.deviceAction?.let { send(AgentEvent.DeviceAction(it)) }
                res.attachment?.let { send(AgentEvent.Attachment(it)) }
                res.untrusted?.let { ctx.taint(it) }
                // The model sees other people's content between delimiters; the UI cards come from `meta`, which does not change.
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
                // The person said "yes": the server executes what was stored (not the model) and the agent continues with that context.
                val calls = pending.map { ToolCall(newCallId(), function = ToolCallFunction(it.tool, it.args.toString())) }
                val msg = ChatMessage("assistant", JsonPrimitive(""), calls)
                messages += msg
                conversations.append(conv.id, msg.copy(content = null))
                log.info("Actions confirmed by {} in {}: {}", user.id, conv.id, pending.map { it.tool })
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
                // The text before the tools ("let me check...") adds nothing to the history and sometimes comes out in the wrong language.
                conversations.append(conv.id, msg.copy(content = null, reasoningContent = null))
                val destructive = calls.count { c -> parseArgs(c)?.let { ToolPolicy.isDestructive(c.function.name, it) } == true }
                for (call in calls) runCall(call, confirmedByUser = false, destructiveInStep = destructive)
                step++
            }
            // The server writes the confirmation question, in the user's language: it does not depend on what the model says.
            if (pendingNow.isNotEmpty()) {
                val ask = Confirmations.prompt(pendingNow, lang)
                finalText = if (finalText.isEmpty()) ask else "$finalText\n\n$ask"
            }
            if (finalText.isEmpty()) finalText = M.AGENT_DONE.t(lang)
            val id = conversations.append(conv.id, ChatMessage.assistant(finalText))
            send(AgentEvent.Done(conv.id, id, finalText, Speech.clean(finalText)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Agent error", e)
            send(AgentEvent.Error(friendlyError(e, lang)))
        }
    }

    /** Normalizes the final reply: no long dashes (Kairo's style guide does not use them). */
    private fun tidy(text: String): String = text.trim()
        .replace(Regex("(?m)^([ \\t]*)\u2014[ \\t]*"), "$1- ")
        .replace(Regex("[ \\t]*\u2014[ \\t]*(?=\\S)"), ", ")
        .replace("\u2014", "")
        .replace(Regex(",\\s*,"), ",")

    /** Runs one streaming round; if the main model fails before emitting text, it retries with the fallback one. */
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
            return finished ?: throw NanException(502, "Empty response from the model")
        }
        return try {
            attempt(opts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emitted || fallback == opts.model) throw e
            log.warn("Model {} failed ({}), using {}", opts.model, logSafe(e.message), fallback)
            attempt(opts.copy(model = fallback))
        }
    }

    private fun parseArgs(call: ToolCall): JsonObject? =
        runCatching { KairoJson.parseToJsonElement(call.function.arguments.ifBlank { "{}" }).jsonObject }.getOrNull()

    /** Only a message typed or dictated by the person can confirm (not something shared from another app). */
    private fun isHumanReply(req: AgentRequest): Boolean =
        req.channel != "share" && req.hiddenContext == null && req.attachments.isEmpty()

    private fun newCallId(): String = "call_" + UUID.randomUUID().toString().replace("-", "").take(24)

    /**
     * Executes the tool unless [ToolPolicy] asks for confirmation: then it stores it as pending
     * (on the server, not in the model) and tells the model that it needs the person's "yes".
     */
    private suspend fun guarded(tool: Tool?, call: ToolCall, ctx: ToolContext, destructiveInStep: Int, pendingNow: MutableList<String>): ToolResult {
        if (tool == null) return execute(tool, call, ctx)
        val args = parseArgs(call) ?: return execute(tool, call, ctx)
        // Fast path: the turn read nothing foreign, so the person asked for it.
        if (!ctx.tainted && destructiveInStep <= 1) return execute(tool, call, ctx)
        val facts = try {
            builtin.facts(ctx, tool.name, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not check {} before running it: {}", tool.name, logSafe(e.message))
            null
        }
        // If it could not be checked, the safe choice is to ask for confirmation.
        val decision = facts?.let { ToolPolicy.decide(it, ctx.tainted, destructiveInStep, ctx.taintReason) }
            ?: Decision.NeedsConfirmation(ctx.taintReason ?: ToolPolicy.DEFAULT_TAINT_REASON)
        if (decision !is Decision.NeedsConfirmation) return execute(tool, call, ctx)
        val summary = ToolPolicy.describe(facts ?: ActionFacts(tool.name, args, ctx.userId), ctx.lang)
        confirmations.add(ctx.conversationId, ctx.userId, PendingAction(tool.name, args, summary))
        pendingNow += summary
        log.info("Action {} pending confirmation in {} ({})", tool.name, ctx.conversationId, decision.reason)
        return ToolResult(
            buildJsonObject {
                put("needs_confirmation", true)
                put("pending", summary)
                put("reason", decision.reason)
                put("instruction", "This needs the user's confirmation: $summary. Ask the user to answer \"yes\" to do it. It has not been done yet.")
            },
            M.CONFIRM_NEEDED_CARD.t(ctx.lang, summary),
            ok = false,
        )
    }

    private suspend fun execute(tool: Tool?, call: ToolCall, ctx: ToolContext): ToolResult {
        if (tool == null) return ToolResult.error(ctx.t(M.TOOL_UNKNOWN, call.function.name))
        val args = parseArgs(call) ?: return ToolResult.error(ctx.t(M.TOOL_BAD_ARGS))
        return try {
            tool.handler(ctx, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: InvalidInput) {
            ToolResult.error(e.text(ctx.lang).take(160))
        } catch (e: IllegalArgumentException) {
            ToolResult.error(e.message?.take(160) ?: ctx.t(M.TOOL_INVALID_DATA))
        } catch (e: IllegalStateException) {
            // error("...") and check() of the tools: messages written for the person
            ToolResult.error(e.message?.take(160) ?: ctx.t(M.TOOL_FAILED))
        } catch (e: NanException) {
            ToolResult.error(e.message?.take(160) ?: ctx.t(M.TOOL_MODEL_DOWN))
        } catch (e: Exception) {
            // Internal errors (database, network): the detail stays in the log, it is not passed to the model
            log.error("Tool {} failed", call.function.name, e)
            ToolResult.error(ctx.t(M.TOOL_INTERNAL))
        }
    }

    private fun friendlyError(e: Exception, lang: Lang): String = when {
        e is NanException && e.status == 401 -> M.AGENT_ERR_KEY.t(lang)
        e is NanException && e.status == 429 -> M.AGENT_ERR_RATE.t(lang)
        e is NanException -> M.AGENT_ERR_MODEL.t(lang, e.status)
        e.message?.contains("timeout", true) == true -> M.AGENT_ERR_TIMEOUT.t(lang)
        else -> M.AGENT_ERR_GENERIC.t(lang)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        const val MAX_STEPS = 6
    }
}

/** Text cleanup for speech synthesis. */
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
