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
    /** Continuous listening for the wake phrase with the foreground service. */
    val wakeWordEnabled: Boolean = false,
    /** After a question from Kairo, listen for the answer without repeating the wake phrase. */
    val followUp: Boolean = true,
    /** Read typed chat replies aloud. */
    val speakChatReplies: Boolean = false,
    /** Short sounds when listening starts and ends. */
    val earcons: Boolean = true,
    /** Light service that keeps the WebSocket open to receive notices without ntfy or the wake phrase. */
    val directConnection: Boolean = false,
)

/** Preferences and local caches of the phone (they do not travel to the server). */
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

    /** Last family received: names appear instantly, even offline. */
    suspend fun saveFamilyCache(json: String?) = context.appStore.edit { if (json == null) it.remove(FAMILY) else it[FAMILY] = json }
    suspend fun familyCache(): String? = context.appStore.data.first()[FAMILY]

    /** Last "Today" received, for the widget. */
    suspend fun saveTodayCache(json: String) = context.appStore.edit { it[TODAY] = json }
    suspend fun todayCache(): String? = context.appStore.data.first()[TODAY]

    /** Ids of scheduled local alarms (to cancel them when they change). */
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

/** Log of notices already shown: avoids duplicating the local alarm's and the WebSocket's. */
class FiredLog(context: Context) {
    private val prefs = context.getSharedPreferences("kairo_fired", Context.MODE_PRIVATE)

    @Synchronized
    fun markIfNew(reminderId: String, fireAtMillis: Long): Boolean {
        val key = "$reminderId@${fireAtMillis / 60_000}"
        if (prefs.contains(key)) return false
        val now = System.currentTimeMillis()
        val editor = prefs.edit().putLong(key, now)
        // Clean up entries older than 3 days
        prefs.all.forEach { (k, v) -> if (v is Long && now - v > 3 * 86_400_000L) editor.remove(k) }
        editor.apply()
        return true
    }

    /** Family notices: the same id arrives by WebSocket, push and sync; it is shown once. */
    fun markNoticeIfNew(noticeId: String): Boolean = markIfNew("notice:$noticeId", 0)
}
