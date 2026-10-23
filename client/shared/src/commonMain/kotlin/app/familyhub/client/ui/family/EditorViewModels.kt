package app.familyhub.client.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.familyhub.client.api.FamilyHubApi
import app.familyhub.client.auth.SessionManager
import app.familyhub.client.auth.SessionState
import app.familyhub.client.data.GroupsRepository
import app.familyhub.client.data.HouseholdRepository
import app.familyhub.client.data.MembersRepository
import app.familyhub.client.data.PermissionsRepository
import app.familyhub.client.data.PersonsRepository
import app.familyhub.client.data.PersonsWriter
import app.familyhub.client.sync.LocalWriteException
import app.familyhub.client.sync.SyncEngine
import app.familyhub.client.ui.common.FieldError
import app.familyhub.client.ui.common.UiError
import app.familyhub.client.ui.common.fieldErrors
import app.familyhub.client.ui.common.toUiError
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.api.admin.CreateGroupRequest
import app.familyhub.contract.api.admin.UpdateGroupRequest
import app.familyhub.contract.api.admin.UpdateHouseholdRequest
import app.familyhub.contract.entities.ContactData
import app.familyhub.contract.entities.ContactKind
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.contract.permissions.Permission
import app.familyhub.contract.validation.Validators
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private fun SessionManager.me() = (state.value as? SessionState.LoggedIn)?.me

data class GroupEditorUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val builtIn: BuiltInGroup? = null,
    val name: String = "",
    val nameError: FieldError? = null,
    val permissions: Set<Permission> = emptySet(),
    val memberIds: Set<String> = emptySet(),
    val members: List<MemberItem> = emptyList(),
    val canEdit: Boolean = false,
    val online: Boolean = true,
    val action: ActionState = ActionState(),
    /** Saved or deleted: the screen closes. */
    val done: Boolean = false,
) {
    val isAdministrators: Boolean get() = builtIn == BuiltInGroup.ADMINISTRATORS
}

/** Group editor (FND-021, FND-022, FND-023): name, permissions checklist, members checklist. */
class GroupEditorViewModel(
    sessionManager: SessionManager,
    membersRepository: MembersRepository,
    private val groupsRepository: GroupsRepository,
    permissionsRepository: PermissionsRepository,
    private val api: FamilyHubApi,
    private val syncEngine: SyncEngine,
    private val groupId: String?,
) : ViewModel() {
    private val me = sessionManager.me()
    private val form = MutableStateFlow(GroupEditorUiState(loaded = groupId == null, isNew = groupId == null))

    val state: StateFlow<GroupEditorUiState> =
        combine(
            form,
            membersRepository.observeAll(),
            groupsRepository.observeAll(),
            permissionsRepository.observeMine(),
            syncEngine.status.map { it.isOnline() }.distinctUntilChanged(),
        ) { form, members, groups, permissions, online ->
            val groupItems = GroupItem.list(groups)
            form.copy(
                members = MemberItem.list(members, groupItems, me?.memberId),
                canEdit = FamilyAccess.of(permissions).manageFamily,
                online = online,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, form.value)

    init {
        if (groupId != null) {
            viewModelScope.launch {
                val group = groupsRepository.observeAll().first().firstOrNull { it.id == groupId }
                form.update {
                    if (group == null) {
                        it.copy(loaded = true, done = true)
                    } else {
                        it.copy(
                            loaded = true,
                            builtIn = group.data.builtIn,
                            name = group.data.name,
                            permissions = group.data.permissions,
                            memberIds = group.data.memberIds,
                        )
                    }
                }
            }
        }
    }

    fun onName(value: String) = form.update { it.copy(name = value, nameError = null) }

    fun togglePermission(
        permission: Permission,
        checked: Boolean,
    ) = form.update {
        if (it.isAdministrators) it else it.copy(permissions = PermissionChecklist.toggle(it.permissions, permission, checked))
    }

    /** The Administrators group always keeps one member (FND-021): unchecking the last one shows `LAST_ADMIN`. */
    fun toggleMember(
        memberId: String,
        checked: Boolean,
    ) = form.update {
        val next = if (checked) it.memberIds + memberId else it.memberIds - memberId
        if (it.isAdministrators && next.isEmpty()) {
            it.copy(action = ActionState(error = UiError.Api(ErrorCode.LAST_ADMIN)))
        } else {
            it.copy(memberIds = next, action = ActionState())
        }
    }

    fun save() {
        // Form fields come from the local state: the combined state may lag one update behind.
        val current = form.value
        val derived = state.value
        if (current.action.busy || !derived.canEdit || !derived.online) return
        val name = current.name.trim()
        if (!current.isAdministrators) {
            Validators.groupName(name)?.let { code ->
                form.update { it.copy(nameError = FieldError.of(code)) }
                return
            }
        }
        // Members that no longer exist are dropped, so the server does not answer NOT_FOUND.
        val memberIds = current.memberIds.intersect(derived.members.map { it.id }.toSet())
        call {
            if (groupId == null) {
                api.createGroup(CreateGroupRequest(name, current.permissions, memberIds))
            } else if (current.isAdministrators) {
                api.updateGroup(groupId, UpdateGroupRequest(memberIds = memberIds))
            } else {
                api.updateGroup(groupId, UpdateGroupRequest(name = name, permissions = current.permissions, memberIds = memberIds))
            }
        }
    }

    fun delete() {
        val id = groupId ?: return
        call { api.deleteGroup(id) }
    }

    fun clearError() = form.update { it.copy(action = ActionState()) }

    private fun call(block: suspend () -> Unit) {
        form.update { it.copy(action = ActionState(busy = true)) }
        viewModelScope.launch {
            val failure = syncEngine.adminCall(block).exceptionOrNull()
            form.update {
                if (failure == null) {
                    it.copy(action = ActionState(), done = true)
                } else {
                    val nameError = failure.fieldErrors["name"]?.let(FieldError::of)
                    it.copy(action = ActionState(error = failure.toUiError()), nameError = nameError ?: it.nameError)
                }
            }
        }
    }
}

data class PersonUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val form: PersonForm = PersonForm(),
    /** The color shown when the person has none stored (persons created before FND-034). */
    val fallbackColor: PaletteColor = PaletteColor.BLUE,
    val fieldErrors: Map<String, FieldError> = emptyMap(),
    val isMember: Boolean = false,
    val isMe: Boolean = false,
    val canEdit: Boolean = false,
    val canDelete: Boolean = false,
    val error: UiError? = null,
    /** Saved or deleted: the screen closes. */
    val done: Boolean = false,
) {
    val color: PaletteColor get() = form.color ?: fallbackColor
}

