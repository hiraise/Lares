package app.familyhub.client.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.action_continue
import app.familyhub.client.resources.app_logo
import app.familyhub.client.resources.field_login
import app.familyhub.client.resources.field_password
import app.familyhub.client.resources.ic_qr_code_scanner
import app.familyhub.client.resources.invite_code_hint
import app.familyhub.client.resources.invite_code_label
import app.familyhub.client.resources.invite_scan
import app.familyhub.client.resources.login_change_server
import app.familyhub.client.resources.login_have_invite
import app.familyhub.client.resources.login_qr_hint
import app.familyhub.client.resources.login_submit
import app.familyhub.client.resources.login_title
import app.familyhub.client.resources.login_with_qr
import app.familyhub.client.ui.common.FieldError
import app.familyhub.client.ui.components.AppLogo
import app.familyhub.client.ui.components.AppPage
import app.familyhub.client.ui.components.ConfirmDialog
import app.familyhub.client.ui.components.ErrorText
import app.familyhub.client.ui.components.FormField
import app.familyhub.client.ui.components.LinkButton
import app.familyhub.client.ui.components.PasswordField
import app.familyhub.client.ui.components.PrimaryButton
import app.familyhub.client.ui.components.TonalButton
import app.familyhub.client.ui.theme.Spacing
import app.familyhub.contract.api.FieldErrorCode
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LoginRoute(
    onScanQr: () -> Unit,
    onInviteCode: (String) -> Unit,
    onChangeServer: () -> Unit,
    onNeedsSetup: () -> Unit,
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.needsSetup) {
        if (state.needsSetup) {
            viewModel.onSetupOpened()
            onNeedsSetup()
        }
    }
    LoginScreen(state, viewModel::onLoginChange, viewModel::onPasswordChange, viewModel::submit, onScanQr, onInviteCode, onChangeServer)
}

/** FND-010, FND-014, FND-024: password login, QR login, invitation. */
@Composable
fun LoginScreen(
    state: LoginUiState,
    onLoginChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanQr: () -> Unit,
    onInviteCode: (String) -> Unit,
    onChangeServer: () -> Unit,
) {
    var inviteDialog by rememberSaveable { mutableStateOf(false) }
    AppPage(contentPadding = Spacing.xl) {
        Column(
            Modifier.fillMaxWidth().padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            AppLogo(stringResource(Res.string.app_logo), size = 64.dp)
            Text(stringResource(Res.string.login_title), style = MaterialTheme.typography.headlineLarge)
            Text(state.serverUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FormCard {
            FormField(
                state.login,
                onLoginChange,
                stringResource(Res.string.field_login),
                error = state.loginError,
                capitalization = KeyboardCapitalization.None,
            )
            PasswordField(
                state.password,
                onPasswordChange,
                stringResource(Res.string.field_password),
                error = state.passwordError,
                imeAction = ImeAction.Done,
                onDone = onSubmit,
            )
            ErrorText(state.error)
            PrimaryButton(stringResource(Res.string.login_submit), onSubmit, loading = state.loading)
        }
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TonalButton(stringResource(Res.string.login_with_qr), onScanQr, icon = Res.drawable.ic_qr_code_scanner)
            Text(
                stringResource(Res.string.login_qr_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinkButton(stringResource(Res.string.login_have_invite), { inviteDialog = true })
            LinkButton(stringResource(Res.string.login_change_server), onChangeServer)
        }
    }
    if (inviteDialog) {
        InviteCodeDialog(
            onCode = {
                inviteDialog = false
                onInviteCode(it)
            },
            onScan = {
                inviteDialog = false
                onScanQr()
            },
            onDismiss = { inviteDialog = false },
        )
    }
}

/** Type an invite code, or switch to the scanner. */
@Composable
private fun InviteCodeDialog(
    onCode: (String) -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }
    ConfirmDialog(
        title = stringResource(Res.string.login_have_invite),
        text = stringResource(Res.string.invite_code_hint),
        confirmLabel = stringResource(Res.string.action_continue),
        onConfirm = { if (code.isBlank()) error = true else onCode(code.trim()) },
        onDismiss = onDismiss,
    ) {
        FormField(
            code,
            {
                code = it
                error = false
            },
            stringResource(Res.string.invite_code_label),
            error = if (error) FieldError.of(FieldErrorCode.REQUIRED) else null,
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done,
        )
        TonalButton(stringResource(Res.string.invite_scan), onScan, icon = Res.drawable.ic_qr_code_scanner)
    }
}
