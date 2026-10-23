package app.lares.client.ui.family

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.api.LaresApi
import app.lares.client.auth.SessionManager
import app.lares.client.auth.SessionState
import app.lares.client.data.GroupsRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.PersonsRepository
import app.lares.client.data.Tag
import app.lares.client.data.TagsRepository
import app.lares.client.data.TagsWriter
import app.lares.client.sync.LocalWriteException
import app.lares.client.sync.SyncEngine
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.InviteInfo
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PaletteColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An active invite with its groups resolved for display. */
data class InviteItem(
    val info: InviteInfo,
    val groups: List<GroupItem>,
)

data class FamilyUiState(
    val loaded: Boolean = false,
    val access: FamilyAccess = FamilyAccess(),
    val online: Boolean = true,
    val members: List<MemberItem> = emptyList(),
    val groups: List<GroupItem> = emptyList(),
    val persons: List<PersonItem> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val archivedTags: List<Tag> = emptyList(),
    /** Active invites, loaded from the server for `household.manage`; null until loaded. */
    val invites: List<InviteItem>? = null,
    val error: UiError? = null,
)

/** The tag create/edit sheet. [id] is null for a new tag. */
data class TagSheet(
    val id: String? = null,
    val name: String = "",
    val color: PaletteColor = PaletteColor.BLUE,
    val archived: Boolean = false,
    val nameError: FieldError? = null,
    val error: UiError? = null,
)

/**
 * The Family screen (plan M0-08): lists from the synced tables, invites from the server, tag edits through sync.
 * Administrative calls are online only; after each one a sync is requested so the change arrives through sync.
 */
class FamilyViewModel(
    sessionManager: SessionManager,
    membersRepository: MembersRepository,
    groupsRepository: GroupsRepository,
    personsRepository: PersonsRepository,
    private val tagsRepository: TagsRepository,
    permissionsRepository: PermissionsRepository,
    private val tagsWriter: TagsWriter,
    private val api: LaresApi,
    private val syncEngine: SyncEngine,
) : ViewModel() {
    private val me = (sessionManager.state.value as? SessionState.LoggedIn)?.me
    private val invites = MutableStateFlow<List<InviteInfo>?>(null)
    private val error = MutableStateFlow<UiError?>(null)
    private val mutableTagSheet = MutableStateFlow<TagSheet?>(null)

    val tagSheet: StateFlow<TagSheet?> = mutableTagSheet.asStateFlow()

    private val lists =
        combine(
            membersRepository.observeAll(),
            groupsRepository.observeAll(),
            personsRepository.observeAll(),
            tagsRepository.observeAll(includeArchived = true),
        ) { members, groups, persons, tags ->
            val groupItems = GroupItem.list(groups)
            FamilyUiState(
                loaded = true,
                members = MemberItem.list(members, groupItems, me?.memberId),
                groups = groupItems,
                persons = PersonItem.list(persons, members, me?.personId),
                tags = tags.filter { !it.data.archived }.sortedBy { it.data.name.lowercase() },
                archivedTags = tags.filter { it.data.archived }.sortedBy { it.data.name.lowercase() },
            )
        }

    private val access = permissionsRepository.observeMine().map(FamilyAccess::of).distinctUntilChanged()

    private val online = syncEngine.status.map { it.isOnline() }.distinctUntilChanged()

    val state: StateFlow<FamilyUiState> =
        combine(lists, access, online, invites, error) { lists, access, online, invites, error ->
            lists.copy(
                access = access,
                online = online,
                invites =
                    invites?.map { invite ->
                        InviteItem(invite, lists.groups.filter { it.id in invite.groupIds })
                    },
                error = error,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, FamilyUiState())

    init {
        // Invites live only on the server; load them when the member may manage the family and the server is reachable.
        viewModelScope.launch {
            combine(access, online) { a, o -> a.manageFamily && o }.distinctUntilChanged().collect { if (it) loadInvites() }
        }
    }

    fun loadInvites() {
        viewModelScope.launch {
            attempt { api.invites() }.onSuccess { list -> invites.value = list.sortedBy { it.expiresAt } }
        }
    }

    fun revokeInvite(id: String) {
        viewModelScope.launch {
            val result = attempt { api.revokeInvite(id) }
            result.exceptionOrNull()?.let { error.value = it.toUiError() }
            loadInvites()
        }
    }

    fun clearError() {
        error.value = null
    }

    // Tags (TAG-001, TAG-006): written through sync, so they work offline.

    fun newTag() {
        val used = state.value.tags.map { it.data.color }
        mutableTagSheet.value = TagSheet(color = PaletteColors.pickLeastUsed(used))
    }

    fun editTag(tag: Tag) {
        mutableTagSheet.value = TagSheet(tag.id, tag.data.name, tag.data.color, tag.data.archived)
    }

    fun closeTagSheet() {
        mutableTagSheet.value = null
    }

    fun onTagName(value: String) = mutableTagSheet.update { it?.copy(name = value, nameError = null, error = null) }

    fun onTagColor(value: PaletteColor) = mutableTagSheet.update { it?.copy(color = value) }

    fun saveTag() {
        val sheet = mutableTagSheet.value ?: return
        val name = sheet.name.trim()
        viewModelScope.launch {
            val all = tagsRepository.observeAll(includeArchived = true).first()
            val nameError = TagRules.nameError(name, all, sheet.id)
            if (nameError != null) {
                mutableTagSheet.update { it?.copy(nameError = nameError) }
                return@launch
            }
            val result =
                attempt {
                    val existing = all.firstOrNull { it.id == sheet.id }
                    if (existing == null) {
                        tagsWriter.create(name, sheet.color)
                    } else {
                        if (existing.data.name != name) tagsWriter.rename(existing.id, name)
                        if (existing.data.color != sheet.color) tagsWriter.recolor(existing.id, sheet.color)
                    }
                }
            tagWriteDone(result)
        }
    }

    fun setTagArchived(archived: Boolean) {
        val id = mutableTagSheet.value?.id ?: return
        viewModelScope.launch { tagWriteDone(attempt { tagsWriter.archive(id, archived) }) }
    }

    fun deleteTag() {
        val id = mutableTagSheet.value?.id ?: return
        viewModelScope.launch { tagWriteDone(attempt { tagsWriter.delete(id) }) }
    }

    private fun tagWriteDone(result: Result<*>) {
        val failure = result.exceptionOrNull()
        if (failure == null) {
            mutableTagSheet.value = null
            return
        }
        mutableTagSheet.update { sheet ->
            if (failure is LocalWriteException) {
                val field = failure.fields["name"]
                if (field != null) {
                    sheet?.copy(nameError = FieldError.of(field))
                } else {
                    sheet?.copy(error = UiError.Api(failure.code))
                }
            } else {
                sheet?.copy(error = UiError.Api(ErrorCode.UNKNOWN))
            }
        }
    }
}
