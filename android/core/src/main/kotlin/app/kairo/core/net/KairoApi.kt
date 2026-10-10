package app.kairo.core.net

import app.kairo.core.model.Activity
import app.kairo.core.model.AgentEvent
import app.kairo.core.model.ApiError
import app.kairo.core.model.AuthResponse
import app.kairo.core.model.BriefingResponse
import app.kairo.core.model.Conversation
import app.kairo.core.model.Device
import app.kairo.core.model.DeviceSync
import app.kairo.core.model.Event
import app.kairo.core.model.FamilyInfo
import app.kairo.core.model.Household
import app.kairo.core.model.InviteCreated
import app.kairo.core.model.InvitePreview
import app.kairo.core.model.Invitation
import app.kairo.core.model.Member
import app.kairo.core.model.Memory
import app.kairo.core.model.Note
import app.kairo.core.model.NoticesResponse
import app.kairo.core.model.Place
import app.kairo.core.model.PushConfig
import app.kairo.core.model.Project
import app.kairo.core.model.Recording
import app.kairo.core.model.Reminder
import app.kairo.core.model.ServerEvent
import app.kairo.core.model.SettingsResponse
import app.kairo.core.model.ShoppingList
import app.kairo.core.model.StoredMessage
import app.kairo.core.model.Task
import app.kairo.core.model.TodayData
import app.kairo.core.model.User
import app.kairo.core.model.VoicesResponse
import app.kairo.core.session.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * What went wrong, so the UI can show a localized message. [Kind.Server] carries text written by the
 * server (already in the user's language); every other kind has a message resource in the app.
 */
class ApiException(val status: Int, override val message: String, val kind: Kind = Kind.Server) : RuntimeException(message) {
    enum class Kind { Server, Http, NoServer, NoSession, SessionExpired, InviteNotFound, MissingRefreshToken, SpeechFailed }
}

/** Information the client sends with every request (capabilities and location). */
interface ClientContext {
    val clientKind: String
    val capabilities: Set<String>
    suspend fun location(): Pair<Double, Double>?
}

/** Kairo API client (see docs/API.md). */
class KairoApi(private val session: SessionStore, private val ctx: ClientContext) {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
        classDiscriminator = "type"
    }

    val http = HttpClient(OkHttp) {
        install(WebSockets) { pingIntervalMillis = 20_000 }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 90_000
        }
        expectSuccess = false
    }

    private val refreshMutex = Mutex()

    /** Device language, so the server can pick the account language at sign-up (`es*` -> Spanish, else English). */
    private fun HttpRequestBuilder.language() = header(HttpHeaders.AcceptLanguage, java.util.Locale.getDefault().toLanguageTag())

    // ------------------------------------------------------------ Authentication

    suspend fun login(serverUrl: String, email: String, password: String, deviceName: String): User {
        val base = normalizeServer(serverUrl)
        val resp = http.request("$base/api/auth/login") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header("X-Kairo-Client", ctx.clientKind)
            language()
            setBody(buildJsonObject {
                put("email", email.trim())
                put("password", password)
                put("deviceName", deviceName)
                put("platform", ctx.clientKind)
                put("timezone", java.time.ZoneId.systemDefault().id)
            }.toString())
        }
        val auth: AuthResponse = decode(resp)
        session.save(base, auth.user, auth.deviceId, auth.refreshToken ?: throw ApiException(0, "The server returned no refresh token", ApiException.Kind.MissingRefreshToken), auth.accessToken)
        return auth.user
    }

    /** What a family code opens (public): a new invitation or an existing member's access code. */
    suspend fun invitePreview(serverUrl: String, code: String): InvitePreview {
        val base = normalizeServer(serverUrl)
        val resp = http.request("$base/api/auth/invite/${normalizeCode(code).encodeURLParameter()}") {
            method = HttpMethod.Get
            header("X-Kairo-Client", ctx.clientKind)
        }
        if (resp.status.value == 404) throw ApiException(404, "That code does not exist or has expired", ApiException.Kind.InviteNotFound)
        return decode(resp)
    }

    /**
     * Redeems a family code: with an invitation it creates the new member's account (needs [email]);
     * with an access code it sets a new password. Stores the session like a normal login.
     */
    suspend fun join(serverUrl: String, code: String, email: String?, password: String, displayName: String?, deviceName: String): User {
        val base = normalizeServer(serverUrl)
        val resp = http.request("$base/api/auth/join") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header("X-Kairo-Client", ctx.clientKind)
            language()
            setBody(buildJsonObject {
                put("code", normalizeCode(code))
                email?.trim()?.takeIf { it.isNotEmpty() }?.let { put("email", it) }
                put("password", password)
                displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("displayName", it) }
                put("deviceName", deviceName)
                put("platform", ctx.clientKind)
                put("timezone", java.time.ZoneId.systemDefault().id)
            }.toString())
        }
        val auth: AuthResponse = decode(resp)
        session.save(base, auth.user, auth.deviceId, auth.refreshToken ?: throw ApiException(0, "The server returned no refresh token", ApiException.Kind.MissingRefreshToken), auth.accessToken)
        return auth.user
    }

    /** Pairs this device (watch) with a code generated on the phone or the dashboard. */
    suspend fun pair(serverUrl: String, code: String, deviceName: String): User {
        val base = normalizeServer(serverUrl)
        val resp = http.request("$base/api/auth/pair") {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header("X-Kairo-Client", ctx.clientKind)
            setBody(buildJsonObject { put("code", code.trim()); put("deviceName", deviceName); put("platform", ctx.clientKind) }.toString())
        }
        val auth: AuthResponse = decode(resp)
        session.save(base, auth.user, auth.deviceId, auth.refreshToken ?: throw ApiException(0, "The server returned no refresh token", ApiException.Kind.MissingRefreshToken), auth.accessToken)
        return auth.user
    }

    suspend fun pairCode(): String = send<PairCode>(HttpMethod.Post, "/api/auth/pair-code").code

    suspend fun logout() {
        runCatching { send<JsonObject>(HttpMethod.Post, "/api/auth/logout") }
        session.clear()
    }

    private suspend fun refresh(): Boolean = refreshMutex.withLock {
        val base = session.serverUrl() ?: return false
        val token = session.refreshToken() ?: return false
        val resp = runCatching {
            http.request("$base/api/auth/refresh") {
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("refreshToken", token) }.toString())
            }
        }.getOrElse { return false }
        if (resp.status.value == 401) {
            session.clear()
            return false
        }
        if (!resp.status.isSuccess()) return false
        val auth = json.decodeFromString(AuthResponse.serializer(), resp.bodyAsText())
        session.updateTokens(auth.refreshToken ?: token, auth.accessToken, auth.user)
        true
    }

    // ------------------------------------------------------------ HTTP core

    private suspend fun base(): String = session.serverUrl() ?: throw ApiException(0, "No server configured", ApiException.Kind.NoServer)

    private suspend fun HttpRequestBuilder.common() {
        session.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header("X-Kairo-Client", ctx.clientKind)
        language()
        if (ctx.capabilities.isNotEmpty()) header("X-Kairo-Capabilities", ctx.capabilities.joinToString(","))
        ctx.location()?.let { (lat, lon) -> header("X-Kairo-Location", "$lat,$lon") }
    }

    /**
     * Runs the request; if the access token expired, refreshes once and retries.
     * Note: the parameter cannot be named `build`, because inside the `build()` block it would resolve to
     * `HttpRequestBuilder.build()` and the body would never be set.
     */
    suspend fun raw(method: HttpMethod, path: String, configure: HttpRequestBuilder.() -> Unit = {}): HttpResponse {
        if (session.accessToken == null) refresh()
        val url = base() + path
        var resp = http.request(url) { this.method = method; common(); configure() }
        if (resp.status.value == 401 && refresh()) {
            resp = http.request(url) { this.method = method; common(); configure() }
        }
        if (resp.status.value == 401) throw ApiException(401, "Your session expired. Sign in again.", ApiException.Kind.SessionExpired)
        return resp
    }

    suspend inline fun <reified T> send(method: HttpMethod, path: String, body: JsonElement? = null): T {
        val resp = raw(method, path) {
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        return decode(resp)
    }

    suspend inline fun <reified T> decode(resp: HttpResponse): T {
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            val serverMsg = runCatching { json.decodeFromString(ApiError.serializer(), text).error }.getOrNull()
            throw ApiException(resp.status.value, serverMsg ?: "Error ${resp.status.value}", if (serverMsg != null) ApiException.Kind.Server else ApiException.Kind.Http)
        }
        if (T::class == Unit::class) return Unit as T
        return json.decodeFromString(text)
    }

    // ------------------------------------------------------------ Agent (SSE)

    fun chat(message: String, conversationId: String?, channel: String = "chat"): Flow<AgentEvent> = stream("/api/chat") {
        contentType(ContentType.Application.Json)
        setBody(buildJsonObject {
            put("message", message)
            conversationId?.let { put("conversationId", it) }
            put("channel", channel)
        }.toString())
    }

    fun voiceTurn(audio: ByteArray, mime: String, fileName: String, conversationId: String?, channel: String = "voice"): Flow<AgentEvent> =
        stream("/api/voice/turn") {
            setBody(MultiPartFormDataContent(formData {
                append("file", audio, Headers.build {
                    append(HttpHeaders.ContentType, mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
                conversationId?.let { append("conversationId", it) }
                append("channel", channel)
            }))
        }

    fun capture(text: String?, url: String?, image: ByteArray?, imageMime: String?): Flow<AgentEvent> = stream("/api/capture") {
        setBody(MultiPartFormDataContent(formData {
            text?.let { append("text", it) }
            url?.let { append("url", it) }
            if (image != null) {
                append("file", image, Headers.build {
                    append(HttpHeaders.ContentType, imageMime ?: "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"capture.jpg\"")
                })
            }
        }))
    }

    private fun stream(path: String, configure: HttpRequestBuilder.() -> Unit): Flow<AgentEvent> = flow {
        if (session.accessToken == null) refresh()
        var attempt = 0
        while (true) {
            val url = base() + path
            var retry = false
            http.prepareRequest(url) {
                method = HttpMethod.Post
                timeout { requestTimeoutMillis = 180_000; socketTimeoutMillis = 120_000 }
                common()
                configure()
            }.execute { resp ->
                if (resp.status.value == 401 && attempt == 0 && refresh()) {
                    retry = true
                    return@execute
                }
                if (!resp.status.isSuccess()) {
                    val msg = runCatching { json.decodeFromString(ApiError.serializer(), resp.bodyAsText()).error }.getOrNull()
                    emit(AgentEvent.Error(msg ?: "Error ${resp.status.value}"))
                    return@execute
                }
                parseSse(resp)
            }
            if (!retry) break
            attempt++
        }
    }

    private suspend fun FlowCollector<AgentEvent>.parseSse(resp: HttpResponse) {
        val ch = resp.bodyAsChannel()
        while (true) {
            val line = ch.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            val ev = runCatching { json.decodeFromString(AgentEvent.serializer(), payload) }.getOrNull() ?: continue
            emit(ev)
        }
    }

    // ------------------------------------------------------------ Voice

    suspend fun speak(text: String, voice: String? = null): ByteArray {
        val resp = raw(HttpMethod.Post, "/api/voice/speak") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", text); voice?.let { put("voice", it) } }.toString())
        }
        if (!resp.status.isSuccess()) throw ApiException(resp.status.value, "Could not generate speech", ApiException.Kind.SpeechFailed)
        return resp.readRawBytes()
    }

    suspend fun voices(): VoicesResponse = send(HttpMethod.Get, "/api/voice/voices")

    // ------------------------------------------------------------ Data

    suspend fun today(): TodayData = send(HttpMethod.Get, "/api/today")
    suspend fun briefing(): BriefingResponse = send(HttpMethod.Post, "/api/briefing")
    suspend fun me(): User = send<UserEnvelope>(HttpMethod.Get, "/api/me").user

    suspend fun conversations(): List<Conversation> = send(HttpMethod.Get, "/api/conversations")
    suspend fun messages(id: String): List<StoredMessage> = send(HttpMethod.Get, "/api/conversations/$id/messages")
    suspend fun deleteConversation(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/conversations/$id") }

    suspend fun notes(q: String? = null): List<Note> = send(HttpMethod.Get, "/api/notes" + (q?.let { "?q=${it.encodeURLParameter()}" } ?: ""))
    suspend fun searchNotes(q: String): List<Note> = send(HttpMethod.Get, "/api/notes/search?q=${q.encodeURLParameter()}")
    suspend fun note(id: String): Note = send(HttpMethod.Get, "/api/notes/$id")
    suspend fun createNote(title: String, content: String, tags: List<String> = emptyList(), shared: Boolean = false): Note =
        send(HttpMethod.Post, "/api/notes", obj { put("title", title); put("content", content); put("tags", strings(tags)); if (shared) put("shared", true) })
    suspend fun updateNote(id: String, patch: JsonObject): Note = send(HttpMethod.Patch, "/api/notes/$id", patch)
    suspend fun deleteNote(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/notes/$id") }

    /** [assignee]: `me` or a member id (tasks that person is responsible for). */
    suspend fun tasks(includeDone: Boolean = true, assignee: String? = null): List<Task> =
        send(HttpMethod.Get, "/api/tasks?includeDone=$includeDone" + (assignee?.let { "&assignee=${it.encodeURLParameter()}" } ?: ""))
    suspend fun createTask(body: JsonObject): Task = send(HttpMethod.Post, "/api/tasks", body)
    suspend fun updateTask(id: String, patch: JsonObject): Task = send(HttpMethod.Patch, "/api/tasks/$id", patch)
    suspend fun deleteTask(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/tasks/$id") }
    suspend fun projects(): List<Project> = send(HttpMethod.Get, "/api/projects")
    suspend fun createProject(name: String): Project = send(HttpMethod.Post, "/api/projects", obj { put("name", name) })

    suspend fun reminders(statuses: String? = null): List<Reminder> = send(HttpMethod.Get, "/api/reminders" + (statuses?.let { "?status=$it" } ?: ""))
    suspend fun createReminder(body: JsonObject): Reminder = send(HttpMethod.Post, "/api/reminders", body)
    suspend fun updateReminder(id: String, patch: JsonObject): Reminder = send(HttpMethod.Patch, "/api/reminders/$id", patch)
    suspend fun reminderDone(id: String): Reminder = send(HttpMethod.Post, "/api/reminders/$id/done")
    suspend fun reminderSnooze(id: String, minutes: Int): Reminder = send(HttpMethod.Post, "/api/reminders/$id/snooze", obj { put("minutes", minutes) })
    suspend fun reminderCancel(id: String): Reminder = send(HttpMethod.Post, "/api/reminders/$id/cancel")
    suspend fun deleteReminder(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/reminders/$id") }

    suspend fun places(): List<Place> = send(HttpMethod.Get, "/api/places")
    suspend fun savePlace(name: String, lat: Double, lon: Double, radius: Int, shared: Boolean = false): Place =
        send(HttpMethod.Post, "/api/places", obj { put("name", name); put("latitude", lat); put("longitude", lon); put("radiusM", radius); if (shared) put("shared", true) })
    suspend fun sharePlace(id: String, shared: Boolean): Place = send(HttpMethod.Patch, "/api/places/$id", obj { put("shared", shared) })
    suspend fun deletePlace(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/places/$id") }

    suspend fun lists(): List<ShoppingList> = send(HttpMethod.Get, "/api/lists")
    suspend fun createList(name: String, shared: Boolean = false): ShoppingList = send(HttpMethod.Post, "/api/lists", obj { put("name", name); if (shared) put("shared", true) })
    suspend fun updateList(id: String, patch: JsonObject): ShoppingList = send(HttpMethod.Patch, "/api/lists/$id", patch)
    suspend fun deleteList(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/lists/$id") }
    suspend fun addItems(listId: String, items: List<String>): ShoppingList = send(HttpMethod.Post, "/api/lists/$listId/items", obj { put("items", strings(items)) })
    suspend fun setItem(listId: String, itemId: String, checked: Boolean): ShoppingList =
        send(HttpMethod.Patch, "/api/lists/$listId/items/$itemId", obj { put("checked", checked) })
    suspend fun deleteItem(listId: String, itemId: String): ShoppingList = send(HttpMethod.Delete, "/api/lists/$listId/items/$itemId")
    suspend fun clearChecked(listId: String): ShoppingList = send(HttpMethod.Post, "/api/lists/$listId/clear-checked")

    suspend fun events(fromIso: String, toIso: String): List<Event> = send(HttpMethod.Get, "/api/events?from=$fromIso&to=$toIso")
    suspend fun createEvent(body: JsonObject): Event = send(HttpMethod.Post, "/api/events", body)
    suspend fun updateEvent(id: String, patch: JsonObject): Event = send(HttpMethod.Patch, "/api/events/$id", patch)
    suspend fun deleteEvent(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/events/$id") }

    suspend fun memories(): List<Memory> = send(HttpMethod.Get, "/api/memories")
    suspend fun createMemory(content: String, category: String): Memory = send(HttpMethod.Post, "/api/memories", obj { put("content", content); put("category", category) })
    suspend fun updateMemory(id: String, content: String, category: String): Memory =
        send(HttpMethod.Patch, "/api/memories/$id", obj { put("content", content); put("category", category) })
    suspend fun deleteMemory(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/memories/$id") }

    suspend fun recordings(): List<Recording> = send(HttpMethod.Get, "/api/recordings")
    suspend fun recording(id: String): Recording = send(HttpMethod.Get, "/api/recordings/$id")
    suspend fun createRecording(title: String = ""): Recording = send(HttpMethod.Post, "/api/recordings", obj { put("title", title) })
    suspend fun uploadSegment(id: String, seq: Int, durationS: Int, audio: ByteArray, mime: String): Recording {
        val resp = raw(HttpMethod.Post, "/api/recordings/$id/segments") {
            setBody(MultiPartFormDataContent(formData {
                append("seq", seq.toString())
                append("duration", durationS.toString())
                append("file", audio, Headers.build {
                    append(HttpHeaders.ContentType, mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"seg$seq.m4a\"")
                })
            }))
        }
        return decode(resp)
    }
    suspend fun finishRecording(id: String, title: String? = null) { send<JsonObject>(HttpMethod.Post, "/api/recordings/$id/finish", obj { title?.let { put("title", it) } }) }
    suspend fun recordingTasks(id: String): Int = send<CreatedCount>(HttpMethod.Post, "/api/recordings/$id/tasks").created
    suspend fun deleteRecording(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/recordings/$id") }

    suspend fun activity(limit: Int = 60): List<Activity> = send(HttpMethod.Get, "/api/activity?limit=$limit")
    suspend fun undo(id: String) { send<JsonObject>(HttpMethod.Post, "/api/activity/$id/undo") }

    suspend fun settings(): SettingsResponse = send(HttpMethod.Get, "/api/settings")
    suspend fun updateSettings(patch: JsonObject): SettingsResponse = send(HttpMethod.Patch, "/api/settings", patch)
    suspend fun devices(): List<Device> = send(HttpMethod.Get, "/api/devices")
    suspend fun revokeDevice(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/devices/$id") }
    suspend fun deviceSync(): DeviceSync = send(HttpMethod.Get, "/api/sync/device")

    // ------------------------------------------------------------ Family

    suspend fun family(): FamilyInfo = send(HttpMethod.Get, "/api/family")
    suspend fun renameFamily(name: String): Household = send(HttpMethod.Patch, "/api/family", obj { put("name", name) })
    suspend fun invites(): List<Invitation> = send(HttpMethod.Get, "/api/family/invites")
    suspend fun createInvite(name: String, role: String = "member"): InviteCreated =
        send(HttpMethod.Post, "/api/family/invites", obj { put("name", name); put("role", role) })
    suspend fun revokeInvite(id: String) { send<JsonObject>(HttpMethod.Delete, "/api/family/invites/$id") }
    suspend fun accessCode(memberId: String): InviteCreated = send(HttpMethod.Post, "/api/family/members/$memberId/access-code")
    suspend fun setMemberRole(memberId: String, role: String): Member = send(HttpMethod.Patch, "/api/family/members/$memberId", obj { put("role", role) })
    suspend fun removeMember(memberId: String) { send<JsonObject>(HttpMethod.Delete, "/api/family/members/$memberId") }

    /** Notice to the family; without [to], to everyone but you. Returns how many received it. */
    suspend fun familyMessage(text: String, to: List<String>? = null): Int =
        send<SentCount>(HttpMethod.Post, "/api/family/message", obj { put("text", text); to?.let { put("to", strings(it)) } }).sent

    suspend fun notices(unread: Boolean = false, limit: Int = 50): NoticesResponse = send(HttpMethod.Get, "/api/notices?unread=$unread&limit=$limit")
    /** Marks notices as read; without [ids], all of them. */
    suspend fun markNoticesRead(ids: List<String>? = null) { send<JsonObject>(HttpMethod.Post, "/api/notices/read", obj { ids?.let { put("ids", strings(it)) } }) }

    // ------------------------------------------------------------ Own push (UnifiedPush)

    suspend fun pushConfig(): PushConfig = send(HttpMethod.Get, "/api/push")
    suspend fun registerPush(endpoint: String, p256dh: String, auth: String): PushConfig =
        send(HttpMethod.Post, "/api/push/register", obj { put("endpoint", endpoint); put("p256dh", p256dh); put("auth", auth) })
    suspend fun unregisterPush() { send<JsonObject>(HttpMethod.Delete, "/api/push/register") }
    /** Sends a test `family.notice` over push only; returns how many devices accepted it. */
    suspend fun testPush(): Int = send<Delivered>(HttpMethod.Post, "/api/push/test").delivered

    /** Absolute URL for signed files (attachments and images). */
    suspend fun absolute(url: String): String = if (url.startsWith("http")) url else base() + url

    // ------------------------------------------------------------ Realtime

    /** Server events with automatic reconnection (exponential backoff up to 60 s). */
    fun realtime(): Flow<ServerEvent> = flow {
        var backoff = 2_000L
        while (true) {
            try {
                // The server closes the socket when the token expires and rejects the handshake with an expired one:
                // renew before connecting instead of waiting for a 401 (which does not surface as ApiException in the handshake)
                if (session.accessToken?.let { expiresSoon(it) } != false) refresh()
                val token = session.accessToken ?: throw ApiException(401, "No session", ApiException.Kind.NoSession)
                val wsUrl = base().replaceFirst("http", "ws") + "/api/ws?access_token=" + token.encodeURLParameter()
                http.webSocket(wsUrl) {
                    backoff = 2_000L
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val ev = runCatching { json.decodeFromString(ServerEvent.serializer(), frame.readText()) }.getOrNull() ?: continue
                            if (ev.type != "ping" && ev.type != "pong") emit(ev)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiException && e.status == 401) refresh()
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    companion object {
        /** True if the JWT has less than a minute left, or if its `exp` cannot be read. */
        fun expiresSoon(token: String, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
            val payload = token.split('.').getOrNull(1) ?: return true
            val exp = runCatching {
                val claims = Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8))
                claims.jsonObject["exp"]?.jsonPrimitive?.long
            }.getOrNull() ?: return true
            return exp - nowSeconds < 60
        }

        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return s.removeSuffix("/api")
        }

        /** Family codes `XXXX-XXXX`: accepts lower case, spaces and a missing dash. */
        fun normalizeCode(raw: String): String {
            val clean = raw.uppercase().filter { it.isLetterOrDigit() }
            return if (clean.length == 8) clean.take(4) + "-" + clean.drop(4) else clean
        }

        inline fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)
        fun strings(values: List<String>) = kotlinx.serialization.json.JsonArray(values.map { JsonPrimitive(it) })
    }
}

@Serializable
data class UserEnvelope(val user: User)

@Serializable
data class CreatedCount(val created: Int)

@Serializable
data class PairCode(val code: String, val expiresIn: Int = 300)

@Serializable
data class SentCount(val sent: Int)

@Serializable
data class Delivered(val delivered: Int)
