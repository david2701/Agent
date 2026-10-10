package app.kairo.nan

import app.kairo.util.SafeUrl
import app.kairo.util.proxyFromEnv
import app.kairo.util.safeGet
import app.kairo.util.KairoJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

class NanException(val status: Int, message: String) : RuntimeException(message)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
data class ToolCallFunction(val name: String, val arguments: String = "{}")

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
) {
    val text: String get() = when (content) {
        null -> ""
        is JsonPrimitive -> content.content
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.content }.joinToString("")
        else -> content.toString()
    }

    companion object {
        fun system(text: String) = ChatMessage("system", JsonPrimitive(text))
        fun user(text: String) = ChatMessage("user", JsonPrimitive(text))
        fun assistant(text: String, toolCalls: List<ToolCall>? = null, reasoning: String? = null) =
            ChatMessage("assistant", JsonPrimitive(text), toolCalls?.takeIf { it.isNotEmpty() }, reasoningContent = reasoning)
        fun tool(callId: String, name: String, result: String) = ChatMessage("tool", JsonPrimitive(result), toolCallId = callId, name = name)

        /** User message with an image (data URL or URL) for vision models. */
        fun userWithImage(text: String, imageUrl: String) = ChatMessage(
            "user",
            buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", text) })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", imageUrl) })
                })
            },
        )
    }
}

@Serializable
data class ToolSpec(val type: String = "function", val function: FunctionSpec)

@Serializable
data class FunctionSpec(val name: String, val description: String, val parameters: JsonObject)

data class ChatOptions(
    val model: String,
    val maxTokens: Int = 1500,
    val temperature: Double? = null,
    val reasoningEffort: String? = null,
    val jsonMode: Boolean = false,
)

data class ChatResult(
    val message: ChatMessage,
    val finishReason: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
)

/** Fragments emitted during streaming. */
sealed interface ChatChunk {
    data class Content(val text: String) : ChatChunk
    data class Reasoning(val text: String) : ChatChunk
    data class Finished(val result: ChatResult) : ChatChunk
}

data class RerankHit(val index: Int, val score: Double)

/**
 * Nan client (OpenAI-compatible API). Nan sits behind Cloudflare and rejects generic
 * user agents (error 1010), so we set our own.
 */
class NanClient(private val baseUrl: String, private val apiKey: String) {
    private val log = LoggerFactory.getLogger(NanClient::class.java)

