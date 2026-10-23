package app.lares.client.ui.common

import app.lares.client.api.ApiException
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import kotlinx.coroutines.CancellationException

/** An error a screen shows. Field errors go next to their fields; this is the message for the whole form. */
sealed interface UiError {
    data class Api(
        val code: ErrorCode,
    ) : UiError

    /** The server could not be reached (no network, timeout, DNS). */
    data object Network : UiError

    /** The address answered, but not like a Lares server. */
    data object NotLares : UiError

    /** A scanned QR code is not a Lares link. */
    data object NotLaresQr : UiError
}

/** Field errors of a server answer, keyed by the request field names. */
val Throwable.fieldErrors: Map<String, FieldErrorCode>
    get() = (this as? ApiException)?.fields.orEmpty()

/** Maps a failure of an API call to what the user sees. Rethrows cancellation. */
fun Throwable.toUiError(): UiError {
    if (this is CancellationException) throw this
    return when (this) {
        is ApiException -> UiError.Api(code)
        else -> UiError.Network
    }
}

/** Runs [block]; failures become a [UiError] (cancellation is rethrown). */
suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
