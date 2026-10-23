package app.lares.client.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.platform.AppLanguage
import app.lares.client.platform.ThemeMode
import app.lares.client.resources.Res
import app.lares.client.resources.about_app_version
import app.lares.client.resources.about_licenses
import app.lares.client.resources.about_server_address
import app.lares.client.resources.about_server_version
import app.lares.client.resources.action_retry
import app.lares.client.resources.action_save
import app.lares.client.resources.delete_confirm
import app.lares.client.resources.delete_text
import app.lares.client.resources.delete_title
import app.lares.client.resources.devices_last_seen
import app.lares.client.resources.devices_revoke
import app.lares.client.resources.devices_revoke_text
import app.lares.client.resources.devices_revoke_title
import app.lares.client.resources.devices_this
import app.lares.client.resources.field_password
import app.lares.client.resources.field_password_repeat
import app.lares.client.resources.ic_delete
import app.lares.client.resources.ic_devices
import app.lares.client.resources.ic_info
import app.lares.client.resources.ic_language
import app.lares.client.resources.ic_lock
import app.lares.client.resources.ic_logout
import app.lares.client.resources.ic_notifications
import app.lares.client.resources.ic_palette
import app.lares.client.resources.ic_person
import app.lares.client.resources.ic_qr_code_2
import app.lares.client.resources.language_title
import app.lares.client.resources.licenses_text
import app.lares.client.resources.login_qr_code
import app.lares.client.resources.login_qr_expired
import app.lares.client.resources.login_qr_expires
import app.lares.client.resources.login_qr_new
import app.lares.client.resources.login_qr_text
import app.lares.client.resources.login_qr_title
import app.lares.client.resources.logout_confirm
import app.lares.client.resources.logout_text
import app.lares.client.resources.logout_text_pending
import app.lares.client.resources.logout_title
import app.lares.client.resources.member_login_qr_text
import app.lares.client.resources.member_login_qr_title
import app.lares.client.resources.password_changed
import app.lares.client.resources.password_current
import app.lares.client.resources.password_new
import app.lares.client.resources.password_title
import app.lares.client.resources.profile_change_password
import app.lares.client.resources.profile_login
import app.lares.client.resources.profile_login_other
import app.lares.client.resources.profile_name
import app.lares.client.resources.profile_no_login
import app.lares.client.resources.profile_saved
import app.lares.client.resources.profile_title
import app.lares.client.resources.relative_color
import app.lares.client.resources.settings_about
import app.lares.client.resources.settings_appearance
import app.lares.client.resources.settings_delete_account
import app.lares.client.resources.settings_devices
import app.lares.client.resources.settings_language
import app.lares.client.resources.settings_logout
import app.lares.client.resources.settings_notifications
import app.lares.client.resources.settings_profile
import app.lares.client.resources.settings_theme
import app.lares.client.resources.settings_title
import app.lares.client.resources.unknown_value
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.label
import app.lares.client.ui.common.rememberTimeFormatter
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.ChoiceDialog
import app.lares.client.ui.components.ColorSwatches
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.PasswordField
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.onboarding.SetupFields
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import app.lares.contract.Codes
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Where the settings screens lead. */
class SettingsNavigation(
    val onBack: () -> Unit,
    val onProfile: () -> Unit,
    val onDevices: () -> Unit,
    val onNotifications: () -> Unit,
    val onAbout: () -> Unit,
)

