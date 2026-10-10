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

/** Event sent over WebSocket to the connected devices. */
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
 * In-memory bus per user. Enough for a single server instance.
 *
 * Publishing never waits: if a slow subscriber (a WebSocket the client does not read) fills the buffer, that subscriber
 * loses the oldest events (DROP_OLDEST) instead of slowing down the publisher (the reminder schedulers
 * and the whole family's notices). Clients resynchronize on their own, so losing an `entity.changed` is harmless.
 */
class EventBus {
    private val flows = ConcurrentHashMap<UUID, MutableSharedFlow<ServerEvent>>()

    private fun of(userId: UUID): MutableSharedFlow<ServerEvent> =
        flows.getOrPut(userId) { MutableSharedFlow(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

    fun flow(userId: UUID): SharedFlow<ServerEvent> = of(userId)

    @Suppress("RedundantSuspendModifier") // stays suspend for compatibility with its callers
    suspend fun publish(userId: UUID, event: ServerEvent) {
        // With DROP_OLDEST, tryEmit always succeeds and never suspends
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
