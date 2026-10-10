package app.kairo.android.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kairo.android.AppGraph
import app.kairo.core.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Loadable<T>(val data: T? = null, val loading: Boolean = true, val error: String? = null)

fun Throwable.friendly(): String = when (this) {
    is ApiException -> message
    is java.net.UnknownHostException -> "No hay conexión con el servidor"
    is java.net.ConnectException -> "No hay conexión con el servidor"
    is java.net.SocketTimeoutException -> "El servidor tardó demasiado"
    else -> message ?: "Algo salió mal"
}

/**
 * ViewModel de una colección del servidor. Se recarga solo cuando llega `entity.changed`
 * por WebSocket para cualquiera de [entities].
 */
abstract class ResourceViewModel<T>(protected val graph: AppGraph, entities: Set<String>) : ViewModel() {
    private val _state = MutableStateFlow(Loadable<T>())
    val state: StateFlow<Loadable<T>> = _state
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    protected abstract suspend fun load(): T

    init {
        viewModelScope.launch { graph.realtime.changes.filter { it in entities }.collect { refresh(silent = true) } }
    }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = it.data == null, error = null) }
            try {
                val d = load()
                _state.value = Loadable(d, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.friendly()) }
            }
        }
    }

    /** Ejecuta un cambio y recarga; si falla, avisa con un mensaje. */
    protected fun mutate(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                success?.let { _messages.tryEmit(it) }
                refresh(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.friendly())
            }
        }
    }

    protected fun updateData(transform: (T) -> T) = _state.update { s -> s.data?.let { s.copy(data = transform(it)) } ?: s }

    protected fun say(message: String) { _messages.tryEmit(message) }
}
