package app.kairo.android.ui.common

import android.content.Context
import androidx.annotation.StringRes
import app.kairo.android.R
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

/** User-facing text for an error, in the app language. Server-written messages are shown as they come. */
fun Throwable.friendly(context: Context): String = when (this) {
    is ApiException -> when {
        status == 429 -> context.getString(R.string.error_too_many_attempts)
        else -> when (kind) {
            ApiException.Kind.Server -> message
            ApiException.Kind.Http -> context.getString(R.string.error_http, status)
            ApiException.Kind.NoServer -> context.getString(R.string.error_no_server)
            ApiException.Kind.NoSession -> context.getString(R.string.error_no_session)
            ApiException.Kind.SessionExpired -> context.getString(R.string.error_session_expired)
            ApiException.Kind.InviteNotFound -> context.getString(R.string.error_invite_not_found)
            ApiException.Kind.MissingRefreshToken -> context.getString(R.string.error_no_refresh_token)
            ApiException.Kind.SpeechFailed -> context.getString(R.string.error_speech_failed)
        }
    }
    is java.net.UnknownHostException, is java.net.ConnectException -> context.getString(R.string.error_no_connection)
    is java.net.SocketTimeoutException -> context.getString(R.string.error_timeout)
    else -> message ?: context.getString(R.string.error_generic)
}

/**
 * ViewModel for a server collection. It reloads by itself when `entity.changed` arrives
 * over the WebSocket for any of [entities].
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
                _state.update { it.copy(loading = false, error = e.friendly(graph.app)) }
            }
        }
    }

    /** Runs a change and reloads; if it fails, shows a message. */
    protected fun mutate(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                success?.let { _messages.tryEmit(it) }
                refresh(silent = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.friendly(graph.app))
            }
        }
    }

    protected fun updateData(transform: (T) -> T) = _state.update { s -> s.data?.let { s.copy(data = transform(it)) } ?: s }

    protected fun say(message: String) { _messages.tryEmit(message) }

    /** Text from string resources in the current app language. */
    protected fun str(@StringRes id: Int, vararg args: Any): String = graph.app.getString(id, *args)

    protected fun say(@StringRes id: Int, vararg args: Any) = say(str(id, *args))

    protected fun Throwable.friendly(): String = friendly(graph.app)
}
