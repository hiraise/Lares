package app.familyhub.client.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.familyhub.client.api.FamilyHubApi
import app.familyhub.client.auth.SessionManager
import app.familyhub.client.auth.SessionState
import app.familyhub.client.data.GroupsRepository
import app.familyhub.client.data.MembersRepository
import app.familyhub.client.data.PermissionsRepository
import app.familyhub.client.data.Person
import app.familyhub.client.data.PersonsRepository
import app.familyhub.client.sync.SyncEngine
import app.familyhub.client.ui.common.FieldError
import app.familyhub.client.ui.common.UiError
import app.familyhub.client.ui.common.attempt
import app.familyhub.client.ui.common.fieldErrors
import app.familyhub.client.ui.common.toUiError
import app.familyhub.client.ui.onboarding.passwordRepeatError
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.admin.CreateInviteRequest
import app.familyhub.contract.api.admin.CreateMemberRequest
import app.familyhub.contract.api.admin.InviteCreated
import app.familyhub.contract.api.admin.ResetPasswordRequest
import app.familyhub.contract.api.admin.SetGroupsRequest
import app.familyhub.contract.validation.Validators
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Runs an administrative call; on success asks for a sync so that the change arrives through the synced tables. */
internal suspend fun <T> SyncEngine.adminCall(block: suspend () -> T): Result<T> {
    val result = attempt(block)
    if (result.isSuccess) request()
    return result
}

/** Progress of one administrative action of a screen. */
data class ActionState(
    val busy: Boolean = false,
    val error: UiError? = null,
)

data class MemberDetailUiState(
    val member: MemberItem? = null,
    /** False once the member is gone (removed here or on another device). */
    val exists: Boolean = true,
    val allGroups: List<GroupItem> = emptyList(),
    val access: FamilyAccess = FamilyAccess(),
    val online: Boolean = true,
    val action: ActionState = ActionState(),
    val passwordFields: Map<String, FieldError> = emptyMap(),
    val passwordReset: Boolean = false,
    val removed: Boolean = false,
)