@Composable
fun SettingsRoute(
    navigation: SettingsNavigation,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.settings_title), navigation.onBack) }) {
        ListGroup {
            ListRow(
                stringResource(Res.string.settings_profile),
                icon = Res.drawable.ic_person,
                showChevron = true,
                onClick = navigation.onProfile,
            )
            ListDivider()
            ListRow(
                stringResource(Res.string.settings_devices),
                icon = Res.drawable.ic_devices,
                showChevron = true,
                onClick = navigation.onDevices,
            )
            ListDivider()
            ListRow(
                stringResource(Res.string.settings_notifications),
                icon = Res.drawable.ic_notifications,
                showChevron = true,
                onClick = navigation.onNotifications,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.settings_appearance))
            ListGroup {
                ListRow(
                    stringResource(Res.string.settings_theme),
                    icon = Res.drawable.ic_palette,
                    trailingText = state.preferences.theme.label(),
                    onClick = { dialog = SettingsDialog.THEME },
                )
                ListDivider()
                ListRow(
                    stringResource(Res.string.settings_language),
                    icon = Res.drawable.ic_language,
                    trailingText = state.preferences.language.label(),
                    onClick = { dialog = SettingsDialog.LANGUAGE },
                )
            }
        }
        ListGroup {
            ListRow(
                stringResource(Res.string.settings_about),
                icon = Res.drawable.ic_info,
                showChevron = true,
                onClick = navigation.onAbout,
            )
        }
        ListGroup {
            ListRow(stringResource(Res.string.settings_logout), icon = Res.drawable.ic_logout, onClick = { dialog = SettingsDialog.LOGOUT })
            ListDivider()
            ListRow(
                stringResource(Res.string.settings_delete_account),
                icon = Res.drawable.ic_delete,
                titleColor = MaterialTheme.colorScheme.error,
                iconTint = MaterialTheme.colorScheme.error,
                onClick = { dialog = SettingsDialog.DELETE },
            )
        }
    }
    when (dialog) {
        SettingsDialog.THEME -> {
            ChoiceDialog(
                stringResource(Res.string.settings_theme),
                ThemeMode.entries,
                state.preferences.theme,
                { it.label() },
                onSelect = {
                    dialog = null
                    viewModel.setTheme(it)
                },
                onDismiss = { dialog = null },
            )
        }

        SettingsDialog.LANGUAGE -> {
            ChoiceDialog(
                stringResource(Res.string.language_title),
                AppLanguage.entries,
                state.preferences.language,
                { it.label() },
                onSelect = {
                    dialog = null
                    viewModel.setLanguage(it)
                },
                onDismiss = { dialog = null },
            )
        }

        SettingsDialog.LOGOUT -> {
            ConfirmDialog(
                title = stringResource(Res.string.logout_title),
                text =
                    stringResource(
                        if (state.pendingChanges > 0) Res.string.logout_text_pending else Res.string.logout_text,
                    ),
                confirmLabel = stringResource(Res.string.logout_confirm),
                onConfirm = {
                    dialog = null
                    viewModel.logout()
                },
                onDismiss = { dialog = null },
                destructive = state.pendingChanges > 0,
            )
        }

        SettingsDialog.DELETE -> {
            DeleteAccountDialog(
                hasPassword = state.hasPassword,
                deleting = state.deleting,
                error = state.deleteError,
                onConfirm = viewModel::deleteAccount,
                onDismiss = {
                    dialog = null
                    viewModel.clearDeleteError()
                },
            )
        }

        null -> {}
    }
}

private enum class SettingsDialog { THEME, LANGUAGE, LOGOUT, DELETE }

/** FND-013: confirmation, with the password when the account has one; `LAST_ADMIN` is explained by its message. */
@Composable
private fun DeleteAccountDialog(
    hasPassword: Boolean,
    deleting: Boolean,
    error: UiError?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    ConfirmDialog(
        title = stringResource(Res.string.delete_title),
        text = stringResource(Res.string.delete_text),
        confirmLabel = stringResource(Res.string.delete_confirm),
        onConfirm = { if (!deleting) onConfirm(if (hasPassword) password else null) },
        onDismiss = onDismiss,
        destructive = true,
    ) {
        if (hasPassword) {
            PasswordField(password, { password = it }, stringResource(Res.string.field_password), imeAction = ImeAction.Done)
        }
        ErrorText(error)
    }
}

@Composable
fun ProfileRoute(
    onBack: () -> Unit,
    onChangePassword: () -> Unit,
    onLoginOtherDevice: () -> Unit,
    viewModel: ProfileViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.profile_title), onBack) }) {
        ListGroup(Modifier) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                FormField(
                    state.name,
                    viewModel::onNameChange,
                    stringResource(Res.string.profile_name),
                    error = state.nameError,
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                    onDone = viewModel::save,
                    hint = if (state.saved) stringResource(Res.string.profile_saved) else null,
                )
                PrimaryButton(stringResource(Res.string.action_save), viewModel::save, enabled = state.name.trim() != state.savedName)
            }
        }
        ListGroup {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(stringResource(Res.string.relative_color), style = MaterialTheme.typography.labelLarge)
                ColorSwatches(state.color, viewModel::onColor)
            }
        }
        ListGroup {
            ListRow(
                stringResource(Res.string.profile_login),
                icon = Res.drawable.ic_person,
                trailingText = state.login ?: stringResource(Res.string.profile_no_login),
            )
            if (state.login != null) {
                ListDivider()
                ListRow(
                    stringResource(Res.string.profile_change_password),
                    icon = Res.drawable.ic_lock,
                    showChevron = true,
                    onClick = onChangePassword,
                )
            }
            ListDivider()
            ListRow(
                stringResource(Res.string.profile_login_other),
                icon = Res.drawable.ic_qr_code_2,
                showChevron = true,
                onClick = onLoginOtherDevice,
            )
        }
    }
}

