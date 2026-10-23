package app.familyhub.client.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.familyhub.client.auth.SessionManager
import app.familyhub.client.auth.SessionState
import app.familyhub.client.ui.common.FieldError
import app.familyhub.client.ui.common.UiError
import app.familyhub.client.ui.common.attempt
import app.familyhub.client.ui.common.toUiError
import app.familyhub.contract.api.FieldErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val serverUrl: String = "",
    val login: String = "",
    val password: String = "",
    val loginError: FieldError? = null,
    val passwordError: FieldError? = null,
    val loading: Boolean = false,
    val error: UiError? = null,
    /** The server turned out not to be set up yet (app restarted before setup): the route opens Setup. */
    val needsSetup: Boolean = false,
)

/** FND-010: login with login + password. QR login and invitations start from here too (routes). */
class LoginViewModel(
    private val sessionManager: SessionManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginUiState(serverUrl = serverUrl()))
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

    init {
        // After an app restart only the address is known; ask the server whether it still needs setup.
        val url = mutableState.value.serverUrl
        if (url.isNotEmpty()) {
            viewModelScope.launch {
                val info = attempt { sessionManager.connect(url) }.getOrNull()
                if (info != null && !info.initialized) mutableState.update { it.copy(needsSetup = true) }
            }
        }
    }

    fun onSetupOpened() = mutableState.update { it.copy(needsSetup = false) }

    fun onLoginChange(value: String) = mutableState.update { it.copy(login = value, loginError = null, error = null) }

    fun onPasswordChange(value: String) = mutableState.update { it.copy(password = value, passwordError = null, error = null) }

    fun submit() {
        val form = mutableState.value
        if (form.loading) return
        // Only presence is checked here: the server decides whether the pair is right.
        val loginError = if (form.login.isBlank()) FieldError.of(FieldErrorCode.REQUIRED) else null
        val passwordError = if (form.password.isEmpty()) FieldError.of(FieldErrorCode.REQUIRED) else null
        if (loginError != null || passwordError != null) {
            mutableState.update { it.copy(loginError = loginError, passwordError = passwordError) }
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                sessionManager.login(form.login, form.password)
                mutableState.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(loading = false, error = e.toUiError()) }
            }
        }
    }

    private fun serverUrl(): String =
        when (val s = sessionManager.state.value) {
            is SessionState.ServerKnown -> s.serverUrl
            is SessionState.LoggedIn -> s.serverUrl
            SessionState.NoServer -> ""
        }
}
