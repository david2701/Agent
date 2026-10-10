package app.kairo.services

import app.kairo.config.AppConfig
import app.kairo.domain.FamilyRepo
import app.kairo.domain.NoticesRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.UsersRepo
import app.kairo.integrations.ApnsService
import app.kairo.integrations.NtfyNotifier
import app.kairo.integrations.PushService
import app.kairo.realtime.EventBus
import app.kairo.realtime.EventTypes
import app.kairo.realtime.ServerEvent
import app.kairo.util.Lang
import app.kairo.util.Localizable
import app.kairo.util.M
import app.kairo.util.Plain
import app.kairo.util.logSafe
import app.kairo.util.say
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Title and body of a family notice. Each recipient gets it rendered in their own language, so the pieces are
 * [Localizable] (a catalog message, or [Plain] text such as a title the user typed).
 */
class NoticeText(val title: Localizable, val body: Localizable) {
    companion object {
        fun plain(title: String, body: String) = NoticeText(Plain(title), Plain(body))
    }
}

/**
 * A single place to notify a person, without Firebase:
 * 1. WebSocket to their connected devices (dashboard, app open or listening).
 * 2. Encrypted Web Push to their phones with UnifiedPush (arrives with the app closed).
 * 3. Apple native push (APNs) to their iPhones, if the server has APNs credentials.
 * 4. ntfy, if they configured a topic of their own.
 * Phones deduplicate by id, so receiving the same notice through two paths does not show it twice.
 */
class Notifier(
    private val scope: CoroutineScope,
    private val cfg: AppConfig,
    private val bus: EventBus,
    private val push: PushService,
    private val apns: ApnsService,
    private val ntfy: NtfyNotifier,
    private val notices: NoticesRepo,
    private val users: UsersRepo,
    private val family: FamilyRepo,
    private val settings: SettingsService,
) {
    private val log = LoggerFactory.getLogger(Notifier::class.java)

    /** System notice for a user (reminder, upcoming event, daily summary). */
    suspend fun deliver(userId: UUID, event: ServerEvent, title: String, body: String, tags: List<String> = emptyList(), priority: Int = 4) {
        bus.publish(userId, event)
        scope.launch {
            runCatching { push.send(userId, event) }.onFailure { log.warn("Push failed: {}", it.message) }
            runCatching { ntfyTarget(userId)?.let { (topic, auth) -> ntfy.send(topic, title, body, tags = tags, priority = priority, authenticated = auth) } }
                .onFailure { log.warn("ntfy failed: {}", logSafe(it.message)) }
        }
        // Separate, so a slow Web Push does not delay the iPhone notice
        if (apns.available) scope.launch {
            runCatching { apns.send(userId, event, title, body) }.onFailure { log.warn("APNs failed: {}", it.message) }
        }
    }

    /**
     * Notifies [recipients] (not including whoever did the action) and stores the notice for whoever is not connected.
     * The text is rendered per recipient, in their language ("Valentina added a family event").
     */
    suspend fun family(actorId: UUID, recipients: Collection<UUID>, kind: String, text: NoticeText, entityType: String? = null, entityId: UUID? = null) {
        for (r in recipients.toSet() - actorId) {
            val lang = settings.lang(r)
            val title = text.title.text(lang)
            val body = text.body.text(lang)
            val n = notices.insert(r, actorId, kind, title, body, entityType, entityId)
            val event = ServerEvent(EventTypes.FAMILY_NOTICE, buildJsonObject {
                put("id", n.id.toString())
                put("kind", n.kind)
                put("title", n.title)
                put("body", n.body)
                put("actorId", actorId.toString())
                entityType?.let { put("entityType", it) }
                entityId?.let { put("entityId", it.toString()) }
                put("createdAt", n.createdAt.toString())
            })
            deliver(r, event, title, body, tags = listOf("house"))
        }
    }

    /** Notifies the whole family except whoever acted. */
    suspend fun everyone(actorId: UUID, kind: String, text: NoticeText, entityType: String? = null, entityId: UUID? = null) =
        family(actorId, family.memberIds(actorId), kind, text, entityType, entityId)

    /** Every member of the user's household, the user included. */
    suspend fun members(userId: UUID): List<UUID> = family.memberIds(userId)

    /** Short name for notices ("Valentina" instead of "Valentina Gómez"), or null if the user is gone. */
    suspend fun firstName(userId: UUID): String? =
        users.byId(userId)?.displayName?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() }

    /** [firstName] as notice text: the name itself, or "Someone" in the reader's language when it is unknown. */
    suspend fun who(userId: UUID): Localizable = firstName(userId)?.let { Plain(it) } ?: say(M.SOMEONE)

    /**
     * The person's ntfy topic and whether it is published with NTFY_TOKEN.
     * The .env topic (NTFY_TOPIC) and the server token belong to the owner (KAIRO_OWNER_EMAIL): only their notices
     * are published with NTFY_TOKEN. The other members can set a topic of their own in Settings, which is published
     * without a token (as anyone from outside would), and never the owner's topic.
     */
    private suspend fun ntfyTarget(userId: UUID): Pair<String, Boolean>? {
        val own = settings.get(userId).ntfyTopic?.trim()?.takeIf { it.isNotEmpty() }
        val owner = cfg.ownerEmail?.takeIf { it.isNotBlank() }
        val isOwner = owner != null && users.byId(userId)?.email.equals(owner, ignoreCase = true)
        return when {
            own == null -> if (isOwner) cfg.ntfyTopic?.let { it to true } else null
            isOwner -> own to true
            own == cfg.ntfyTopic -> null
            else -> own to false
        }
    }
}
