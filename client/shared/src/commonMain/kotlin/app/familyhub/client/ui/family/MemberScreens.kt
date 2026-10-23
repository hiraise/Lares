package app.familyhub.client.ui.family

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familyhub.client.data.Person
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.action_cancel
import app.familyhub.client.resources.action_done
import app.familyhub.client.resources.action_save
import app.familyhub.client.resources.family_add_create
import app.familyhub.client.resources.family_add_expiry
import app.familyhub.client.resources.family_add_groups
import app.familyhub.client.resources.family_add_groups_hint
import app.familyhub.client.resources.family_add_invite
import app.familyhub.client.resources.family_add_name
import app.familyhub.client.resources.family_add_relative
import app.familyhub.client.resources.family_add_relative_hint
import app.familyhub.client.resources.family_add_relative_none
import app.familyhub.client.resources.family_add_submit_create
import app.familyhub.client.resources.family_add_submit_invite
import app.familyhub.client.resources.family_days
import app.familyhub.client.resources.family_invite_title
import app.familyhub.client.resources.family_needs_internet
import app.familyhub.client.resources.field_password_repeat
import app.familyhub.client.resources.ic_group
import app.familyhub.client.resources.ic_lock_reset
import app.familyhub.client.resources.ic_person
import app.familyhub.client.resources.ic_person_remove
import app.familyhub.client.resources.ic_qr_code_2
import app.familyhub.client.resources.ic_share
import app.familyhub.client.resources.invite_result_text
import app.familyhub.client.resources.invite_share
import app.familyhub.client.resources.invite_share_text
import app.familyhub.client.resources.invite_valid_until
import app.familyhub.client.resources.login_qr_code
import app.familyhub.client.resources.member_gone
import app.familyhub.client.resources.member_groups_change
import app.familyhub.client.resources.member_login_qr
import app.familyhub.client.resources.member_login_qr_hint
import app.familyhub.client.resources.member_no_login
import app.familyhub.client.resources.member_profile
import app.familyhub.client.resources.member_profile_hint
import app.familyhub.client.resources.member_remove
import app.familyhub.client.resources.member_remove_text
import app.familyhub.client.resources.member_remove_title
import app.familyhub.client.resources.member_reset_done
import app.familyhub.client.resources.member_reset_password
import app.familyhub.client.resources.member_reset_text
import app.familyhub.client.resources.member_title
import app.familyhub.client.resources.password_new
import app.familyhub.client.ui.common.rememberShareText
import app.familyhub.client.ui.common.rememberTimeFormatter
import app.familyhub.client.ui.components.AppFilterChip
import app.familyhub.client.ui.components.AppPage
import app.familyhub.client.ui.components.Avatar
import app.familyhub.client.ui.components.CheckRow
import app.familyhub.client.ui.components.ConfirmDialog
import app.familyhub.client.ui.components.DetailTopBar
import app.familyhub.client.ui.components.ErrorText
import app.familyhub.client.ui.components.FormField
import app.familyhub.client.ui.components.LinkButton
import app.familyhub.client.ui.components.ListDivider
import app.familyhub.client.ui.components.ListGroup
import app.familyhub.client.ui.components.ListRow
import app.familyhub.client.ui.components.NoticeBanner
import app.familyhub.client.ui.components.PasswordField
import app.familyhub.client.ui.components.PrimaryButton
import app.familyhub.client.ui.components.SectionHeader
import app.familyhub.client.ui.components.TonalButton
import app.familyhub.client.ui.components.initialsFor
import app.familyhub.client.ui.onboarding.SetupFields
import app.familyhub.client.ui.theme.Spacing
import app.familyhub.client.ui.theme.tabular
import app.familyhub.contract.Codes
import app.familyhub.contract.api.admin.InviteCreated
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun MemberDetailRoute(
    memberId: String,
    onBack: () -> Unit,
    onPerson: (personId: String) -> Unit,
    onLoginQr: (memberId: String, name: String) -> Unit,
    viewModel: MemberDetailViewModel = koinViewModel(key = "member-$memberId") { parametersOf(memberId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<MemberDialog?>(null) }
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    val member = state.member
    AppPage(topBar = { DetailTopBar(member?.name ?: stringResource(Res.string.member_title), onBack) }) {
        if (member == null) return@AppPage
        if (!state.exists) {
            Text(stringResource(Res.string.member_gone), style = MaterialTheme.typography.bodyLarge)
            return@AppPage
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Avatar(initialsFor(member.name), member.color, size = 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    member.login ?: stringResource(Res.string.member_no_login),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GroupChips(member.groups)
            }
        }
        val manage = state.access.manageFamily
        if (manage && !state.online) NoticeBanner(stringResource(Res.string.family_needs_internet))
        ListGroup {
            ListRow(
                stringResource(Res.string.member_profile),
                icon = Res.drawable.ic_person,
                supporting = stringResource(Res.string.member_profile_hint),
                showChevron = true,
                onClick = { onPerson(member.personId) },
            )
            if (manage) {
                ListDivider()
                ListRow(
                    stringResource(Res.string.member_groups_change),
                    icon = Res.drawable.ic_group,
                    showChevron = true,
                    enabled = state.online,
                    onClick = { dialog = MemberDialog.GROUPS },
                )
            }
            if (manage && !member.isMe) {
                if (member.login != null) {
                    ListDivider()
                    ListRow(
                        stringResource(Res.string.member_reset_password),
                        icon = Res.drawable.ic_lock_reset,
                        showChevron = true,
                        enabled = state.online,
                        onClick = { dialog = MemberDialog.PASSWORD },
                    )
                }
                ListDivider()
                ListRow(
                    stringResource(Res.string.member_login_qr),
                    icon = Res.drawable.ic_qr_code_2,
                    supporting = stringResource(Res.string.member_login_qr_hint),
                    showChevron = true,
                    enabled = state.online,
                    onClick = { onLoginQr(member.id, member.name) },
                )
            }
        }
        if (manage && !member.isMe) {
            ListGroup {
                ListRow(
                    stringResource(Res.string.member_remove),
                    icon = Res.drawable.ic_person_remove,
                    titleColor = MaterialTheme.colorScheme.error,
                    iconTint = MaterialTheme.colorScheme.error,
                    enabled = state.online,
                    onClick = { dialog = MemberDialog.REMOVE },
                )
            }
        }
        if (dialog == null) ErrorText(state.action.error)
    }
    when (dialog) {
        MemberDialog.GROUPS -> {
            GroupsDialog(
                groups = state.allGroups,
                initial =
                    member
                        ?.groups
                        ?.map { it.id }
                        ?.toSet()
                        .orEmpty(),
                onSave = {
                    dialog = null
                    viewModel.setGroups(it)
                },
                onDismiss = { dialog = null },
            )
        }

        MemberDialog.PASSWORD -> {
            ResetPasswordDialog(
                name = member?.name.orEmpty(),
                state = state,
                onSubmit = viewModel::resetPassword,
                onDismiss = {
                    dialog = null
                    viewModel.clearPasswordState()
                },
            )
        }

        MemberDialog.REMOVE -> {
            ConfirmDialog(
                title = stringResource(Res.string.member_remove_title, member?.name.orEmpty()),
                text = stringResource(Res.string.member_remove_text),
                confirmLabel = stringResource(Res.string.member_remove),
                onConfirm = {
                    dialog = null
                    viewModel.remove()
                },
                onDismiss = { dialog = null },
                destructive = true,
            )
        }

        null -> {}
    }
}

private enum class MemberDialog { GROUPS, PASSWORD, REMOVE }

/** Multi-select of the member's groups → `PUT /members/{id}/groups`. */
@Composable
private fun GroupsDialog(
    groups: List<GroupItem>,
    initial: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.member_groups_change), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEach { group ->
                    CheckRow(
                        group.label(),
                        group.id in selected,
                        { checked -> selected = if (checked) selected + group.id else selected - group.id },
                    )
                }
            }
        },
        confirmButton = { LinkButton(stringResource(Res.string.action_save), { onSave(selected) }) },
        dismissButton = { LinkButton(stringResource(Res.string.action_cancel), onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    )
}

