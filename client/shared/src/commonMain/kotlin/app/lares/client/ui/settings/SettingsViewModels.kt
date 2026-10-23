package app.lares.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.api.LaresApi
import app.lares.client.auth.SessionManager
import app.lares.client.auth.SessionState
import app.lares.client.data.PersonsRepository
import app.lares.client.data.PersonsWriter
import app.lares.client.platform.AppInfo
import app.lares.client.platform.AppLanguage
import app.lares.client.platform.AppPreferences
import app.lares.client.platform.AppearanceSettings
import app.lares.client.platform.ThemeMode
import app.lares.client.sync.LocalWriteException
import app.lares.client.sync.SyncEngine
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.fieldErrors
import app.lares.client.ui.common.toUiError
import app.lares.client.ui.onboarding.SetupFields
import app.lares.client.ui.onboarding.passwordRepeatError
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.admin.CreateLoginCodeRequest
import app.lares.contract.api.auth.ChangePasswordRequest
import app.lares.contract.api.auth.Me
import app.lares.contract.api.auth.SessionInfo
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PaletteColors
import app.lares.contract.entities.PersonData
import app.lares.contract.validation.Validators
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun SessionManager.me(): Me? = (state.value as? SessionState.LoggedIn)?.me

// Settings (FND-011, FND-013, FND-014, FND-061, FND-062).

data class SettingsUiState(
    val preferences: AppPreferences = AppPreferences(),
    val hasPassword: Boolean = false,
    val pendingChanges: Long = 0,
    val deleting: Boolean = false,
    val deleteError: UiError? = null,
    /** True once deletion succeeded; the session is gone and the app shows onboarding. */
    val deleted: Boolean = false,
)

class SettingsViewModel(
    private val sessionManager: SessionManager,
    private val appearance: AppearanceSettings,
    syncEngine: SyncEngine,
) : ViewModel() {
    private val deletion = MutableStateFlow(Triple(false, null as UiError?, false))

    val state: StateFlow<SettingsUiState> =
        combine(appearance.preferences, syncEngine.status, deletion) { prefs, sync, (deleting, error, deleted) ->
            SettingsUiState(
                prefs ?: AppPreferences(),
                sessionManager.me()?.hasPassword ?: false,
                sync.pendingCount,
                deleting,
                error,
                deleted,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsUiState(
                hasPassword =
                    sessionManager.me()?.hasPassword ?: false,
            ),
        )

    fun setTheme(theme: ThemeMode) = appearance.setTheme(theme)

    fun setLanguage(language: AppLanguage) = appearance.setLanguage(language)

    fun logout() {
        viewModelScope.launch { sessionManager.logout() }
    }

    fun deleteAccount(password: String?) {
        if (deletion.value.first) return
        deletion.value = Triple(true, null, false)
        viewModelScope.launch {
            val result = attempt { sessionManager.deleteAccount(password?.takeIf { it.isNotEmpty() }) }
            deletion.value = Triple(false, result.exceptionOrNull()?.toUiError(), result.isSuccess)
        }
    }

    fun clearDeleteError() = deletion.update { it.copy(second = null) }
}

data class ProfileUiState(
    val name: String = "",
    val savedName: String = "",
    val nameError: FieldError? = null,
    val login: String? = null,
    val saved: Boolean = false,
    /** The personal color (FND-034); changing it saves at once. */
    val color: PaletteColor? = null,
)

/** Own profile: the name is the own person, edited offline through the sync engine. */
class ProfileViewModel(
    sessionManager: SessionManager,
    private val persons: PersonsRepository,
    private val personsWriter: PersonsWriter,
) : ViewModel() {
    private val me = sessionManager.me()
    private val mutableState = MutableStateFlow(ProfileUiState(login = me?.login))
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()

    init {
        if (me != null) {
            viewModelScope.launch {
                persons.observe(me.personId).collect { person ->
                    val name = person?.data?.name ?: me.displayName
                    mutableState.update {
                        val synced = it.copy(color = person?.color ?: PaletteColors.fallbackFor(me.personId))
                        if (it.name == it.savedName) synced.copy(name = name, savedName = name) else synced.copy(savedName = name)
                    }
                }
            }
        }
    }

    fun onColor(value: PaletteColor) {
        val me = me ?: return
        mutableState.update { it.copy(color = value) }
        viewModelScope.launch {
            val current = persons.observe(me.personId).first()?.data ?: return@launch
            attempt { personsWriter.update(me.personId, current.copy(color = value)) }
        }
    }

    fun onNameChange(value: String) = mutableState.update { it.copy(name = value, nameError = null, saved = false) }

    fun save() {
        val me = me ?: return
        val name = mutableState.value.name.trim()
        val error = Validators.personName(name)
        if (error != null) {
            mutableState.update { it.copy(nameError = FieldError.of(error)) }
            return
        }
        viewModelScope.launch {
            val current = persons.observe(me.personId).first()?.data ?: PersonData(name = name)
            val result = attempt { personsWriter.update(me.personId, current.copy(name = name)) }
            val failure = result.exceptionOrNull()
            mutableState.update {
                if (failure is LocalWriteException) {
                    it.copy(nameError = FieldError.of(failure.fields["name"] ?: FieldErrorCode.UNKNOWN))
                } else {
                    it.copy(name = name, savedName = name, saved = failure == null)
                }
            }
        }
    }
}

data class ChangePasswordUiState(
    val hasPassword: Boolean = true,
    val current: String = "",
    val new: String = "",
    val repeat: String = "",
    val fieldErrors: Map<String, FieldError> = emptyMap(),
    val loading: Boolean = false,
    val error: UiError? = null,
    val done: Boolean = false,
)

class ChangePasswordViewModel(
    sessionManager: SessionManager,
    private val api: LaresApi,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChangePasswordUiState(hasPassword = sessionManager.me()?.hasPassword ?: false))
    val state: StateFlow<ChangePasswordUiState> = mutableState.asStateFlow()

    fun onCurrentChange(value: String) =
        mutableState.update { it.copy(current = value, fieldErrors = it.fieldErrors - CURRENT, error = null) }

    fun onNewChange(value: String) = mutableState.update { it.copy(new = value, fieldErrors = it.fieldErrors - NEW, error = null) }

    fun onRepeatChange(value: String) =
        mutableState.update { it.copy(repeat = value, fieldErrors = it.fieldErrors - SetupFields.PASSWORD_REPEAT, error = null) }

    fun submit() {
        val form = mutableState.value
        if (form.loading) return
        val errors =
            buildMap {
                if (form.hasPassword && form.current.isEmpty()) put(CURRENT, FieldError.of(FieldErrorCode.REQUIRED)!!)
                Validators.password(form.new)?.let { put(NEW, FieldError.of(it)!!) }
                putAll(passwordRepeatError(form.new, form.repeat))
            }
        if (errors.isNotEmpty()) {
            mutableState.update { it.copy(fieldErrors = errors) }
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = attempt { api.changePassword(ChangePasswordRequest(form.current.takeIf { form.hasPassword }, form.new)) }
            val failure = result.exceptionOrNull()
            mutableState.update {
                if (failure == null) {
                    it.copy(loading = false, done = true)
                } else {
                    val fields = failure.fieldErrors.mapKeys { (key, _) -> if (key == "newPassword") NEW else key }
                    it.copy(
                        loading = false,
                        error = failure.toUiError(),
                        fieldErrors =
                            fields.mapValues { (_, code) ->
                                FieldError.of(code)!!
                            },
                    )
                }
            }
        }
    }

    companion object {
        const val CURRENT = "currentPassword"
        const val NEW = "newPassword"
    }
}

