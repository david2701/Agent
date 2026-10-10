package app.kairo.android.push

import app.kairo.android.R
import app.kairo.android.ui.common.friendly
import android.app.Activity
import android.content.Context
import app.kairo.core.net.ApiException
import app.kairo.core.net.KairoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush

/** State of the own push shown in Settings. */
data class PushState(
    /** Chosen distributor (package) and its display name, e.g. "ntfy". */
    val distributor: String? = null,
    val distributorLabel: String? = null,
    /** Installed distributors even if none was chosen. */
    val installed: List<String> = emptyList(),
    /** This phone gave its endpoint to the server. */
    val registered: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
)

/**
 * Own push without Firebase using UnifiedPush: a distributor (the ntfy app pointing at the user's
 * ntfy) delivers to Kairo the encrypted Web Push the server sends. The connector decrypts the
 * content (RFC 8291) and [KairoPushService] processes it like a WebSocket event.
 */
class PushManager(private val context: Context, private val api: KairoApi, private val scope: CoroutineScope) {
    private val store = context.getSharedPreferences("kairo_push", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PushState())
    val state: StateFlow<PushState> = _state

    /** Re-reads the chosen distributor and the installed ones (when opening Settings or coming back from another app). */
    fun refreshState() {
        val ack = runCatching { UnifiedPush.getAckDistributor(context) }.getOrNull()
        val installed = runCatching { UnifiedPush.getDistributors(context) }.getOrDefault(emptyList())
        _state.update {
            it.copy(
                distributor = ack,
                distributorLabel = ack?.let(::label),
                installed = installed,
                registered = ack != null && store.getString(KEY_ENDPOINT, null) != null,
            )
        }
    }

    /** Asks the distributor for an endpoint for this phone; the answer arrives at [onNewEndpoint]. */
    suspend fun register() {
        _state.update { it.copy(working = true, error = null) }
        val vapid = try {
            api.pushConfig().vapidPublicKey
        } catch (e: Exception) {
            fail(if (e is ApiException) e.friendly(context) else context.getString(R.string.push_err_no_connection))
            return
        }
        runCatching { UnifiedPush.register(context, messageForDistributor = "Kairo", vapid = vapid) }
            .onFailure { fail(context.getString(R.string.push_err_key_rejected)) }
    }

    /** On opening the app or signing in: renews the registration if a distributor is already chosen. */
    fun registerIfAcknowledged() {
        refreshState()
        if (_state.value.distributor != null) scope.launch { register() }
    }

    /** "Set up" button: uses the current or default distributor (asks if there are several). */
    fun configure(activity: Activity) {
        _state.update { it.copy(working = true, error = null) }
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            refreshState()
            if (ok) scope.launch { register() }
            else _state.update { it.copy(working = false, error = context.getString(if (it.installed.isEmpty()) R.string.push_err_none_installed else R.string.push_err_not_chosen)) }
        }
    }

    fun onNewEndpoint(endpoint: String, p256dh: String, auth: String) {
        scope.launch {
            try {
                api.registerPush(endpoint, p256dh, auth)
                store.edit().putString(KEY_ENDPOINT, endpoint).apply()
                refreshState()
                _state.update { it.copy(working = false, error = null) }
            } catch (e: Exception) {
                fail(if (e is ApiException) context.getString(R.string.push_err_server_rejected, e.friendly(context)) else context.getString(R.string.push_err_no_connection))
            }
        }
    }

    fun onRegistrationFailed(reason: FailedReason) {
        fail(
            when (reason) {
                FailedReason.NETWORK -> context.getString(R.string.push_fail_network)
                FailedReason.ACTION_REQUIRED -> context.getString(R.string.push_fail_action)
                FailedReason.VAPID_REQUIRED -> context.getString(R.string.push_fail_vapid)
                else -> context.getString(R.string.push_fail_other)
            },
        )
    }

    /** The distributor withdrew the endpoint (uninstalled or changed): the server stops using it. */
    fun onUnregistered() {
        store.edit().remove(KEY_ENDPOINT).apply()
        refreshState()
        scope.launch { runCatching { api.unregisterPush() } }
    }

    /** On sign-out: the server forgets the endpoint and the distributor deletes it. */
    suspend fun unregister() {
        runCatching { api.unregisterPush() }
        runCatching { UnifiedPush.unregister(context) }
        store.edit().remove(KEY_ENDPOINT).apply()
        refreshState()
    }

    /** `POST /push/test`: how many devices accepted the test notice. */
    suspend fun test(): Int = api.testPush()

    private fun fail(message: String) = _state.update { it.copy(working = false, error = message) }

    @Suppress("DEPRECATION") // the ApplicationInfoFlags variant is Android 13; minSdk is 31
    private fun label(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val KEY_ENDPOINT = "endpoint"
    }
}