    val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
        defaultRequest {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.UserAgent, "Kairo/1.0 (+self-hosted)")
        }
        expectSuccess = false
    }

    private fun chatBody(messages: List<ChatMessage>, tools: List<ToolSpec>, opts: ChatOptions, stream: Boolean): String {
        val body = buildJsonObject {
            put("model", opts.model)
            put("messages", KairoJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), messages))
            if (tools.isNotEmpty()) {
                put("tools", KairoJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ToolSpec.serializer()), tools))
                put("tool_choice", "auto")
            }
            put("max_tokens", opts.maxTokens)
            opts.temperature?.let { put("temperature", it) }
            opts.reasoningEffort?.let { put("reasoning_effort", it) }
            if (opts.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            if (stream) {
                put("stream", true)
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }
        }
        return body.toString()
    }

    private suspend fun ensureOk(resp: HttpResponse): HttpResponse {
        if (!resp.status.isSuccess()) {
            val txt = runCatching { resp.bodyAsText() }.getOrDefault("")
            throw NanException(resp.status.value, "Nan ${resp.status.value}: ${txt.take(300)}")
        }
        return resp
    }

    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec> = emptyList(), opts: ChatOptions): ChatResult {
        val resp = ensureOk(http.post("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(chatBody(messages, tools, opts, stream = false))
        })
        val root = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject
        val choice = root["choices"]!!.jsonArray.first().jsonObject
        val msg = KairoJson.decodeFromJsonElement<ChatMessage>(choice["message"]!!)
        val usage = root["usage"] as? JsonObject
        return ChatResult(
            message = msg,
            finishReason = (choice["finish_reason"] as? JsonPrimitive)?.content,
            promptTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.content?.toIntOrNull(),
            completionTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.content?.toIntOrNull(),
        )
    }

    /** Streaming chat (SSE). Rebuilds the tool calls from the deltas. */
    fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec> = emptyList(), opts: ChatOptions): Flow<ChatChunk> = flow {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = sortedMapOf<Int, PartialCall>()
        var finish: String? = null
        var promptTokens: Int? = null
        var completionTokens: Int? = null

        http.preparePost("$baseUrl/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(chatBody(messages, tools, opts, stream = true))
        }.execute { resp ->
            ensureOk(resp)
            val ch = resp.bodyAsChannel()
            while (true) {
                val line = ch.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val obj = runCatching { KairoJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                (obj["usage"] as? JsonObject)?.let { u ->
                    promptTokens = (u["prompt_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()
                    completionTokens = (u["completion_tokens"] as? JsonPrimitive)?.content?.toIntOrNull()
                }
                val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject ?: continue
                (choice["finish_reason"] as? JsonPrimitive)?.content?.takeIf { it != "null" }?.let { finish = it }
                val delta = choice["delta"] as? JsonObject ?: continue
                (delta["reasoning_content"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }?.let {
                    reasoning.append(it)
                    emit(ChatChunk.Reasoning(it))
                }
                (delta["content"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }?.let {
                    content.append(it)
                    emit(ChatChunk.Content(it))
                }
                (delta["tool_calls"] as? JsonArray)?.forEachIndexed { pos, el ->
                    val tc = el.jsonObject
                    val index = (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: pos
                    val p = calls.getOrPut(index) { PartialCall() }
                    (tc["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }?.let { p.id = it }
                    (tc["function"] as? JsonObject)?.let { f ->
                        (f["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() && it != "null" }?.let { p.name.append(it) }
                        (f["arguments"] as? JsonPrimitive)?.content?.takeIf { it != "null" }?.let { p.args.append(it) }
                    }
                }
            }
        }
        val toolCalls = calls.values.filter { it.name.isNotEmpty() }.mapIndexed { i, p ->
            ToolCall(id = p.id ?: "call_$i", function = ToolCallFunction(p.name.toString(), p.args.toString().ifBlank { "{}" }))
        }
        emit(
            ChatChunk.Finished(
                ChatResult(
                    message = ChatMessage.assistant(content.toString(), toolCalls, reasoning.toString().ifEmpty { null }),
                    finishReason = finish,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                ),
            ),
        )
    }

    private class PartialCall(var id: String? = null, val name: StringBuilder = StringBuilder(), val args: StringBuilder = StringBuilder())

    /** Speech to text with Whisper. [language] is the hint from the user's setting (`en`, `es`); null lets Whisper detect it. */
    suspend fun transcribe(audio: ByteArray, fileName: String, contentType: String, language: String? = null, model: String = "whisper"): Transcription {
        val resp = ensureOk(http.submitFormWithBinaryData(
            url = "$baseUrl/audio/transcriptions",
            formData = formData {
                append("model", model)
                language?.let { append("language", it) }
                append("response_format", "verbose_json")
                append("file", audio, Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            },
        ))
        val obj = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject
        return Transcription(
            text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty(),
            language = (obj["language"] as? JsonPrimitive)?.content,
            durationSeconds = (obj["duration"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
        )
    }

    /** Text to speech with Kokoro. Returns the audio (mp3 by default). */
    suspend fun speech(text: String, voice: String, model: String = "kokoro", format: String = "mp3", speed: Double = 1.0): ByteArray {
        val resp = ensureOk(http.post("$baseUrl/audio/speech") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                put("input", text)
                put("voice", voice)
                put("response_format", format)
                put("speed", speed)
            }.toString())
        })
        return resp.readRawBytes()
    }

    suspend fun embed(texts: List<String>, model: String = "qwen3-embedding", dimensions: Int = 1024): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val resp = ensureOk(http.post("$baseUrl/embeddings") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                put("input", buildJsonArray { texts.forEach { add(JsonPrimitive(it.take(8000))) } })
                put("dimensions", dimensions)
            }.toString())
        })
        val data = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonArray
        return data.sortedBy { it.jsonObject["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }.map { item ->
            item.jsonObject["embedding"]!!.jsonArray.let { arr -> FloatArray(arr.size) { i -> arr[i].jsonPrimitive.content.toFloat() } }
        }
    }

    suspend fun rerank(query: String, documents: List<String>, model: String = "rerank"): List<RerankHit> {
        if (documents.isEmpty()) return emptyList()
        val resp = ensureOk(http.post("$baseUrl/rerank") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                put("query", query)
                put("documents", buildJsonArray { documents.forEach { add(JsonPrimitive(it.take(4000))) } })
            }.toString())
        })
        val results = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject["results"]!!.jsonArray
        return results.map {
            val o = it.jsonObject
            RerankHit(o["index"]!!.jsonPrimitive.content.toInt(), o["relevance_score"]!!.jsonPrimitive.content.toDouble())
        }
    }

    /** Generates an image and returns its temporary URL. */
    suspend fun generateImage(prompt: String, model: String = "flux-2-klein", size: String = "1024x1024"): String {
        val resp = ensureOk(http.post("$baseUrl/images/generations") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("model", model)
                put("prompt", prompt)
                put("size", size)
                put("n", 1)
            }.toString())
        })
        val first = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonArray.first().jsonObject
        return first["url"]?.jsonPrimitive?.content
            ?: first["b64_json"]?.jsonPrimitive?.content?.let { "data:image/png;base64,$it" }
            ?: throw NanException(500, "Image response without data")
    }

    /** Client without credentials: never send the Nan key to third-party hosts (e.g. the image storage). */
    private val plainHttp = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
        expectSuccess = false
        followRedirects = false // safeGet validates every hop
    }

    /** Downloads the image Nan returned: https only, public hosts only, 20 MB at most and images only. */
    suspend fun download(url: String): Pair<ByteArray, String> {
        require(url.startsWith("https://")) { "Only https downloads are allowed" }
        val r = plainHttp.safeGet(url, SafeUrl.Policy(), maxBytes = MAX_IMAGE_BYTES)
        if (r.status !in 200..299) throw NanException(r.status, "Could not download the image")
        if (r.truncated) throw NanException(502, "The image is too large")
        val type = r.contentType?.substringBefore(';')?.trim()?.lowercase() ?: "image/png"
        if (!type.startsWith("image/")) throw NanException(502, "The response is not an image")
        return r.body to type
    }

    suspend fun models(): List<String> {
        val resp = ensureOk(http.get("$baseUrl/models"))
        return KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject["data"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    fun close() {
        http.close()
        plainHttp.close()
    }
}

private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

data class Transcription(val text: String, val language: String?, val durationSeconds: Double?)
