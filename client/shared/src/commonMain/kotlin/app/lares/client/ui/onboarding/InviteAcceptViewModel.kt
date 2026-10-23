package app.lares.client.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.auth.SessionManager
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.fieldErrors
import app.lares.client.ui.common.toUiError
import app.lares.contract.api.admin.InvitePreview
import app.lares.contract.validation.ValidationErrors
import app.lares.contract.validation.Validators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object InviteFields {
    const val DISPLAY_NAME = "displayName"
    const val LOGIN = "login"
    const val PASSWORD = "password"
}

data class InviteAcceptUiState(
    /** Null while the preview loads or when it failed ([previewError]). */
    val preview: InvitePreview? = null,
    val previewError: UiError? = null,
    val displayName: String = "",
    val login: String = "",
    val password: String = "",
    val passwordRepeat: String = "",
    val fieldErrors: Map<String, FieldError> = emptyMap(),
    val loading: Boolean = false,
    val error: UiError? = null,
)

/** FND-024: preview an invitation, then create the own account and join. */
class InviteAcceptViewModel(
    private val sessionManager: SessionManager,
    private val code: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InviteAcceptUiState())
    val state: StateFlow<InviteAcceptUiState> = mutableState.asStateFlow()

    init {
        loadPreview()
    }

    fun loadPreview() {
        mutableState.update { it.copy(previewError = null) }
        viewModelScope.launch {
            try {
                val preview = sessionManager.previewInvite(code)
                mutableState.update { it.copy(preview = preview) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(previewError = e.toUiError()) }
            }
        }
    }

    fun onDisplayNameChange(value: String) = edit(InviteFields.DISPLAY_NAME) { copy(displayName = value) }

    fun onLoginChange(value: String) = edit(InviteFields.LOGIN) { copy(login = value) }

    fun onPasswordChange(value: String) = edit(InviteFields.PASSWORD) { copy(password = value) }

    fun onPasswordRepeatChange(value: String) = edit(SetupFields.PASSWORD_REPEAT) { copy(passwordRepeat = value) }

    fun submit() {
        val form = mutableState.value
        if (form.loading || form.preview == null) return
        val errors =
            ValidationErrors()
                .add(InviteFields.DISPLAY_NAME, Validators.personName(form.displayName))
                .add(InviteFields.LOGIN, Validators.login(form.login))
                .add(InviteFields.PASSWORD, Validators.password(form.password))
                .fields
                .mapValues { (_, code) -> FieldError.of(code)!! } + passwordRepeatError(form.password, form.passwordRepeat)
        if (errors.isNotEmpty()) {
            mutableState.update { it.copy(fieldErrors = errors, error = null) }
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                sessionManager.acceptInvite(code, form.displayName.trim(), form.login, form.password)
                mutableState.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fields = e.fieldErrors.mapValues { (_, code) -> FieldError.of(code)!! }
                mutableState.update { it.copy(loading = false, error = e.toUiError(), fieldErrors = fields) }
            }
        }
    }

    private fun edit(
        field: String,
        change: InviteAcceptUiState.() -> InviteAcceptUiState,
    ) = mutableState.update { it.change().copy(fieldErrors = it.fieldErrors - field, error = null) }
}
