package app.kairo.android

import app.kairo.core.net.KairoApi
import java.net.URI
import java.net.URLDecoder

/**
 * A family invite link that opened the app.
 *
 * Canonical form: `https://host/join?code=ABCD-2345`.
 * Legacy form (still accepted): `https://host/unirse?codigo=ABCD-2345`.
 * Both query parameter names are accepted on both paths, so old shared messages keep working.
 *
 * @property server origin of the server that issued the link (`https://host`), or null when the link has no host.
 * @property code normalized code `XXXX-XXXX`.
 */
data class InviteLink(val server: String?, val code: String) {
    companion object {
        private val PATHS = setOf("/join", "/unirse")
        private val CODE_PARAMS = listOf("code", "codigo")

        fun parse(raw: String?): InviteLink? {
            if (raw.isNullOrBlank()) return null
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            if (uri.path?.trimEnd('/') !in PATHS) return null
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                val parts = pair.split('=', limit = 2)
                if (parts.size == 2) parts[0] to runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrDefault(parts[1]) else null
            }.toMap()
            val code = CODE_PARAMS.firstNotNullOfOrNull { params[it] }?.let(KairoApi::normalizeCode)?.takeIf { it.length == 9 } ?: return null
            val server = if (uri.scheme in setOf("http", "https") && uri.host != null) {
                "${uri.scheme}://${uri.host}" + if (uri.port != -1) ":${uri.port}" else ""
            } else {
                null
            }
            return InviteLink(server, code)
        }
    }
}
