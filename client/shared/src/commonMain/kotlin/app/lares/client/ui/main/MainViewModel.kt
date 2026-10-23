package app.lares.client.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lares.client.auth.SessionManager
import app.lares.client.auth.SessionState
import app.lares.client.data.GroupsRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.PersonsRepository
import app.lares.client.data.TagsRepository
import app.lares.client.sync.Rejection
import app.lares.client.sync.SyncEngine
import app.lares.client.sync.SyncState
import app.lares.client.sync.SyncStatus
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PaletteColors
import app.lares.contract.permissions.BuiltInGroup
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Section
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A group of the current member; built-in groups are labelled by the app, not by their stored name. */
data class GroupLabel(
    val name: String,
    val builtIn: BuiltInGroup?,
)

data class MainUiState(
    val name: String = "",
    val color: PaletteColor = PaletteColor.GRAY,
    val groups: List<GroupLabel> = emptyList(),
    /** Sections the member may see (FND-028); navigation hides the others. */
    val sections: Set<Section> = emptySet(),
    val sync: SyncStatus = SyncStatus(SyncState.SYNCED, 0, null, null),
    val rejections: List<Rejection> = emptyList(),
    val syncingNow: Boolean = false,
    /** Counts for the Family and Tags tiles of More. */
    val memberCount: Int = 0,
    val groupCount: Int = 0,
    val tagCount: Int = 0,
)

/** State shared by the main scaffold, Today and More: who is logged in, what they may see, sync status (FND-043). */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    sessionManager: SessionManager,
    personsRepository: PersonsRepository,
    groupsRepository: GroupsRepository,
    permissionsRepository: PermissionsRepository,
    private val syncEngine: SyncEngine,
    membersRepository: MembersRepository,
    tagsRepository: TagsRepository,
) : ViewModel() {
    private val syncingNow = MutableStateFlow(false)

    private val me = sessionManager.state.filterIsInstance<SessionState.LoggedIn>().map { it.me }

    private val profile: Flow<Pair<String, PaletteColor>> =
        me.flatMapLatest { me ->
            personsRepository.observe(me.personId).map { person ->
                (person?.data?.name ?: me.displayName) to (person?.color ?: PaletteColors.fallbackFor(me.personId))
            }
        }

    private val myGroups: Flow<List<GroupLabel>> =
        me.flatMapLatest { me ->
            groupsRepository.observeAll().map { groups ->
                groups
                    .filter { me.memberId in it.data.memberIds }
                    .sortedWith(compareBy({ it.data.builtIn == null }, { it.data.name }))
                    .map { GroupLabel(it.data.name, it.data.builtIn) }
            }
        }

    private val sections: Flow<Set<Section>> = permissionsRepository.observeMine().map(::visibleSections)

    val state: StateFlow<MainUiState> =
        combine(profile, myGroups, sections, syncEngine.status, syncEngine.rejections) { profile, groups, sections, sync, rejections ->
            MainUiState(profile.first, profile.second, groups, sections, sync, rejections)
        }.combine(syncingNow) { state, now -> state.copy(syncingNow = now) }
            .combine(
                combine(membersRepository.observeAll(), groupsRepository.observeAll(), tagsRepository.observeAll()) { m, g, t ->
                    Triple(m.size, g.size, t.size)
                },
            ) { state, (members, groups, tags) -> state.copy(memberCount = members, groupCount = groups, tagCount = tags) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun syncNow() {
        if (syncingNow.value) return
        syncingNow.value = true
        viewModelScope.launch {
            try {
                syncEngine.syncNow()
            } finally {
                syncingNow.update { false }
            }
        }
    }

    fun markRejectionsSeen(ids: Collection<Long>) {
        viewModelScope.launch { syncEngine.markRejectionsSeen(ids) }
    }

    companion object {
        fun visibleSections(permissions: EffectivePermissions): Set<Section> =
            Permission.entries
                .filter { it.kind == app.lares.contract.permissions.PermissionKind.SECTION && permissions.has(it) }
                .mapNotNull { it.section }
                .toSet()
    }
}
