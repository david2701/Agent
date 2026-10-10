package app.kairo.services

import app.kairo.config.AppConfig
import app.kairo.domain.FamilyRepo
import app.kairo.domain.NoticesRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.UsersRepo
import app.kairo.integrations.NtfyNotifier
import app.kairo.integrations.PushService
import app.kairo.realtime.EventBus
import app.kairo.realtime.EventTypes
import app.kairo.realtime.ServerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Un solo lugar para avisar a una persona, sin Firebase:
 * 1. WebSocket a sus dispositivos conectados (dashboard, app abierta o escuchando).
 * 2. Web Push cifrado a sus teléfonos con UnifiedPush (llega con la app cerrada).
 * 3. ntfy, si configuró un tema propio.
 * Los teléfonos deduplican por id, así que recibir el mismo aviso por dos vías no lo muestra dos veces.
 */
class Notifier(
    private val scope: CoroutineScope,
    private val cfg: AppConfig,
    private val bus: EventBus,
    private val push: PushService,
    private val ntfy: NtfyNotifier,
    private val notices: NoticesRepo,
    private val users: UsersRepo,
    private val family: FamilyRepo,
    private val settings: SettingsService,
) {
    private val log = LoggerFactory.getLogger(Notifier::class.java)

    /** Aviso del sistema para un usuario (recordatorio, evento próximo, resumen del día). */
    suspend fun deliver(userId: UUID, event: ServerEvent, title: String, body: String, tags: List<String> = emptyList(), priority: Int = 4) {
        bus.publish(userId, event)
        scope.launch {
            runCatching { push.send(userId, event) }.onFailure { log.warn("Push falló: {}", it.message) }
            runCatching { ntfyTopic(userId)?.let { ntfy.send(it, title, body, tags = tags, priority = priority) } }
        }
    }

    /**
     * Avisa a [recipients] (sin incluir a quien hizo la acción) y guarda el aviso para quien no esté conectado.
     * [title] ya viene redactado ("Valentina agregó un evento familiar").
     */
    suspend fun family(actorId: UUID, recipients: Collection<UUID>, kind: String, title: String, body: String, entityType: String? = null, entityId: UUID? = null) {
        for (r in recipients.toSet() - actorId) {
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

    /** Avisa a toda la familia menos a quien actuó. */
    suspend fun everyone(actorId: UUID, kind: String, title: String, body: String, entityType: String? = null, entityId: UUID? = null) =
        family(actorId, family.memberIds(actorId), kind, title, body, entityType, entityId)

    /** Todos los miembros del hogar del usuario, él incluido. */
    suspend fun members(userId: UUID): List<UUID> = family.memberIds(userId)

    /** Nombre corto para los avisos ("Valentina" en vez de "Valentina Gómez"). */
    suspend fun firstName(userId: UUID): String =
        users.byId(userId)?.displayName?.trim()?.split(' ')?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Alguien"

    private suspend fun ntfyTopic(userId: UUID): String? {
        settings.get(userId).ntfyTopic?.takeIf { it.isNotBlank() }?.let { return it }
        // El tema global de .env es del propietario; los demás miembros configuran el suyo
        val owner = cfg.ownerEmail ?: return null
        return if (users.byId(userId)?.email.equals(owner, ignoreCase = true)) cfg.ntfyTopic else null
    }
}
