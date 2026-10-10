package app.kairo.android.push

import app.kairo.android.graph
import app.kairo.android.sync.ServerEvents
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives the own push through the distributor (UnifiedPush). The content is the same
 * `{type, data}` as the WebSocket, already decrypted by the connector, and goes through the same handler.
 */
class KairoPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // Without keys the server cannot encrypt (RFC 8291): this only happens with very old distributors
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
