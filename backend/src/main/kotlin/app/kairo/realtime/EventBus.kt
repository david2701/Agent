package app.kairo.realtime

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Evento enviado por WebSocket a los dispositivos conectados. */
@Serializable
data class ServerEvent(val type: String, val data: JsonObject = JsonObject(emptyMap()))

object EventTypes {
    const val ENTITY_CHANGED = "entity.changed"
    const val REMINDER_FIRE = "reminder.fire"
    const val EVENT_ALERT = "event.alert"
    const val BRIEFING_READY = "briefing.ready"
    const val RECORDING_UPDATED = "recording.updated"
    const val FAMILY_NOTICE = "family.notice"
}

/**
 * Bus en memoria por usuario. Suficiente para una sola instancia del servidor.
 *
 * Publicar nunca espera: si un suscriptor lento (un WebSocket que el cliente no lee) llena el búfer, ese suscriptor
 * pierde los eventos más viejos (DROP_OLDEST) en vez de frenar a quien publica (los planificadores de recordatorios
 * y avisos de toda la familia). Los clientes se resincronizan solos, así que perder un `entity.changed` es inocuo.
 */
class EventBus {
    private val flows = ConcurrentHashMap<UUID, MutableSharedFlow<ServerEvent>>()

    private fun of(userId: UUID): MutableSharedFlow<ServerEvent> =
        flows.getOrPut(userId) { MutableSharedFlow(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

    fun flow(userId: UUID): SharedFlow<ServerEvent> = of(userId)

    @Suppress("RedundantSuspendModifier") // se mantiene suspend por compatibilidad con quienes lo llaman
    suspend fun publish(userId: UUID, event: ServerEvent) {
        // Con DROP_OLDEST tryEmit siempre tiene éxito y nunca suspende
        of(userId).tryEmit(event)
    }

    fun subscriberCount(userId: UUID): Int = flows[userId]?.subscriptionCount?.value ?: 0

    private companion object {
        const val BUFFER = 64
    }

    suspend fun entityChanged(userIds: Collection<UUID>, entity: String, id: UUID?, op: String) =
        userIds.forEach { entityChanged(it, entity, id, op) }

    suspend fun entityChanged(userId: UUID, entity: String, id: UUID?, op: String) = publish(
        userId,
        ServerEvent(EventTypes.ENTITY_CHANGED, buildJsonObject {
            put("entity", entity)
            id?.let { put("id", it.toString()) }
            put("op", op)
        }),
    )
}
