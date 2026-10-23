package app.familyhub.client.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.familyhub.client.api.ApiException
import app.familyhub.client.auth.SessionManager
import app.familyhub.client.auth.SessionState
import app.familyhub.client.ui.common.FieldError
import app.familyhub.client.ui.common.UiError
import app.familyhub.client.ui.common.toUiError
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException

/** Where onboarding continues after the server answered. */
enum class AfterConnect { SETUP, LOGIN }

data class ServerUiState(
    val url: String = "",
    val urlError: FieldError? = null,
    val loading: Boolean = false,
    val error: UiError? = null,
    /** Set once the server is checked; the route navigates and calls [ServerViewModel.onNavigated]. */
    val next: AfterConnect? = null,
)

/** FND-060: the server address, typed or prefilled. */
class ServerViewModel(
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(ServerUiState(url = (sessionManager.state.value as? SessionState.ServerKnown)?.serverUrl.orEmpty()))
    val state: StateFlow<ServerUiState> = mutableState.asStateFlow()

    fun onUrlChange(value: String) = mutableState.update { it.copy(url = value, urlError = null, error = null) }

    fun connect() {
        val current = mutableState.value
        if (current.loading) return
        if (current.url.isBlank()) {
            mutableState.update { it.copy(urlError = FieldError.of(FieldErrorCode.REQUIRED)) }
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val info = sessionManager.connect(current.url)
                val next = if (info.initialized) AfterConnect.LOGIN else AfterConnect.SETUP
                mutableState.update { it.copy(loading = false, next = next) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, error = connectError(e)) }
            }
        }
    }

    fun onNavigated() = mutableState.update { it.copy(next = null) }

    companion object {
        /** The address may answer with anything: only our own error body or a reachable server says more than "unreachable". */
        fun connectError(e: Throwable): UiError =
            when {
                e is ApiException && e.code == ErrorCode.PROTOCOL_UNSUPPORTED -> UiError.Api(ErrorCode.PROTOCOL_UNSUPPORTED)
                e is ApiException && e.code == ErrorCode.RATE_LIMITED -> UiError.Api(ErrorCode.RATE_LIMITED)
                e is ApiException -> UiError.NotFamilyHub
                e is SerializationException || e is ContentConvertException || e is NoTransformationFoundException -> UiError.NotFamilyHub
                else -> e.toUiError()
            }
    }
}
