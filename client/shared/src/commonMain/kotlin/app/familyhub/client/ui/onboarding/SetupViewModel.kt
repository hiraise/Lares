package app.familyhub.client.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.familyhub.client.auth.SessionManager
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.field_passwords_differ
import app.familyhub.client.ui.common.FieldError
import app.familyhub.client.ui.common.UiError
import app.familyhub.client.ui.common.fieldErrors
import app.familyhub.client.ui.common.toUiError
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.validation.ValidationErrors
import app.familyhub.contract.validation.Validators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Defaults of the setup form taken from the device: time zone, currency by region, language. */
data class SetupDefaults(
    val timeZone: String,
    val currency: String,
    val language: String,
)

/** Field keys are the names of `SetupRequest` fields, so server field errors land on the same inputs. */
object SetupFields {
    const val SETUP_CODE = "setupCode"
    const val HOUSEHOLD_NAME = "householdName"
    const val TIME_ZONE = "timeZone"
    const val CURRENCY = "currency"
    const val LANGUAGE = "defaultLanguage"
    const val DISPLAY_NAME = "displayName"
    const val LOGIN = "login"
    const val PASSWORD = "password"
    const val PASSWORD_REPEAT = "passwordRepeat"
}

data class SetupUiState(
    val setupCode: String = "",
    val householdName: String = "",
    val timeZone: String,
    val currency: String,
    val language: String,
    val displayName: String = "",
    val login: String = "",
    val password: String = "",
    val passwordRepeat: String = "",
    val fieldErrors: Map<String, FieldError> = emptyMap(),
    val loading: Boolean = false,
    val error: UiError? = null,
)

/** FND-001: first-run setup of a new server, one scrolling form. Success switches the app to the main graph. */
class SetupViewModel(
    private val sessionManager: SessionManager,
    defaults: SetupDefaults,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(SetupUiState(timeZone = defaults.timeZone, currency = defaults.currency, language = defaults.language))
    val state: StateFlow<SetupUiState> = mutableState.asStateFlow()

    fun onSetupCodeChange(value: String) = edit(SetupFields.SETUP_CODE) { copy(setupCode = value) }

    fun onHouseholdNameChange(value: String) = edit(SetupFields.HOUSEHOLD_NAME) { copy(householdName = value) }

    fun onTimeZoneChange(value: String) = edit(SetupFields.TIME_ZONE) { copy(timeZone = value) }

    fun onCurrencyChange(value: String) = edit(SetupFields.CURRENCY) { copy(currency = value.uppercase().take(3)) }

    fun onLanguageChange(value: String) = edit(SetupFields.LANGUAGE) { copy(language = value) }

    fun onDisplayNameChange(value: String) = edit(SetupFields.DISPLAY_NAME) { copy(displayName = value) }

    fun onLoginChange(value: String) = edit(SetupFields.LOGIN) { copy(login = value) }

    fun onPasswordChange(value: String) = edit(SetupFields.PASSWORD) { copy(password = value) }

    fun onPasswordRepeatChange(value: String) = edit(SetupFields.PASSWORD_REPEAT) { copy(passwordRepeat = value) }

    fun submit() {
        val form = mutableState.value
        if (form.loading) return
        val errors = validate(form)
        if (errors.isNotEmpty()) {
            mutableState.update { it.copy(fieldErrors = errors, error = null) }
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                sessionManager.setup(
                    setupCode = form.setupCode,
                    householdName = form.householdName.trim(),
                    timeZone = form.timeZone,
                    currency = form.currency,
                    defaultLanguage = form.language,
                    displayName = form.displayName.trim(),
                    login = form.login,
                    password = form.password,
                )
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
        change: SetupUiState.() -> SetupUiState,
    ) = mutableState.update { it.change().copy(fieldErrors = it.fieldErrors - field, error = null) }

    companion object {
        fun validate(form: SetupUiState): Map<String, FieldError> {
            val errors =
                ValidationErrors()
                    .add(SetupFields.SETUP_CODE, if (form.setupCode.isBlank()) FieldErrorCode.REQUIRED else null)
                    .add(SetupFields.HOUSEHOLD_NAME, Validators.householdName(form.householdName))
                    .add(SetupFields.TIME_ZONE, Validators.timeZone(form.timeZone))
                    .add(SetupFields.CURRENCY, Validators.currency(form.currency))
                    .add(SetupFields.LANGUAGE, Validators.language(form.language))
                    .add(SetupFields.DISPLAY_NAME, Validators.personName(form.displayName))
                    .add(SetupFields.LOGIN, Validators.login(form.login))
                    .add(SetupFields.PASSWORD, Validators.password(form.password))
                    .fields
                    .mapValues { (_, code) -> FieldError.of(code)!! }
            return errors + passwordRepeatError(form.password, form.passwordRepeat)
        }
    }
}

/** Client-only check of the repeated password, keyed by [SetupFields.PASSWORD_REPEAT]. */
internal fun passwordRepeatError(
    password: String,
    repeat: String,
): Map<String, FieldError> =
    if (password.isNotEmpty() && password != repeat) {
        mapOf(SetupFields.PASSWORD_REPEAT to FieldError(Res.string.field_passwords_differ))
    } else {
        emptyMap()
    }
