package app.kairo.wear

import android.content.Context
import app.kairo.core.net.ApiException

/** User-facing text for an error, in the watch language. Server-written messages are shown as they come. */
fun Throwable.friendly(context: Context): String = when (this) {
    is ApiException -> when {
        status == 429 -> context.getString(R.string.wear_err_too_many)
        kind == ApiException.Kind.Server -> message
        kind == ApiException.Kind.Http -> context.getString(R.string.wear_err_http, status)
        kind == ApiException.Kind.SessionExpired || kind == ApiException.Kind.NoSession -> context.getString(R.string.wear_err_session)
        else -> context.getString(R.string.wear_err_generic)
    }
    is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> context.getString(R.string.wear_offline)
    else -> message ?: context.getString(R.string.wear_err_generic)
}
