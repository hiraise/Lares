package app.familyhub.client.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.familyhub.client.auth.SessionManager
import app.familyhub.client.ui.common.UiError
import app.familyhub.client.ui.common.attempt
import app.familyhub.client.ui.common.toUiError
import app.familyhub.contract.DeepLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LinkUiState(
    val working: Boolean = true,
    val error: UiError? = null,
    /** For an invite: the code to continue with on the invite screen, once the server is connected. */
    val inviteCode: String? = null,
)

/**
 * Handles a scanned or opened `familyhub://` link: connects to its server, then logs in with the login code
 * (FND-014) or hands the invite code to the invite screen (FND-024).
 */
class LinkViewModel(
    private val sessionManager: SessionManager,
    private val link: DeepLink,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LinkUiState())
    val state: StateFlow<LinkUiState> = mutableState.asStateFlow()

    init {
        run()
    }

    fun run() {
        mutableState.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            val connected = attempt { sessionManager.connect(link.serverUrl) }
            connected.exceptionOrNull()?.let { e ->
                mutableState.update { it.copy(working = false, error = ServerViewModel.connectError(e)) }
                return@launch
            }
            when (link) {
                is DeepLink.Invite -> {
                    mutableState.update { it.copy(working = false, inviteCode = link.code) }
                }

                is DeepLink.Login -> {
                    // Success switches the app to the main graph; only failures stay here.
                    attempt { sessionManager.loginWithCode(link.code) }.exceptionOrNull()?.let { e ->
                        mutableState.update { it.copy(working = false, error = e.toUiError()) }
                    }
                }
            }
        }
    }

    fun onNavigated() = mutableState.update { it.copy(inviteCode = null) }
}