@Composable
private fun ResetPasswordDialog(
    name: String,
    state: MemberDetailUiState,
    onSubmit: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    if (state.passwordReset) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(Res.string.member_reset_password), style = MaterialTheme.typography.headlineSmall) },
            text = { Text(stringResource(Res.string.member_reset_done, name), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = { LinkButton(stringResource(Res.string.action_done), onDismiss) },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        )
        return
    }
    ConfirmDialog(
        title = stringResource(Res.string.member_reset_password),
        text = stringResource(Res.string.member_reset_text, name),
        confirmLabel = stringResource(Res.string.action_save),
        onConfirm = { onSubmit(password, repeat) },
        onDismiss = onDismiss,
    ) {
        PasswordField(
            password,
            { password = it },
            stringResource(Res.string.password_new),
            error = state.passwordFields[MemberDetailViewModel.NEW_PASSWORD],
        )
        PasswordField(
            repeat,
            { repeat = it },
            stringResource(Res.string.field_password_repeat),
            error = state.passwordFields[SetupFields.PASSWORD_REPEAT],
            imeAction = ImeAction.Done,
            onDone = { onSubmit(password, repeat) },
        )
        ErrorText(state.action.error)
    }
}

@Composable
fun AddMemberRoute(
    mode: AddMemberMode,
    onBack: () -> Unit,
    onMemberCreated: (memberId: String, name: String) -> Unit,
    viewModel: AddMemberViewModel = koinViewModel(key = "add-member-$mode") { parametersOf(mode) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdMemberId) {
        state.createdMemberId?.let { onMemberCreated(it, state.name.trim()) }
    }
    val invite = state.invite
    val title =
        if (invite !=
            null
        ) {
            Res.string.family_invite_title
        } else if (mode ==
            AddMemberMode.INVITE
        ) {
            Res.string.family_add_invite
        } else {
            Res.string.family_add_create
        }
    AppPage(topBar = { DetailTopBar(stringResource(title), onBack) }) {
        if (invite != null) {
            InviteResult(invite, onBack)
            return@AppPage
        }
        AddMemberForm(state, viewModel)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddMemberForm(
    state: AddMemberUiState,
    viewModel: AddMemberViewModel,
) {
    if (!state.online) NoticeBanner(stringResource(Res.string.family_needs_internet))
    if (state.mode == AddMemberMode.CREATE) {
        FormField(
            state.name,
            viewModel::onName,
            stringResource(Res.string.family_add_name),
            error = state.nameError,
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(Res.string.family_add_groups))
        Text(
            stringResource(Res.string.family_add_groups_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xs),
        )
        ListGroup {
            state.groups.forEach { group ->
                CheckRow(group.label(), group.id in state.selectedGroups, { viewModel.toggleGroup(group.id, it) })
            }
        }
    }
    if (state.mode == AddMemberMode.INVITE) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.family_add_expiry))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AddMemberViewModel.EXPIRY_DAYS.forEach { days ->
                    AppFilterChip(
                        state.expiryDays == days,
                        pluralStringResource(Res.plurals.family_days, days, days),
                        { viewModel.setExpiryDays(days) },
                    )
                }
            }
        }
    }
    if (state.relatives.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.family_add_relative))
            Text(
                stringResource(Res.string.family_add_relative_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xs),
            )
            ListGroup {
                RelativeOption(
                    null,
                    stringResource(Res.string.family_add_relative_none),
                    state.relativeId == null,
                ) { viewModel.setRelative(null) }
                state.relatives.forEach { person ->
                    ListDivider(16.dp)
                    RelativeOption(person, person.data.name, state.relativeId == person.id) { viewModel.setRelative(person.id) }
                }
            }
        }
    }
    ErrorText(state.action.error)
    PrimaryButton(
        stringResource(
            if (state.mode ==
                AddMemberMode.INVITE
            ) {
                Res.string.family_add_submit_invite
            } else {
                Res.string.family_add_submit_create
            },
        ),
        viewModel::submit,
        enabled = state.online,
        loading = state.action.busy,
    )
}