/**
 * A person's form (FND-031, FND-034): relatives, members' profiles and the own profile. Written through sync, so it
 * works offline. Everyone edits their own profile; other persons need `persons.manage`.
 */
class PersonViewModel(
    sessionManager: SessionManager,
    private val personsRepository: PersonsRepository,
    private val membersRepository: MembersRepository,
    permissionsRepository: PermissionsRepository,
    private val personsWriter: PersonsWriter,
    private val clock: Clock,
    private val personId: String?,
) : ViewModel() {
    private val me = sessionManager.me()
    private val form = MutableStateFlow(PersonUiState(loaded = personId == null, isNew = personId == null))

    val state: StateFlow<PersonUiState> =
        combine(form, permissionsRepository.observeMine(), membersRepository.observeAll()) { form, permissions, members ->
            val manage = FamilyAccess.of(permissions).managePersons
            val isMember = personId != null && members.any { it.data.personId == personId }
            val isMe = personId != null && personId == me?.personId
            form.copy(
                isMember = isMember,
                isMe = isMe,
                canEdit = manage || isMe,
                canDelete = manage && personId != null && !isMember,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, form.value)

    init {
        viewModelScope.launch {
            if (personId == null) {
                form.update { it.copy(fallbackColor = personsWriter.nextColor()) }
                return@launch
            }
            val person = personsRepository.observe(personId).first()
            form.update {
                if (person == null) {
                    it.copy(loaded = true, done = true)
                } else {
                    it.copy(loaded = true, form = PersonForm.of(person.data), fallbackColor = person.color)
                }
            }
        }
    }

    fun edit(change: (PersonForm) -> PersonForm) = form.update { it.copy(form = change(it.form), error = null) }

    fun onName(value: String) = clearing("name") { it.copy(name = value) }

    fun onRelation(value: String) = clearing("relation") { it.copy(relation = value) }

    fun onNotes(value: String) = clearing("notes") { it.copy(notes = value) }

    fun onColor(value: PaletteColor) = edit { it.copy(color = value) }

    /** Day and month are picked together; the year is optional ([year] null = unknown). */
    fun onBirthday(
        day: Int?,
        month: Int?,
        year: Int?,
    ) = clearing(PersonForm.BIRTHDAY) { it.copy(birthDay = day, birthMonth = month, birthYear = year) }

    fun addContact(kind: ContactKind) = clearing("contacts") { it.copy(contacts = it.contacts + ContactData(kind, "")) }

    fun onContact(
        index: Int,
        contact: ContactData,
    ) = clearing("contacts") { it.copy(contacts = it.contacts.mapIndexed { i, c -> if (i == index) contact else c }) }

    fun removeContact(index: Int) = clearing("contacts") { it.copy(contacts = it.contacts.filterIndexed { i, _ -> i != index }) }

    fun save() {
        val current = form.value
        if (!state.value.canEdit) return
        val year = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).year
        val errors = current.form.validate(year)
        if (errors.isNotEmpty()) {
            // The form is long: say it next to the button too.
            form.update { it.copy(fieldErrors = errors, error = UiError.Api(ErrorCode.VALIDATION_FAILED)) }
            return
        }
        val data = current.form.toData()
        viewModelScope.launch {
            write {
                if (personId == null) {
                    personsWriter.create(data)
                } else {
                    // A person created before FND-034 keeps having no stored color until one is picked.
                    personsWriter.update(personId, data)
                }
            }
        }
    }

    fun delete() {
        val id = personId ?: return
        if (!state.value.canDelete) return
        viewModelScope.launch { write { personsWriter.delete(id) } }
    }

    private suspend fun write(block: suspend () -> Unit) {
        val failure =
            app.familyhub.client.ui.common
                .attempt(block)
                .exceptionOrNull()
        form.update {
            when (failure) {
                null -> {
                    it.copy(done = true)
                }

                is LocalWriteException -> {
                    val fields =
                        failure.fields
                            .map { (field, code) ->
                                (if (field.startsWith("birth")) PersonForm.BIRTHDAY else field) to FieldError.of(code)!!
                            }.toMap()
                    it.copy(fieldErrors = fields, error = UiError.Api(failure.code))
                }

                else -> {
                    it.copy(error = UiError.Api(ErrorCode.UNKNOWN))
                }
            }
        }
    }

    private fun clearing(
        field: String,
        change: (PersonForm) -> PersonForm,
    ) = form.update { it.copy(form = change(it.form), fieldErrors = it.fieldErrors - field, error = null) }
}

