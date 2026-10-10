package app.kairo.realtime

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
}

/** Bus en memoria por usuario. Suficiente para una sola instancia del servidor. */
class EventBus {
    private val flows = ConcurrentHashMap<UUID, MutableSharedFlow<ServerEvent>>()

    fun flow(userId: UUID): SharedFlow<ServerEvent> = flows.getOrPut(userId) { MutableSharedFlow(extraBufferCapacity = 64) }

    suspend fun publish(userId: UUID, event: ServerEvent) {
        (flows.getOrPut(userId) { MutableSharedFlow(extraBufferCapacity = 64) }).emit(event)
    }

    fun subscriberCount(userId: UUID): Int = flows[userId]?.subscriptionCount?.value ?: 0

    suspend fun entityChanged(userId: UUID, entity: String, id: UUID?, op: String) = publish(
        userId,
        ServerEvent(EventTypes.ENTITY_CHANGED, buildJsonObject {
            put("entity", entity)
            id?.let { put("id", it.toString()) }
            put("op", op)
        }),
    )
}
