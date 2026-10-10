package app.kairo.android.family

import app.kairo.android.data.AppPrefs
import app.kairo.core.model.FamilyInfo
import app.kairo.core.net.KairoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The user's family in cache, so screens can translate `ownerId`, `assigneeId` and
 * `createdBy` to names without requesting it every time. It reloads when opening the app, on sign-in and
 * when `entity.changed` arrives with `family`; the last copy is kept on disk.
 */
class FamilyStore(private val api: KairoApi, private val prefs: AppPrefs, private val scope: CoroutineScope) {
    private val _info = MutableStateFlow<FamilyInfo?>(null)
    val info: StateFlow<FamilyInfo?> = _info

    /** When the session changes: uses the copy on disk if it belongs to this user and refreshes it from the server. */
    suspend fun onSession(userId: String?) {
        if (userId == null) { clear(); return }
        if (_info.value?.me != userId) {
            _info.value = prefs.familyCache()
                ?.let { runCatching { api.json.decodeFromString(FamilyInfo.serializer(), it) }.getOrNull() }
                ?.takeIf { it.me == userId }
        }
        refresh()
    }

    fun refresh(): Job = scope.launch {
        runCatching { api.family() }.onSuccess { set(it) }
    }

    fun set(info: FamilyInfo) {
        _info.value = info
        scope.launch { runCatching { prefs.saveFamilyCache(api.json.encodeToString(FamilyInfo.serializer(), info)) } }
    }

    fun clear() {
        _info.value = null
        scope.launch { prefs.saveFamilyCache(null) }
    }

    fun follow(changes: Flow<String>) = scope.launch { changes.filter { it == "family" }.collect { refresh() } }

    fun nameOf(userId: String?): String? = _info.value?.member(userId)?.displayName

    fun firstName(userId: String?): String? = nameOf(userId)?.let(::firstNameOf)

    companion object {
        fun firstNameOf(name: String): String = name.trim().substringBefore(' ').ifBlank { name }
    }
}