@Composable
private fun RelativeOption(
    person: Person?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (person != null) Avatar(initialsFor(person.data.name), person.color, size = 32.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}

/** FND-024: the invite as a QR code, a link to share and its expiry. The code is shown only now. */
@Composable
private fun InviteResult(
    invite: InviteCreated,
    onDone: () -> Unit,
) {
    val formatTime = rememberTimeFormatter()
    val share = rememberShareText()
    val code = Codes.format(invite.code)
    val shareText = stringResource(Res.string.invite_share_text, invite.payload, code)
    Text(
        stringResource(Res.string.invite_result_text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        // Always dark on white so every camera reads it.
        Box(
            Modifier.size(264.dp).background(Color.White, MaterialTheme.shapes.large).padding(Spacing.l),
            contentAlignment = Alignment.Center,
        ) {
            Image(rememberQrCodePainter(invite.payload), stringResource(Res.string.family_invite_title), Modifier.fillMaxWidth())
        }
        Text(stringResource(Res.string.invite_valid_until, formatTime(invite.expiresAt)), style = MaterialTheme.typography.titleMedium)
        SelectionContainer {
            Text(
                stringResource(Res.string.login_qr_code, code),
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    TonalButton(stringResource(Res.string.invite_share), { share(shareText) }, icon = Res.drawable.ic_share)
    PrimaryButton(stringResource(Res.string.action_done), onDone)
}
