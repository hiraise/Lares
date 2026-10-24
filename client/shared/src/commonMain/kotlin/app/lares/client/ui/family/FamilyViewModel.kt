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
import app.lares.client.sync.SyncEngine
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.toUiError
import app.lares.contract.api.admin.InviteInfo
import app.lares.contract.entities.PaletteColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/**
 * The Family screen (plan M0-08): lists from the synced tables, invites from the server, tag edits through sync.
 * Administrative calls are online only; after each one a sync is requested so the change arrives through sync.
 */
class FamilyViewModel(
    sessionManager: SessionManager,
    membersRepository: MembersRepository,
    groupsRepository: GroupsRepository,
    personsRepository: PersonsRepository,
    tagsRepository: TagsRepository,
    permissionsRepository: PermissionsRepository,
    tagsWriter: TagsWriter,
    private val api: LaresApi,
    private val syncEngine: SyncEngine,
) : ViewModel() {
    private val me = (sessionManager.state.value as? SessionState.LoggedIn)?.me
    private val invites = MutableStateFlow<List<InviteInfo>?>(null)
    private val error = MutableStateFlow<UiError?>(null)
    val tagEditor = TagEditor(viewModelScope, tagsRepository, tagsWriter)

    val tagSheet: StateFlow<TagSheet?> = tagEditor.sheet

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

    // Tags (TAG-001, TAG-006): written through sync, so they work offline. The sheet logic is shared with the task editor.

    fun newTag() = tagEditor.new(state.value.tags.map { it.data.color })

    fun editTag(tag: Tag) = tagEditor.edit(tag)

    fun closeTagSheet() = tagEditor.close()

    fun onTagName(value: String) = tagEditor.onName(value)

    fun onTagColor(value: PaletteColor) = tagEditor.onColor(value)

    fun saveTag() = tagEditor.save()

    fun setTagArchived(archived: Boolean) = tagEditor.setArchived(archived)

    fun deleteTag() = tagEditor.delete()
}