data class FamilySettingsUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val timeZone: String = "",
    val currency: String = "",
    val language: String = "",
    val fieldErrors: Map<String, FieldError> = emptyMap(),
    val canEdit: Boolean = false,
    val online: Boolean = true,
    val action: ActionState = ActionState(),
    val done: Boolean = false,
)

/** Family settings (FND-003): `PATCH /household` with the changed fields. */
class FamilySettingsViewModel(
    private val householdRepository: HouseholdRepository,
    permissionsRepository: PermissionsRepository,
    private val api: FamilyHubApi,
    private val syncEngine: SyncEngine,
) : ViewModel() {
    private val form = MutableStateFlow(FamilySettingsUiState())

    val state: StateFlow<FamilySettingsUiState> =
        combine(form, permissionsRepository.observeMine(), syncEngine.status.map { it.isOnline() }.distinctUntilChanged()) {
            form,
            permissions,
            online,
            ->
            form.copy(canEdit = FamilyAccess.of(permissions).manageFamily, online = online)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, form.value)

    init {
        viewModelScope.launch {
            val household = householdRepository.observe().first()
            form.update {
                if (household == null) {
                    it.copy(loaded = true)
                } else {
                    it.copy(
                        loaded = true,
                        name = household.data.name,
                        timeZone = household.data.timeZone,
                        currency = household.data.currency,
                        language = household.data.defaultLanguage,
                    )
                }
            }
        }
    }

    fun onName(value: String) = change(NAME) { it.copy(name = value) }

    fun onTimeZone(value: String) = change(TIME_ZONE) { it.copy(timeZone = value) }

    fun onCurrency(value: String) = change(CURRENCY) { it.copy(currency = value.uppercase().take(3)) }

    fun onLanguage(value: String) = change(LANGUAGE) { it.copy(language = value) }

    fun save() {
        val current = form.value
        if (current.action.busy || !state.value.canEdit || !state.value.online) return
        val name = current.name.trim()
        val errors =
            buildMap<String, FieldErrorCode> {
                Validators.householdName(name)?.let { put(NAME, it) }
                Validators.timeZone(current.timeZone)?.let { put(TIME_ZONE, it) }
                Validators.currency(current.currency)?.let { put(CURRENCY, it) }
                Validators.language(current.language)?.let { put(LANGUAGE, it) }
            }
        if (errors.isNotEmpty()) {
            form.update { it.copy(fieldErrors = errors.mapValues { (_, code) -> FieldError.of(code)!! }) }
            return
        }
        form.update { it.copy(action = ActionState(busy = true)) }
        viewModelScope.launch {
            val saved = householdRepository.observe().first()?.data
            val request =
                UpdateHouseholdRequest(
                    name = name.takeIf { it != saved?.name },
                    timeZone = current.timeZone.takeIf { it != saved?.timeZone },
                    currency = current.currency.takeIf { it != saved?.currency },
                    defaultLanguage = current.language.takeIf { it != saved?.defaultLanguage },
                )
            val failure = syncEngine.adminCall { api.updateHousehold(request) }.exceptionOrNull()
            form.update {
                if (failure == null) {
                    it.copy(action = ActionState(), done = true)
                } else {
                    it.copy(
                        action = ActionState(error = failure.toUiError()),
                        fieldErrors = failure.fieldErrors.mapValues { (_, code) -> FieldError.of(code)!! },
                    )
                }
            }
        }
    }

    private fun change(
        field: String,
        block: (FamilySettingsUiState) -> FamilySettingsUiState,
    ) = form.update { block(it).copy(fieldErrors = it.fieldErrors - field, action = ActionState()) }

    companion object {
        const val NAME = "name"
        const val TIME_ZONE = "timeZone"
        const val CURRENCY = "currency"
        const val LANGUAGE = "defaultLanguage"
    }
}
