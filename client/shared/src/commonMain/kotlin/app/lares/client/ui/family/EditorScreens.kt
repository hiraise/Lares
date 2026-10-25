package app.lares.client.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.action_delete
import app.lares.client.resources.action_save
import app.lares.client.resources.contact_remove
import app.lares.client.resources.family_needs_internet
import app.lares.client.resources.family_settings
import app.lares.client.resources.family_settings_read_only
import app.lares.client.resources.group_admin_text
import app.lares.client.resources.group_administrators
import app.lares.client.resources.group_delete
import app.lares.client.resources.group_delete_text
import app.lares.client.resources.group_delete_title
import app.lares.client.resources.group_members
import app.lares.client.resources.group_name
import app.lares.client.resources.group_new
import app.lares.client.resources.group_read_only
import app.lares.client.resources.group_rights
import app.lares.client.resources.group_title
import app.lares.client.resources.ic_close
import app.lares.client.resources.ic_delete
import app.lares.client.resources.ic_lock
import app.lares.client.resources.language_en
import app.lares.client.resources.language_ru
import app.lares.client.resources.relation_suggestions
import app.lares.client.resources.relative_birthday
import app.lares.client.resources.relative_birthday_clear
import app.lares.client.resources.relative_birthday_none
import app.lares.client.resources.relative_birthday_year_unknown
import app.lares.client.resources.relative_color
import app.lares.client.resources.relative_contacts
import app.lares.client.resources.relative_delete_text
import app.lares.client.resources.relative_delete_title
import app.lares.client.resources.relative_name
import app.lares.client.resources.relative_notes
import app.lares.client.resources.relative_read_only
import app.lares.client.resources.relative_relation
import app.lares.client.resources.relative_title
import app.lares.client.resources.relative_title_new
import app.lares.client.resources.section_family
import app.lares.client.resources.setup_currency
import app.lares.client.resources.setup_currency_hint
import app.lares.client.resources.setup_family_name
import app.lares.client.resources.setup_language
import app.lares.client.resources.setup_time_zone
import app.lares.client.ui.common.birthdayText
import app.lares.client.ui.common.label
import app.lares.client.ui.common.labelRes
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.CheckRow
import app.lares.client.ui.components.ColorSwatches
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.DayMonthYearDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.NoticeBanner
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.main.title
import app.lares.client.ui.onboarding.CURRENCY_SUGGESTIONS
import app.lares.client.ui.onboarding.TimeZoneDialog
import app.lares.client.ui.theme.Spacing
import app.lares.contract.entities.ContactKind
import app.lares.contract.validation.Validators
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun GroupEditorRoute(
    groupId: String?,
    onBack: () -> Unit,
    viewModel: GroupEditorViewModel = koinViewModel(key = "group-$groupId") { parametersOf(GroupArgs(groupId)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onBack() }
    GroupEditorScreen(state, GroupEditorActions(viewModel), onBack)
}

/** Koin parameters cannot carry null; the editor gets its optional id wrapped. */
data class GroupArgs(
    val groupId: String?,
)

class GroupEditorActions(
    val onName: (String) -> Unit = {},
    val onPermission: (app.lares.contract.permissions.Permission, Boolean) -> Unit = { _, _ -> },
    val onMember: (String, Boolean) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
) {
    constructor(viewModel: GroupEditorViewModel) : this(
        viewModel::onName,
        viewModel::togglePermission,
        viewModel::toggleMember,
        viewModel::save,
        viewModel::delete,
    )
}

/** Group editor (FND-021…FND-023): name, plain-language permissions by section, members. */
@Composable
fun GroupEditorScreen(
    state: GroupEditorUiState,
    actions: GroupEditorActions,
    onBack: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val editable = state.canEdit && state.online
    val title =
        when {
            state.isNew -> stringResource(Res.string.group_new)
            state.isAdministrators -> stringResource(Res.string.group_administrators)
            state.name.isNotBlank() -> state.name
            else -> stringResource(Res.string.group_title)
        }
    AppPage(
        topBar = {
            DetailTopBar(title, onBack) {
                if (state.canEdit && !state.isNew && state.builtIn == null) {
                    IconButton(onClick = { confirmDelete = true }, enabled = state.online) {
                        AppIcon(Res.drawable.ic_delete, stringResource(Res.string.group_delete))
                    }
                }
            }
        },
    ) {
        if (!state.loaded) return@AppPage
        if (state.canEdit && !state.online) NoticeBanner(stringResource(Res.string.family_needs_internet))
        if (!state.canEdit) ReadOnlyHint(stringResource(Res.string.group_read_only))
        if (state.isAdministrators) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.large)
                    .padding(Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(Res.drawable.ic_lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(Res.string.group_admin_text), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            FormField(
                state.name,
                actions.onName,
                stringResource(Res.string.group_name),
                error = state.nameError,
                enabled = editable,
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(stringResource(Res.string.group_rights))
                PermissionChecklist.sections.forEach { (section, permissions) ->
                    ListGroup {
                        Text(
                            stringResource(section?.title() ?: Res.string.section_family),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = Spacing.l, end = Spacing.l, top = 14.dp, bottom = 2.dp),
                        )
                        permissions.forEach { permission ->
                            CheckRow(
                                stringResource(permission.labelRes()),
                                permission in state.permissions,
                                { actions.onPermission(permission, it) },
                                enabled = editable,
                                modifier = Modifier.testTag("perm_${permission.key}"),
                            )
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.group_members))
            val names = state.members.map { it.name }
            ListGroup {
                state.members.forEachIndexed { index, member ->
                    if (index > 0) ListDivider(70.dp)
                    CheckRow(
                        member.name,
                        member.id in state.memberIds,
                        { actions.onMember(member.id, it) },
                        enabled = editable,
                        leading = { Avatar(initialsFor(member.name, names), member.color, size = 40.dp) },
                        modifier = Modifier.testTag("group_member_${member.id}"),
                    )
                }
            }
        }
        ErrorText(state.action.error)
        if (state.canEdit) {
            PrimaryButton(
                stringResource(Res.string.action_save),
                actions.onSave,
                enabled = state.online,
                loading = state.action.busy,
            )
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(Res.string.group_delete_title, state.name),
            text = stringResource(Res.string.group_delete_text),
            confirmLabel = stringResource(Res.string.action_delete),
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

/** Koin parameters cannot carry null; the person form gets its optional id wrapped. */
data class PersonArgs(
    val personId: String?,
)

/** A relative, a member's profile or the own profile (FND-031, FND-034). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonRoute(
    personId: String?,
    onBack: () -> Unit,
    viewModel: PersonViewModel = koinViewModel(key = "person-$personId") { parametersOf(PersonArgs(personId)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickBirthday by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onBack() }
    val form = state.form
    val title =
        if (state.isNew) {
            stringResource(Res.string.relative_title_new)
        } else {
            form.name.ifBlank {
                stringResource(Res.string.relative_title)
            }
        }
    AppPage(
        topBar = {
            DetailTopBar(title, onBack) {
                if (state.canDelete) {
                    IconButton(
                        onClick = { confirmDelete = true },
                    ) { AppIcon(Res.drawable.ic_delete, stringResource(Res.string.action_delete)) }
                }
            }
        },
    ) {
        if (!state.loaded) return@AppPage
        val editable = state.canEdit
        if (!editable) ReadOnlyHint(stringResource(Res.string.relative_read_only))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
            Avatar(initialsFor(form.name), state.color, size = 64.dp)
            FormField(
                form.name,
                viewModel::onName,
                stringResource(Res.string.relative_name),
                modifier = Modifier.weight(1f),
                error = state.fieldErrors["name"],
                enabled = editable,
                capitalization = KeyboardCapitalization.Words,
            )
        }
        Box(Modifier.clickable(enabled = editable) { pickBirthday = true }.testTag("birthday_field")) {
            FormField(
                birthdayText(form.birthDay, form.birthMonth, form.birthYear) ?: stringResource(Res.string.relative_birthday_none),
                {},
                stringResource(Res.string.relative_birthday),
                error = state.fieldErrors[PersonForm.BIRTHDAY],
                readOnly = true,
                enabled = false,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FormField(
                form.relation,
                viewModel::onRelation,
                stringResource(Res.string.relative_relation),
                error = state.fieldErrors["relation"],
                enabled = editable,
            )
            if (editable) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    stringArrayResource(Res.array.relation_suggestions).forEach { suggestion ->
                        AppFilterChip(form.relation.trim() == suggestion, suggestion, { viewModel.onRelation(suggestion) })
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(Res.string.relative_color), style = MaterialTheme.typography.labelLarge)
            ColorSwatches(state.color, viewModel::onColor, enabled = editable)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(Res.string.relative_contacts), style = MaterialTheme.typography.labelLarge)
            // A contact added with "+ Phone" etc. gets the focus, so typing goes into it.
            val newContactFocus = remember { FocusRequester() }
            var focusIndex by remember { mutableStateOf(-1) }
            LaunchedEffect(focusIndex, form.contacts.size) {
                if (focusIndex in form.contacts.indices) {
                    newContactFocus.requestFocus()
                    focusIndex = -1
                }
            }
            form.contacts.forEachIndexed { index, contact ->
                FormField(
                    contact.value,
                    { viewModel.onContact(index, contact.copy(value = it)) },
                    contact.kind.label(),
                    focusRequester = if (index == focusIndex) newContactFocus else null,
                    enabled = editable,
                    keyboardType =
                        when (contact.kind) {
                            ContactKind.PHONE -> KeyboardType.Phone
                            ContactKind.EMAIL -> KeyboardType.Email
                            else -> KeyboardType.Text
                        },
                    trailingIcon =
                        if (editable) {
                            {
                                IconButton(onClick = { viewModel.removeContact(index) }) {
                                    AppIcon(Res.drawable.ic_close, stringResource(Res.string.contact_remove))
                                }
                            }
                        } else {
                            null
                        },
                )
            }
            state.fieldErrors["contacts"]?.let { ErrorLine(stringResource(it.res)) }
            if (editable && form.contacts.size < Validators.CONTACTS_MAX) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    ContactKind.entries.forEach { kind ->
                        AppFilterChip(false, "+ ${kind.label()}", {
                            focusIndex = form.contacts.size
                            viewModel.addContact(kind)
                        })
                    }
                }
            }
        }
        FormField(
            form.notes,
            viewModel::onNotes,
            stringResource(Res.string.relative_notes),
            error = state.fieldErrors["notes"],
            enabled = editable,
            capitalization = KeyboardCapitalization.Sentences,
            singleLine = false,
        )
        ErrorText(state.error)
        if (editable) PrimaryButton(stringResource(Res.string.action_save), viewModel::save)
    }
    if (pickBirthday) {
        DayMonthYearDialog(
            title = stringResource(Res.string.relative_birthday),
            unknownYear = stringResource(Res.string.relative_birthday_year_unknown),
            clearLabel = stringResource(Res.string.relative_birthday_clear),
            day = form.birthDay,
            month = form.birthMonth,
            year = form.birthYear,
            onSave = { d, m, y ->
                pickBirthday = false
                viewModel.onBirthday(d, m, y)
            },
            onDismiss = { pickBirthday = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(Res.string.relative_delete_title, form.name),
            text = stringResource(Res.string.relative_delete_text),
            confirmLabel = stringResource(Res.string.action_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Family settings (FND-003). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FamilySettingsRoute(
    onBack: () -> Unit,
    viewModel: FamilySettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickZone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.done) { if (state.done) onBack() }
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.family_settings), onBack) }) {
        if (!state.loaded) return@AppPage
        val editable = state.canEdit && state.online
        if (state.canEdit && !state.online) NoticeBanner(stringResource(Res.string.family_needs_internet))
        if (!state.canEdit) ReadOnlyHint(stringResource(Res.string.family_settings_read_only))
        FormField(
            state.name,
            viewModel::onName,
            stringResource(Res.string.setup_family_name),
            error = state.fieldErrors[FamilySettingsViewModel.NAME],
            enabled = editable,
            capitalization = KeyboardCapitalization.Sentences,
        )
        Box(Modifier.clickable(enabled = editable) { pickZone = true }) {
            FormField(
                state.timeZone.replace('_', ' '),
                {},
                stringResource(Res.string.setup_time_zone),
                error = state.fieldErrors[FamilySettingsViewModel.TIME_ZONE],
                readOnly = true,
                enabled = false,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FormField(
                state.currency,
                viewModel::onCurrency,
                stringResource(Res.string.setup_currency),
                hint = stringResource(Res.string.setup_currency_hint),
                error = state.fieldErrors[FamilySettingsViewModel.CURRENCY],
                enabled = editable,
                capitalization = KeyboardCapitalization.Characters,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                CURRENCY_SUGGESTIONS.forEach { code ->
                    AppFilterChip(state.currency == code, code, { if (editable) viewModel.onCurrency(code) })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(stringResource(Res.string.setup_language), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AppFilterChip(state.language == "ru", stringResource(Res.string.language_ru), { if (editable) viewModel.onLanguage("ru") })
                AppFilterChip(state.language == "en", stringResource(Res.string.language_en), { if (editable) viewModel.onLanguage("en") })
            }
        }
        ErrorText(state.action.error)
        if (state.canEdit) {
            PrimaryButton(
                stringResource(Res.string.action_save),
                viewModel::save,
                enabled = state.online,
                loading = state.action.busy,
            )
        }
    }
    if (pickZone) {
        TimeZoneDialog(
            selected = state.timeZone,
            onSelect = {
                pickZone = false
                viewModel.onTimeZone(it)
            },
            onDismiss = { pickZone = false },
        )
    }
}
