package app.lares.client.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.data.Tag
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.family_add_create
import app.lares.client.resources.family_add_create_text
import app.lares.client.resources.family_add_invite
import app.lares.client.resources.family_add_invite_text
import app.lares.client.resources.family_add_title
import app.lares.client.resources.family_invite
import app.lares.client.resources.family_invite_days_left
import app.lares.client.resources.family_invite_hours_left
import app.lares.client.resources.family_invite_revoke
import app.lares.client.resources.family_invite_title
import app.lares.client.resources.family_needs_internet
import app.lares.client.resources.family_no_groups
import app.lares.client.resources.family_pending_invites
import app.lares.client.resources.family_settings
import app.lares.client.resources.family_tab_groups
import app.lares.client.resources.family_tab_members
import app.lares.client.resources.family_tab_relatives
import app.lares.client.resources.family_tab_tags
import app.lares.client.resources.family_tabs
import app.lares.client.resources.family_you
import app.lares.client.resources.group_admin_text
import app.lares.client.resources.group_administrators
import app.lares.client.resources.group_members_count
import app.lares.client.resources.group_new
import app.lares.client.resources.group_read_only
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_chevron_right
import app.lares.client.resources.ic_expand_less
import app.lares.client.resources.ic_expand_more
import app.lares.client.resources.ic_group
import app.lares.client.resources.ic_lock
import app.lares.client.resources.ic_person_add
import app.lares.client.resources.ic_qr_code_2
import app.lares.client.resources.ic_schedule_send
import app.lares.client.resources.ic_sell
import app.lares.client.resources.ic_settings
import app.lares.client.resources.ic_shield_person
import app.lares.client.resources.relative_member_badge
import app.lares.client.resources.relative_new
import app.lares.client.resources.relatives_empty
import app.lares.client.resources.section_family
import app.lares.client.resources.tag_new
import app.lares.client.resources.tags_archived
import app.lares.client.resources.tags_empty
import app.lares.client.resources.tags_read_only
import app.lares.client.ui.common.birthdayText
import app.lares.client.ui.common.message
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.ExtendedGradientFab
import app.lares.client.ui.components.LabelChip
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.NoticeBanner
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tones
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

/** Where the Family screen leads. */
class FamilyNavigation(
    val onBack: () -> Unit,
    val onMember: (memberId: String) -> Unit,
    val onAddMember: (AddMemberMode) -> Unit,
    val onGroup: (groupId: String?) -> Unit,
    val onPerson: (personId: String?) -> Unit,
    val onSettings: () -> Unit,
)

/** What the Family screen does without leaving it. */
class FamilyActions(
    val onRevokeInvite: (inviteId: String) -> Unit = {},
    val onNewTag: () -> Unit = {},
    val onTag: (Tag) -> Unit = {},
)

@Composable
fun FamilyRoute(
    initialTab: FamilyTab,
    navigation: FamilyNavigation,
    viewModel: FamilyViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheet by viewModel.tagSheet.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    FamilyScreen(
        state = state,
        tab = tab,
        onTab = { tab = it },
        navigation = navigation,
        actions = FamilyActions(viewModel::revokeInvite, viewModel::newTag, viewModel::editTag),
    )
    sheet?.let { TagSheetDialog(it, viewModel) }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            text = { Text(error.message(), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { LinkButton(stringResource(Res.string.action_cancel), viewModel::clearError) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        )
    }
}

/** Family (plan M0-08, mockup `Family.dc.html`): tab chips, the tab's list, the tab's main action. */
@Composable
fun FamilyScreen(
    state: FamilyUiState,
    tab: FamilyTab,
    onTab: (FamilyTab) -> Unit,
    navigation: FamilyNavigation,
    actions: FamilyActions = FamilyActions(),
) {
    var chooseAdd by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        DetailTopBar(stringResource(Res.string.section_family), navigation.onBack) {
            if (state.access.manageFamily) {
                IconButton(onClick = navigation.onSettings) {
                    AppIcon(Res.drawable.ic_settings, stringResource(Res.string.family_settings))
                }
            }
        }
        TabChips(tab, onTab)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen)
                    .padding(bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (tab) {
                    FamilyTab.MEMBERS -> MembersTab(state, navigation.onMember, actions.onRevokeInvite)
                    FamilyTab.RELATIVES -> RelativesTab(state, navigation.onPerson)
                    FamilyTab.GROUPS -> GroupsTab(state, navigation.onGroup)
                    FamilyTab.TAGS -> TagsTab(state, actions.onTag)
                }
            }
            val fab: Triple<DrawableResource, String, () -> Unit>? =
                when (tab) {
                    FamilyTab.MEMBERS -> {
                        if (state.access.manageFamily) {
                            Triple(Res.drawable.ic_person_add, stringResource(Res.string.family_invite)) { chooseAdd = true }
                        } else {
                            null
                        }
                    }

                    FamilyTab.RELATIVES -> {
                        if (state.access.managePersons) {
                            Triple(Res.drawable.ic_add, stringResource(Res.string.relative_new)) { navigation.onPerson(null) }
                        } else {
                            null
                        }
                    }

                    FamilyTab.GROUPS -> {
                        if (state.access.manageFamily) {
                            Triple(Res.drawable.ic_add, stringResource(Res.string.group_new)) { navigation.onGroup(null) }
                        } else {
                            null
                        }
                    }

                    FamilyTab.TAGS -> {
                        if (state.access.manageTags) {
                            Triple(
                                Res.drawable.ic_add,
                                stringResource(Res.string.tag_new),
                                actions.onNewTag,
                            )
                        } else {
                            null
                        }
                    }
                }
            if (fab != null) {
                // Admin actions need the server; relatives and tags work offline.
                val needsServer = tab == FamilyTab.MEMBERS || tab == FamilyTab.GROUPS
                ExtendedGradientFab(
                    fab.first,
                    fab.second,
                    fab.third,
                    enabled = !needsServer || state.online,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = Spacing.screen, bottom = Spacing.xl).testTag("family_fab"),
                )
            }
        }
    }
    if (chooseAdd) {
        AddMemberChooser(
            onChoose = {
                chooseAdd = false
                navigation.onAddMember(it)
            },
            onDismiss = { chooseAdd = false },
        )
    }
}

