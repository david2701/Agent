package app.kairo.services

import app.kairo.domain.ActivityRepo
import app.kairo.domain.Embedder
import app.kairo.domain.EntityTypes
import app.kairo.domain.Kinds
import app.kairo.domain.RemindersRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.UsersRepo
import app.kairo.integrations.GoogleCalendarService
import app.kairo.integrations.NtfyNotifier
import app.kairo.realtime.EventBus
import app.kairo.realtime.EventTypes
import app.kairo.realtime.ServerEvent
import app.kairo.util.zoneOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalTime

class Schedulers(
    private val scope: CoroutineScope,
    private val reminders: RemindersRepo,
    private val users: UsersRepo,
    private val settings: SettingsService,
    private val briefing: BriefingService,
    private val google: GoogleCalendarService,
    private val embedder: Embedder,
    private val activity: ActivityRepo,
    private val bus: EventBus,
    private val ntfy: NtfyNotifier,
    private val googleEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(Schedulers::class.java)

    fun start() {
        loop("recordatorios", 10_000) { fireReminders() }
        loop("resumen", 60_000) { briefings() }
        loop("embeddings", 10 * 60_000, initialDelay = 30_000) { embedder.backfill() }
        if (googleEnabled) loop("google", 5 * 60_000, initialDelay = 20_000) { googleSync() }
    }

    private fun loop(name: String, everyMs: Long, initialDelay: Long = 2_000, block: suspend () -> Unit) {
        scope.launch {
            delay(initialDelay)
            while (isActive) {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Tarea '{}' falló: {}", name, e.message)
                }
                delay(everyMs)
            }
        }
    }

    /** Dispara recordatorios vencidos: WebSocket a los dispositivos + ntfy como respaldo. */
    suspend fun fireReminders() {
        reminders.claimDue(Instant.now()) { r, userId ->
            val fireAt = r.nextFireAt ?: Instant.now()
            bus.publish(
                userId,
                ServerEvent(EventTypes.REMINDER_FIRE, buildJsonObject {
                    put("id", r.id.toString())
                    put("text", r.text)
                    put("fireAt", fireAt.toString())
                    put("recurring", r.rrule != null)
                }),
            )
            activity.record(userId, Kinds.FIRED, EntityTypes.REMINDER, r.id, "Sonó: ${r.text}", "system")
            val s = settings.get(userId)
            ntfy.send(s.ntfyTopic, "Kairo", r.text, tags = listOf("alarm_clock"))
        }
    }

    /** Resumen matutino a la hora configurada (zona del usuario), una vez al día. */
    suspend fun briefings() {
        for (user in users.all()) {
            val s = settings.get(user.id)
            if (!s.briefingEnabled) continue
            val zone = zoneOf(user.timezone)
            val now = Instant.now().atZone(zone)
            val target = runCatching { LocalTime.parse(s.briefingTime) }.getOrDefault(LocalTime.of(7, 0))
            val today = now.toLocalDate().toString()
            if (s.lastBriefingDate == today) continue
            if (now.toLocalTime().isBefore(target) || now.toLocalTime().isAfter(target.plusHours(2))) continue
            settings.setInternal(user.id, "lastBriefingDate", JsonPrimitive(today))
            val text = runCatching { briefing.generate(user) }.getOrElse { log.warn("No se pudo generar el resumen: {}", it.message); continue }
            bus.publish(user.id, ServerEvent(EventTypes.BRIEFING_READY, buildJsonObject { put("text", text); put("date", today) }))
            ntfy.send(s.ntfyTopic, "Tu día", text, tags = listOf("sunrise"), priority = 3)
        }
    }

    private suspend fun googleSync() {
        for (userId in google.connectedUsers()) {
            runCatching { google.sync(userId) }.onFailure { log.warn("Sync Google falló para {}: {}", userId, it.message) }
        }
    }
}