@Composable
fun ChangePasswordRoute(
    onBack: () -> Unit,
    viewModel: ChangePasswordViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.password_title), onBack) }) {
        if (state.done) {
            Text(stringResource(Res.string.password_changed), style = MaterialTheme.typography.titleMedium)
            return@AppPage
        }
        ListGroup {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                if (state.hasPassword) {
                    PasswordField(
                        state.current,
                        viewModel::onCurrentChange,
                        stringResource(Res.string.password_current),
                        error = state.fieldErrors[ChangePasswordViewModel.CURRENT],
                    )
                }
                PasswordField(
                    state.new,
                    viewModel::onNewChange,
                    stringResource(Res.string.password_new),
                    error = state.fieldErrors[ChangePasswordViewModel.NEW],
                )
                PasswordField(
                    state.repeat,
                    viewModel::onRepeatChange,
                    stringResource(Res.string.field_password_repeat),
                    error = state.fieldErrors[SetupFields.PASSWORD_REPEAT],
                    imeAction = ImeAction.Done,
                    onDone = viewModel::submit,
                )
            }
        }
        ErrorText(state.error)
        PrimaryButton(stringResource(Res.string.action_save), viewModel::submit, loading = state.loading)
    }
}

@Composable
fun LoginQrRoute(
    onBack: () -> Unit,
    memberId: String? = null,
    memberName: String? = null,
    viewModel: LoginQrViewModel = koinViewModel(key = "login-qr-$memberId") { parametersOf(*listOfNotNull(memberId).toTypedArray()) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title =
        if (memberName !=
            null
        ) {
            stringResource(Res.string.member_login_qr_title, memberName)
        } else {
            stringResource(Res.string.login_qr_title)
        }
    AppPage(topBar = { DetailTopBar(title, onBack) }) {
        Text(
            stringResource(if (memberId != null) Res.string.member_login_qr_text else Res.string.login_qr_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val payload = state.payload
        if (state.error != null) {
            ErrorText(state.error)
            TonalButton(stringResource(Res.string.action_retry), viewModel::create)
            return@AppPage
        }
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            // The QR is always dark on white, in both themes, so every camera reads it.
            Box(
                Modifier.size(264.dp).background(Color.White, MaterialTheme.shapes.large).padding(Spacing.l),
                contentAlignment = Alignment.Center,
            ) {
                if (payload != null && !state.expired) {
                    Image(
                        rememberQrCodePainter(payload),
                        contentDescription = stringResource(Res.string.login_qr_title),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val code = state.code
            if (state.expired) {
                Text(
                    stringResource(Res.string.login_qr_expired),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                PrimaryButton(stringResource(Res.string.login_qr_new), viewModel::create)
            } else if (code != null) {
                val seconds = state.remaining.inWholeSeconds
                val time = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                Text(
                    stringResource(Res.string.login_qr_expires, time),
                    style = MaterialTheme.typography.titleMedium.tabular(),
                )
                SelectionContainer {
                    Text(
                        stringResource(Res.string.login_qr_code, Codes.format(code)),
                        style = MaterialTheme.typography.bodyMedium.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun DevicesRoute(
    onBack: () -> Unit,
    viewModel: DevicesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val formatTime = rememberTimeFormatter()
    var revoking by rememberSaveable { mutableStateOf<String?>(null) }
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.settings_devices), onBack) }) {
        ErrorText(state.error)
        val sessions = state.sessions
        if (sessions == null) {
            if (state.error != null) TonalButton(stringResource(Res.string.action_retry), viewModel::load)
            return@AppPage
        }
        ListGroup {
            sessions.forEachIndexed { index, session ->
                if (index > 0) ListDivider()
                ListRow(
                    title = session.deviceName,
                    icon = Res.drawable.ic_devices,
                    supporting =
                        if (session.current) {
                            stringResource(Res.string.devices_this)
                        } else {
                            stringResource(Res.string.devices_last_seen, formatTime(session.lastSeenAt))
                        },
                    trailing =
                        if (session.current) {
                            null
                        } else {
                            {
                                LinkButton(
                                    stringResource(Res.string.devices_revoke),
                                    { revoking = session.id },
                                    enabled = !state.loading,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                )
            }
        }
    }
    val target = state.sessions?.firstOrNull { it.id == revoking }
    if (target != null) {
        ConfirmDialog(
            title = stringResource(Res.string.devices_revoke_title, target.deviceName),
            text = stringResource(Res.string.devices_revoke_text),
            confirmLabel = stringResource(Res.string.devices_revoke),
            onConfirm = {
                revoking = null
                viewModel.revoke(target.id)
            },
            onDismiss = { revoking = null },
            destructive = true,
        )
    }
}

@Composable
fun AboutRoute(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    viewModel: AboutViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val unknown = stringResource(Res.string.unknown_value)
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.settings_about), onBack) }) {
        ListGroup {
            ListRow(stringResource(Res.string.about_app_version), trailingText = state.appVersion)
            ListDivider(16.dp)
            ListRow(stringResource(Res.string.about_server_version), trailingText = state.serverVersion ?: unknown)
            ListDivider(16.dp)
            ListRow(stringResource(Res.string.about_server_address), supporting = state.serverUrl)
        }
        ListGroup {
            ListRow(stringResource(Res.string.about_licenses), showChevron = true, onClick = onLicenses)
        }
    }
}

@Composable
fun LicensesRoute(onBack: () -> Unit) {
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.about_licenses), onBack) }) {
        SelectionContainer {
            Text(stringResource(Res.string.licenses_text), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