@Composable
private fun TabChips(
    selected: FamilyTab,
    onTab: (FamilyTab) -> Unit,
) {
    val description = stringResource(Res.string.family_tabs)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.l)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        FamilyTab.entries.forEach { tab ->
            AppFilterChip(
                selected = tab == selected,
                label = stringResource(tab.title()),
                onClick = { onTab(tab) },
                modifier =
                    Modifier.testTag("family_tab_${tab.name}").semantics {
                        role = Role.Tab
                        this.selected = tab == selected
                    },
            )
        }
    }
}

private fun FamilyTab.title() =
    when (this) {
        FamilyTab.MEMBERS -> Res.string.family_tab_members
        FamilyTab.RELATIVES -> Res.string.family_tab_relatives
        FamilyTab.GROUPS -> Res.string.family_tab_groups
        FamilyTab.TAGS -> Res.string.family_tab_tags
    }

/** The group's name; the built-in Administrators group is named by the app (its stored name is not localized). */
@Composable
fun GroupItem.label(): String = if (isAdministrators) stringResource(Res.string.group_administrators) else name

/** Group chips of a member: Administrators in `inverseSurface` with a shield, others in `primaryContainer`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupChips(groups: List<GroupItem>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (groups.isEmpty()) {
            Text(
                stringResource(Res.string.family_no_groups),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        groups.forEach { group ->
            if (group.isAdministrators) {
                LabelChip(
                    group.label(),
                    MaterialTheme.colorScheme.inverseSurface,
                    MaterialTheme.colorScheme.inverseOnSurface,
                    icon = Res.drawable.ic_shield_person,
                    modifier = Modifier.testTag("admin_chip"),
                )
            } else {
                LabelChip(group.label(), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun OfflineNotice(state: FamilyUiState) {
    if (state.access.manageFamily && !state.online) NoticeBanner(stringResource(Res.string.family_needs_internet))
}

@Composable
private fun MembersTab(
    state: FamilyUiState,
    onMember: (String) -> Unit,
    onRevokeInvite: (String) -> Unit,
) {
    OfflineNotice(state)
    val names = state.members.map { it.name }
    ListGroup {
        state.members.forEachIndexed { index, member ->
            if (index > 0) ListDivider(78.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onMember(member.id) }
                    .padding(horizontal = Spacing.l, vertical = 14.dp)
                    .testTag("member_${member.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(initialsFor(member.name, names), member.color, size = 48.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(member.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                        if (member.isMe) {
                            Text(
                                stringResource(Res.string.family_you),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    GroupChips(member.groups)
                }
                AppIcon(Res.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val invites = state.invites
    if (state.access.manageFamily && !invites.isNullOrEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(Res.string.family_pending_invites),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
            ListGroup {
                invites.forEachIndexed { index, invite ->
                    if (index > 0) ListDivider(78.dp)
                    InviteRow(invite, state.online, onRevokeInvite)
                }
            }
        }
    }
}

@Composable
private fun InviteRow(
    invite: InviteItem,
    online: Boolean,
    onRevoke: (String) -> Unit,
) {
    val groups = invite.groups.map { it.label() }
    val left = invite.info.expiresAt - Clock.System.now()
    val hours = left.inWholeHours.coerceAtLeast(0) + 1
    val expiry =
        if (hours >= 24) {
            val days = ((hours + 23) / 24).toInt()
            pluralStringResource(Res.plurals.family_invite_days_left, days, days)
        } else {
            pluralStringResource(Res.plurals.family_invite_hours_left, hours.toInt(), hours.toInt())
        }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(48.dp).border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) { AppIcon(Res.drawable.ic_schedule_send, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.family_invite_title), style = MaterialTheme.typography.titleMedium)
            Text(
                (groups + expiry).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinkButton(stringResource(Res.string.family_invite_revoke), { onRevoke(invite.info.id) }, enabled = online)
    }
}

@Composable
private fun RelativesTab(
    state: FamilyUiState,
    onPerson: (String) -> Unit,
) {
    val names = state.persons.map { it.person.data.name }
    ListGroup {
        state.persons.forEachIndexed { index, item ->
            if (index > 0) ListDivider(70.dp)
            val data = item.person.data
            val details = listOfNotNull(data.relation, birthdayText(data.birthDay, data.birthMonth, data.birthYear))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPerson(item.person.id) }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = Spacing.l, vertical = 12.dp)
                    .testTag("person_${item.person.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Avatar(initialsFor(data.name, names), item.person.color, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                        Text(data.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                        if (item.isMember) {
                            LabelChip(
                                stringResource(Res.string.relative_member_badge),
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                    if (details.isNotEmpty()) {
                        Text(
                            details.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AppIcon(Res.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (state.persons.none { !it.isMember }) {
        EmptyState(Res.drawable.ic_group, stringResource(Res.string.relatives_empty))
    }
}

@Composable
private fun GroupsTab(
    state: FamilyUiState,
    onGroup: (String) -> Unit,
) {
    OfflineNotice(state)
    if (!state.access.manageFamily) ReadOnlyHint(stringResource(Res.string.group_read_only))
    ListGroup {
        state.groups.forEachIndexed { index, group ->
            if (index > 0) ListDivider()
            val count = pluralStringResource(Res.plurals.group_members_count, group.memberIds.size, group.memberIds.size)
            ListRow(
                title = group.label(),
                icon = if (group.isAdministrators) Res.drawable.ic_lock else Res.drawable.ic_group,
                supporting = if (group.isAdministrators) "${stringResource(Res.string.group_admin_text)} · $count" else count,
                showChevron = true,
                onClick = { onGroup(group.id) },
                modifier = Modifier.testTag("group_${group.id}"),
            )
        }
    }
}

@Composable
private fun TagsTab(
    state: FamilyUiState,
    onTag: (Tag) -> Unit,
) {
    if (!state.access.manageTags) ReadOnlyHint(stringResource(Res.string.tags_read_only))
    if (state.tags.isEmpty() && state.archivedTags.isEmpty()) {
        EmptyState(Res.drawable.ic_sell, stringResource(Res.string.tags_empty))
        return
    }
    val editable = state.access.manageTags
    if (state.tags.isNotEmpty()) TagList(state.tags, editable, onTag)
    if (state.archivedTags.isNotEmpty()) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.tags_archived, state.archivedTags.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                AppIcon(
                    if (expanded) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) TagList(state.archivedTags, editable, onTag)
        }
    }
}

@Composable
private fun TagList(
    tags: List<Tag>,
    editable: Boolean,
    onTag: (Tag) -> Unit,
) {
    ListGroup {
        tags.forEachIndexed { index, tag ->
            if (index > 0) ListDivider(44.dp)
            val tones = tag.data.color.tones()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = editable) { onTag(tag) }
                    .heightIn(min = 52.dp)
                    .padding(horizontal = Spacing.l, vertical = 14.dp)
                    .testTag("tag_${tag.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                Box(Modifier.size(12.dp).background(tones.base, CircleShape))
                Text(tag.data.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (editable) AppIcon(Res.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ReadOnlyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xs),
    )
}

@Composable
private fun AddMemberChooser(
    onChoose: (AddMemberMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.family_add_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                ChooserOption(Res.drawable.ic_person_add, Res.string.family_add_invite, Res.string.family_add_invite_text) {
                    onChoose(AddMemberMode.INVITE)
                }
                ChooserOption(Res.drawable.ic_qr_code_2, Res.string.family_add_create, Res.string.family_add_create_text) {
                    onChoose(AddMemberMode.CREATE)
                }
            }
        },
        confirmButton = {},
        dismissButton = { LinkButton(stringResource(Res.string.action_cancel), onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    )
}

@Composable
private fun ChooserOption(
    icon: DrawableResource,
    title: org.jetbrains.compose.resources.StringResource,
    text: org.jetbrains.compose.resources.StringResource,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(Spacing.l),
        horizontalArrangement = Arrangement.spacedBy(Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
