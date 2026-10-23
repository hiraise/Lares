package app.familyhub.client.ui.common

import app.familyhub.client.api.ApiException
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import kotlinx.coroutines.CancellationException

/** An error a screen shows. Field errors go next to their fields; this is the message for the whole form. */
sealed interface UiError {
    data class Api(
        val code: ErrorCode,
    ) : UiError

    /** The server could not be reached (no network, timeout, DNS). */
    data object Network : UiError

    /** The address answered, but not like a FamilyHub server. */
    data object NotFamilyHub : UiError

    /** A scanned QR code is not a FamilyHub link. */
    data object NotFamilyHubQr : UiError
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
