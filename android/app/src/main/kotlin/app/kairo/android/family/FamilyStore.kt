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
 * Familia del usuario en caché, para que las pantallas traduzcan `ownerId`, `assigneeId` y
 * `createdBy` a nombres sin pedirla cada vez. Se recarga al abrir la app, al iniciar sesión y
 * cuando llega `entity.changed` con `family`; la última copia queda en disco.
 */
class FamilyStore(private val api: KairoApi, private val prefs: AppPrefs, private val scope: CoroutineScope) {
    private val _info = MutableStateFlow<FamilyInfo?>(null)
    val info: StateFlow<FamilyInfo?> = _info

    /** Al cambiar la sesión: usa la copia en disco si es de este usuario y la actualiza del servidor. */
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
