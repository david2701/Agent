package app.kairo.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.appStore by preferencesDataStore(name = "kairo_app")

data class LocalSettings(
    /** Escucha continua de "Oye Kairo" con el servicio en primer plano. */
    val wakeWordEnabled: Boolean = false,
    /** Tras una pregunta de Kairo, escuchar la respuesta sin repetir "Oye Kairo". */
    val followUp: Boolean = true,
    /** Leer en voz alta las respuestas del chat escrito. */
    val speakChatReplies: Boolean = false,
    /** Sonidos breves al empezar y terminar de escuchar. */
    val earcons: Boolean = true,
    /** Servicio ligero que mantiene el WebSocket abierto para recibir avisos sin ntfy ni "Oye Kairo". */
    val directConnection: Boolean = false,
)

/** Preferencias y cachés locales del teléfono (no viajan al servidor). */
class AppPrefs(private val context: Context) {

    val settings: Flow<LocalSettings> = context.appStore.data.map {
        LocalSettings(
            wakeWordEnabled = it[WAKE] ?: false,
            followUp = it[FOLLOW] ?: true,
            speakChatReplies = it[SPEAK] ?: false,
            earcons = it[EARCONS] ?: true,
            directConnection = it[DIRECT] ?: false,
        )
    }

    suspend fun current(): LocalSettings = settings.first()

    suspend fun setWakeWord(v: Boolean) = context.appStore.edit { it[WAKE] = v }
    suspend fun setFollowUp(v: Boolean) = context.appStore.edit { it[FOLLOW] = v }
    suspend fun setSpeakChatReplies(v: Boolean) = context.appStore.edit { it[SPEAK] = v }
    suspend fun setEarcons(v: Boolean) = context.appStore.edit { it[EARCONS] = v }
    suspend fun setDirectConnection(v: Boolean) = context.appStore.edit { it[DIRECT] = v }

    /** Última familia recibida: los nombres aparecen al instante, incluso sin conexión. */
    suspend fun saveFamilyCache(json: String?) = context.appStore.edit { if (json == null) it.remove(FAMILY) else it[FAMILY] = json }
    suspend fun familyCache(): String? = context.appStore.data.first()[FAMILY]

    /** Último "Hoy" recibido, para el widget. */
    suspend fun saveTodayCache(json: String) = context.appStore.edit { it[TODAY] = json }
    suspend fun todayCache(): String? = context.appStore.data.first()[TODAY]

    /** Ids de alarmas locales programadas (para cancelarlas cuando cambian). */
    suspend fun scheduledAlarms(): Set<String> = context.appStore.data.first()[ALARMS]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    suspend fun setScheduledAlarms(ids: Set<String>) = context.appStore.edit { it[ALARMS] = ids.joinToString(",") }

    suspend fun geofences(): Set<String> = context.appStore.data.first()[GEOFENCES]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    suspend fun setGeofences(ids: Set<String>) = context.appStore.edit { it[GEOFENCES] = ids.joinToString(",") }

    companion object {
        private val WAKE = booleanPreferencesKey("wake_word")
        private val FOLLOW = booleanPreferencesKey("follow_up")
        private val SPEAK = booleanPreferencesKey("speak_chat")
        private val EARCONS = booleanPreferencesKey("earcons")
        private val DIRECT = booleanPreferencesKey("direct_connection")
        private val FAMILY = stringPreferencesKey("family_cache")
        private val TODAY = stringPreferencesKey("today_cache")
        private val ALARMS = stringPreferencesKey("scheduled_alarms")
        private val GEOFENCES = stringPreferencesKey("geofences")
    }
}

/** Registro de avisos ya mostrados: evita duplicar el de la alarma local y el del WebSocket. */
class FiredLog(context: Context) {
    private val prefs = context.getSharedPreferences("kairo_fired", Context.MODE_PRIVATE)

    @Synchronized
    fun markIfNew(reminderId: String, fireAtMillis: Long): Boolean {
        val key = "$reminderId@${fireAtMillis / 60_000}"
        if (prefs.contains(key)) return false
        val now = System.currentTimeMillis()
        val editor = prefs.edit().putLong(key, now)
        // Limpieza de entradas de más de 3 días
        prefs.all.forEach { (k, v) -> if (v is Long && now - v > 3 * 86_400_000L) editor.remove(k) }
        editor.apply()
        return true
    }

    /** Avisos de la familia: el mismo id llega por WebSocket, push y sincronización; se muestra una vez. */
    fun markNoticeIfNew(noticeId: String): Boolean = markIfNew("notice:$noticeId", 0)
}
