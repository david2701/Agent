package app.kairo.android.push

import app.kairo.android.graph
import app.kairo.android.sync.ServerEvents
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Recibe el push propio a través del distribuidor (UnifiedPush). El contenido es el mismo
 * `{type, data}` del WebSocket, ya descifrado por el conector, y pasa por el mismo manejador.
 */
class KairoPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // Sin claves el servidor no puede cifrar (RFC 8291): solo pasa con distribuidores muy antiguos
        val keys = endpoint.pubKeySet ?: return graph.push.onRegistrationFailed(FailedReason.INTERNAL_ERROR)
        graph.push.onNewEndpoint(endpoint.url, keys.pubKey, keys.auth)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val g = graph
        ServerEvents.decode(g.api.json, message.content)?.let(g.events::handle)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = graph.push.onRegistrationFailed(reason)

    override fun onUnregistered(instance: String) = graph.push.onUnregistered()
}
