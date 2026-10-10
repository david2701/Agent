package app.kairo.services

import app.kairo.domain.ActivityRepo
import app.kairo.domain.Embedder
import app.kairo.domain.EntityTypes
import app.kairo.domain.Kinds
import app.kairo.domain.RemindersRepo
import app.kairo.domain.SettingsService
import app.kairo.domain.UsersRepo
import app.kairo.integrations.GoogleCalendarService
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
    private val events: app.kairo.domain.EventsRepo,
    private val users: UsersRepo,
    private val settings: SettingsService,
    private val briefing: BriefingService,
    private val google: GoogleCalendarService,
    private val embedder: Embedder,
    private val activity: ActivityRepo,
    private val notifier: Notifier,
    private val notices: app.kairo.domain.NoticesRepo,
    private val googleEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(Schedulers::class.java)

    fun start() {
        loop("recordatorios", 10_000) { fireReminders() }
        loop("eventos", 30_000) { eventAlerts() }
        loop("resumen", 60_000) { briefings() }
        loop("embeddings", 10 * 60_000, initialDelay = 30_000) { embedder.backfill() }
        if (googleEnabled) loop("google", 5 * 60_000, initialDelay = 20_000) { googleSync() }
        loop("limpieza", 6 * 3600_000, initialDelay = 5 * 60_000) { notices.prune() }
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

    /** Dispara recordatorios vencidos: WebSocket, Web Push y ntfy (el teléfono además tiene su alarma local). */
    suspend fun fireReminders() {
        reminders.claimDue(Instant.now()) { r, userId ->
            val fireAt = r.nextFireAt ?: Instant.now()
            val event = ServerEvent(EventTypes.REMINDER_FIRE, buildJsonObject {
                put("id", r.id.toString())
                put("text", r.text)
                put("fireAt", fireAt.toString())
                put("recurring", r.rrule != null)
                r.createdBy?.let { put("createdBy", it.toString()) }
            })
            activity.record(userId, Kinds.FIRED, EntityTypes.REMINDER, r.id, "Sonó: ${r.text}", "system", alsoTo = listOfNotNull(r.createdBy))
            notifier.deliver(userId, event, "Kairo", r.text, tags = listOf("alarm_clock"))
        }
    }

    private var lastEventCheck: Instant = Instant.now().minusSeconds(60)
    private val sentAlerts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Avisos previos de eventos del calendario ("En 15 min: Cita con el dentista"). */
    suspend fun eventAlerts() {
        val now = Instant.now()
        val from = lastEventCheck
        lastEventCheck = now
        sentAlerts.entries.removeIf { now.toEpochMilli() - it.value > 2 * 86_400_000L }
        for (user in users.all()) {
            // Los eventos familiares avisan a cada miembro en su teléfono
            for (a in events.alerts(user.id, from, now)) {
                val key = "${user.id}:${a.eventId}:${a.startAt.epochSecond}:${a.minutes}"
                if (sentAlerts.putIfAbsent(key, now.toEpochMilli()) != null) continue
                val event = ServerEvent(EventTypes.EVENT_ALERT, buildJsonObject {
                    put("id", a.eventId.toString()); put("title", a.title); put("startAt", a.startAt.toString())
                    put("alertAt", a.alertAt.toString()); put("minutes", a.minutes); put("location", a.location); put("shared", a.shared)
                })
                notifier.deliver(user.id, event, a.title, if (a.minutes == 0) "Empieza ahora" else "Empieza en ${a.minutes} min", tags = listOf("calendar"))
            }
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
            notifier.deliver(
                user.id, ServerEvent(EventTypes.BRIEFING_READY, buildJsonObject { put("text", text); put("date", today) }),
                "Tu día", text, tags = listOf("sunrise"), priority = 3,
            )
        }
    }

    private suspend fun googleSync() {
        for (userId in google.connectedUsers()) {
            runCatching { google.sync(userId) }.onFailure { log.warn("Sync Google falló para {}: {}", userId, it.message) }
        }
    }
}
