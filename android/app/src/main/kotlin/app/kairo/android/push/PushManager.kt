package app.kairo.android.push

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

/** Estado del push propio que muestra Ajustes. */
data class PushState(
    /** Distribuidor elegido (paquete) y su nombre visible, p. ej. "ntfy". */
    val distributor: String? = null,
    val distributorLabel: String? = null,
    /** Distribuidores instalados aunque no se hayan elegido. */
    val installed: List<String> = emptyList(),
    /** Este teléfono entregó su endpoint al servidor. */
    val registered: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
)

/**
 * Push propio sin Firebase con UnifiedPush: un distribuidor (la app ntfy apuntando al ntfy del
 * usuario) entrega a Kairo el Web Push cifrado que envía el servidor. El conector descifra el
 * contenido (RFC 8291) y [KairoPushService] lo procesa igual que un evento del WebSocket.
 */
class PushManager(private val context: Context, private val api: KairoApi, private val scope: CoroutineScope) {
    private val store = context.getSharedPreferences("kairo_push", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PushState())
    val state: StateFlow<PushState> = _state

    /** Relee el distribuidor elegido y los instalados (al abrir Ajustes o volver de otra app). */
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

    /** Pide al distribuidor un endpoint para este teléfono; la respuesta llega a [onNewEndpoint]. */
    suspend fun register() {
        _state.update { it.copy(working = true, error = null) }
        val vapid = try {
            api.pushConfig().vapidPublicKey
        } catch (e: Exception) {
            fail(if (e is ApiException) e.message else "No hay conexión con tu servidor")
            return
        }
        runCatching { UnifiedPush.register(context, messageForDistributor = "Kairo", vapid = vapid) }
            .onFailure { fail("El distribuidor no aceptó la clave del servidor") }
    }

    /** Al abrir la app o iniciar sesión: renueva el registro si ya hay un distribuidor elegido. */
    fun registerIfAcknowledged() {
        refreshState()
        if (_state.value.distributor != null) scope.launch { register() }
    }

    /** Botón "Configurar": usa el distribuidor actual o el predeterminado (pregunta si hay varios). */
    fun configure(activity: Activity) {
        _state.update { it.copy(working = true, error = null) }
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            refreshState()
            if (ok) scope.launch { register() }
            else _state.update { it.copy(working = false, error = if (it.installed.isEmpty()) "No encontré ntfy ni otro distribuidor en este teléfono." else "No elegiste un distribuidor.") }
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
                fail(if (e is ApiException) "Tu servidor no aceptó el registro: ${e.message}" else "No hay conexión con tu servidor")
            }
        }
    }

    fun onRegistrationFailed(reason: FailedReason) {
        fail(
            when (reason) {
                FailedReason.NETWORK -> "El distribuidor no tiene conexión con tu servidor ntfy."
                FailedReason.ACTION_REQUIRED -> "Abre la app del distribuidor y revisa su configuración."
                FailedReason.VAPID_REQUIRED -> "El distribuidor pide una clave VAPID; vuelve a tocar Configurar."
                else -> "El distribuidor no pudo registrar a Kairo."
            },
        )
    }

    /** El distribuidor retiró el endpoint (desinstalado o cambiado): el servidor deja de usarlo. */
    fun onUnregistered() {
        store.edit().remove(KEY_ENDPOINT).apply()
        refreshState()
        scope.launch { runCatching { api.unregisterPush() } }
    }

    /** Al cerrar sesión: el servidor olvida el endpoint y el distribuidor lo borra. */
    suspend fun unregister() {
        runCatching { api.unregisterPush() }
        runCatching { UnifiedPush.unregister(context) }
        store.edit().remove(KEY_ENDPOINT).apply()
        refreshState()
    }

    /** `POST /push/test`: cuántos dispositivos aceptaron el aviso de prueba. */
    suspend fun test(): Int = api.testPush()

    private fun fail(message: String) = _state.update { it.copy(working = false, error = message) }

    @Suppress("DEPRECATION") // la variante con ApplicationInfoFlags es de Android 13; minSdk es 31
    private fun label(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        private const val KEY_ENDPOINT = "endpoint"
    }
}