data class LoginQrUiState(
    val payload: String? = null,
    val code: String? = null,
    val remaining: Duration = Duration.ZERO,
    val expired: Boolean = false,
    val error: UiError? = null,
)

/**
 * FND-014: a login code as a QR, valid for five minutes, with a countdown. For the caller, or with [memberId] for
 * another member (needs `household.manage`).
 */
class LoginQrViewModel(
    private val api: LaresApi,
    private val clock: Clock,
    private val memberId: String? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginQrUiState())
    val state: StateFlow<LoginQrUiState> = mutableState.asStateFlow()
    private var countdown: Job? = null

    init {
        create()
    }

    fun create() {
        countdown?.cancel()
        mutableState.value = LoginQrUiState()
        countdown =
            viewModelScope.launch {
                val result = attempt { api.createLoginCode(CreateLoginCodeRequest(memberId)) }
                val created =
                    result.getOrElse { e ->
                        mutableState.value = LoginQrUiState(error = e.toUiError())
                        return@launch
                    }
                mutableState.value = LoginQrUiState(payload = created.payload, code = created.code)
                tick(created.expiresAt)
            }
    }

    private suspend fun tick(expiresAt: Instant) {
        while (currentCoroutineContext().isActive) {
            val remaining = expiresAt - clock.now()
            if (remaining <= Duration.ZERO) {
                mutableState.update { it.copy(remaining = Duration.ZERO, expired = true) }
                return
            }
            mutableState.update { it.copy(remaining = remaining) }
            delay(1.seconds)
        }
    }
}

data class DevicesUiState(
    val sessions: List<SessionInfo>? = null,
    val loading: Boolean = true,
    val error: UiError? = null,
)

/** FND-011: own sessions; revoking one logs that device out. */
class DevicesViewModel(
    private val api: LaresApi,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = attempt { api.sessions() }
            mutableState.update { state ->
                result.fold(
                    onSuccess = { list ->
                        state.copy(sessions = list.sortedWith(compareBy({ !it.current }, { -it.lastSeenAt.epochSeconds })), loading = false)
                    },
                    onFailure = { state.copy(loading = false, error = it.toUiError()) },
                )
            }
        }
    }

    fun revoke(id: String) {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = attempt { api.revokeSession(id) }
            result.exceptionOrNull()?.let { e -> mutableState.update { it.copy(loading = false, error = e.toUiError()) } }
            if (result.isSuccess) load()
        }
    }
}

data class AboutUiState(
    val appVersion: String,
    val serverUrl: String,
    /** Null until fetched, or when the server cannot be reached. */
    val serverVersion: String? = null,
)

class AboutViewModel(
    sessionManager: SessionManager,
    private val api: LaresApi,
    appInfo: AppInfo,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            AboutUiState(appInfo.version, (sessionManager.state.value as? SessionState.LoggedIn)?.serverUrl.orEmpty()),
        )
    val state: StateFlow<AboutUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            attempt { api.instance() }.getOrNull()?.let { info -> mutableState.update { it.copy(serverVersion = info.serverVersion) } }
        }
    }
}