/** Member detail (FND-012, FND-014, FND-022, FND-025). */
class MemberDetailViewModel(
    sessionManager: SessionManager,
    membersRepository: MembersRepository,
    groupsRepository: GroupsRepository,
    permissionsRepository: PermissionsRepository,
    private val api: FamilyHubApi,
    private val syncEngine: SyncEngine,
    private val memberId: String,
) : ViewModel() {
    private val me = (sessionManager.state.value as? SessionState.LoggedIn)?.me
    private val local = MutableStateFlow(MemberDetailUiState())

    val state: StateFlow<MemberDetailUiState> =
        combine(
            membersRepository.observeAll(),
            groupsRepository.observeAll(),
            permissionsRepository.observeMine(),
            syncEngine.status.map { it.isOnline() }.distinctUntilChanged(),
            local,
        ) { members, groups, permissions, online, local ->
            val groupItems = GroupItem.list(groups)
            val member = MemberItem.list(members, groupItems, me?.memberId).firstOrNull { it.id == memberId }
            local.copy(
                member = member ?: local.member,
                exists = member != null,
                allGroups = groupItems,
                access = FamilyAccess.of(permissions),
                online = online,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MemberDetailUiState())

    fun setGroups(groupIds: Set<String>) = perform { api.setMemberGroups(memberId, SetGroupsRequest(groupIds)) }

    /** FND-012: a new password for the member; their sessions are revoked by the server. */
    fun resetPassword(
        password: String,
        repeat: String,
    ) {
        val errors =
            buildMap {
                Validators.password(password)?.let { put(NEW_PASSWORD, FieldError.of(it)!!) }
                putAll(passwordRepeatError(password, repeat))
            }
        local.update { it.copy(passwordFields = errors, passwordReset = false) }
        if (errors.isNotEmpty()) return
        perform(onSuccess = { it.copy(passwordReset = true) }) {
            api.resetMemberPassword(memberId, ResetPasswordRequest(password))
        }
    }

    fun clearPasswordState() = local.update { it.copy(passwordFields = emptyMap(), passwordReset = false, action = ActionState()) }

    fun remove() = perform(onSuccess = { it.copy(removed = true) }) { api.removeMember(memberId) }

    fun clearError() = local.update { it.copy(action = ActionState()) }

    private fun perform(
        onSuccess: (MemberDetailUiState) -> MemberDetailUiState = { it },
        call: suspend () -> Unit,
    ) {
        if (local.value.action.busy) return
        local.update { it.copy(action = ActionState(busy = true)) }
        viewModelScope.launch {
            val result = syncEngine.adminCall(call)
            val failure = result.exceptionOrNull()
            local.update { state ->
                if (failure == null) {
                    onSuccess(state.copy(action = ActionState()))
                } else {
                    val fields =
                        failure.fieldErrors["newPassword"]?.let { mapOf(NEW_PASSWORD to FieldError.of(it)!!) }.orEmpty()
                    state.copy(action = ActionState(error = failure.toUiError()), passwordFields = state.passwordFields + fields)
                }
            }
        }
    }

    companion object {
        const val NEW_PASSWORD = "newPassword"
    }
}

/** How a new member joins: an invitation they accept themselves, or an account created by the admin (QR login only). */
enum class AddMemberMode { INVITE, CREATE }

data class AddMemberUiState(
    val mode: AddMemberMode = AddMemberMode.INVITE,
    val groups: List<GroupItem> = emptyList(),
    val selectedGroups: Set<String> = emptySet(),
    val expiryDays: Int = 3,
    /** Relatives not linked to a member (FND-032). */
    val relatives: List<Person> = emptyList(),
    val relativeId: String? = null,
    val name: String = "",
    val nameError: FieldError? = null,
    val online: Boolean = true,
    val action: ActionState = ActionState(),
    /** The created invite: the screen switches to its QR and link. */
    val invite: InviteCreated? = null,
    /** The created member: the app shows its login QR next. */
    val createdMemberId: String? = null,
) {
    val relative: Person? get() = relatives.firstOrNull { it.id == relativeId }
}

/** "Add member" (FND-022, FND-024, FND-032): an invite with groups and expiry, or a member without password. */
class AddMemberViewModel(
    membersRepository: MembersRepository,
    groupsRepository: GroupsRepository,
    personsRepository: PersonsRepository,
    private val api: FamilyHubApi,
    private val syncEngine: SyncEngine,
    mode: AddMemberMode,
) : ViewModel() {
    private val local = MutableStateFlow(AddMemberUiState(mode = mode))

    val state: StateFlow<AddMemberUiState> =
        combine(
            membersRepository.observeAll(),
            groupsRepository.observeAll(),
            personsRepository.observeAll(),
            syncEngine.status.map { it.isOnline() }.distinctUntilChanged(),
            local,
        ) { members, groups, persons, online, local ->
            local.copy(groups = GroupItem.list(groups), relatives = PersonItem.unlinked(persons, members), online = online)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    fun toggleGroup(
        id: String,
        checked: Boolean,
    ) = local.update { it.copy(selectedGroups = if (checked) it.selectedGroups + id else it.selectedGroups - id) }

    fun setExpiryDays(days: Int) = local.update { it.copy(expiryDays = days) }

    /** Picks an existing relative (or none); in the create flow the name follows the relative. */
    fun setRelative(id: String?) {
        val person = state.value.relatives.firstOrNull { it.id == id }
        local.update { it.copy(relativeId = person?.id, name = person?.data?.name ?: it.name, nameError = null) }
    }

    fun onName(value: String) = local.update { it.copy(name = value, nameError = null) }

    fun submit() {
        // Form fields come from the local state: the combined state may lag one update behind.
        val form = local.value
        if (form.action.busy || !state.value.online) return
        when (form.mode) {
            AddMemberMode.INVITE -> {
                launchCall {
                    val created =
                        api.createInvite(CreateInviteRequest(form.selectedGroups, form.expiryDays * HOURS_PER_DAY, form.relativeId))
                    local.update { it.copy(invite = created) }
                }
            }

            AddMemberMode.CREATE -> {
                val name = form.name.trim()
                val nameError = Validators.personName(name)
                if (nameError != null) {
                    local.update { it.copy(nameError = FieldError.of(nameError)) }
                    return
                }
                launchCall {
                    val created = api.createMember(CreateMemberRequest(name, groupIds = form.selectedGroups, personId = form.relativeId))
                    local.update { it.copy(createdMemberId = created.memberId) }
                }
            }
        }
    }

    private fun launchCall(call: suspend () -> Unit) {
        local.update { it.copy(action = ActionState(busy = true)) }
        viewModelScope.launch {
            val failure = syncEngine.adminCall(call).exceptionOrNull()
            local.update { state ->
                val nameError = failure?.fieldErrors?.get("displayName")?.let { FieldError.of(it) }
                val error = failure?.toUiError()?.takeUnless { nameError != null && it == UiError.Api(ErrorCode.VALIDATION_FAILED) }
                state.copy(action = ActionState(error = error), nameError = nameError ?: state.nameError)
            }
        }
    }

    companion object {
        const val HOURS_PER_DAY = 24
        val EXPIRY_DAYS = listOf(1, 3, 7)
    }
}
