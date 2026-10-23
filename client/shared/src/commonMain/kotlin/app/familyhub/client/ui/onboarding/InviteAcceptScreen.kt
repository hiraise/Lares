package app.familyhub.client.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.action_retry
import app.familyhub.client.resources.field_login
import app.familyhub.client.resources.field_login_hint
import app.familyhub.client.resources.field_password
import app.familyhub.client.resources.field_password_hint
import app.familyhub.client.resources.field_password_repeat
import app.familyhub.client.resources.field_your_name
import app.familyhub.client.resources.invite_checking
import app.familyhub.client.resources.invite_preview
import app.familyhub.client.resources.invite_submit
import app.familyhub.client.resources.invite_title
import app.familyhub.client.ui.components.AppPage
import app.familyhub.client.ui.components.DetailTopBar
import app.familyhub.client.ui.components.ErrorText
import app.familyhub.client.ui.components.FormField
import app.familyhub.client.ui.components.FullScreenLoading
import app.familyhub.client.ui.components.PasswordField
import app.familyhub.client.ui.components.PrimaryButton
import app.familyhub.client.ui.components.TonalButton
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InviteAcceptRoute(
    code: String,
    onBack: () -> Unit,
) {
    val viewModel: InviteAcceptViewModel = koinViewModel(key = "invite-$code") { parametersOf(code) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    InviteAcceptScreen(
        state = state,
        onRetry = viewModel::loadPreview,
        onDisplayName = viewModel::onDisplayNameChange,
        onLogin = viewModel::onLoginChange,
        onPassword = viewModel::onPasswordChange,
        onPasswordRepeat = viewModel::onPasswordRepeatChange,
        onSubmit = viewModel::submit,
        onBack = onBack,
    )
}

/** FND-024: who invites to which family, then the new account. */
@Composable
fun InviteAcceptScreen(
    state: InviteAcceptUiState,
    onRetry: () -> Unit,
    onDisplayName: (String) -> Unit,
    onLogin: (String) -> Unit,
    onPassword: (String) -> Unit,
    onPasswordRepeat: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val preview = state.preview
    if (preview == null && state.previewError == null) {
        FullScreenLoading(text = stringResource(Res.string.invite_checking))
        return
    }
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.invite_title), onBack) }) {
        if (preview == null) {
            ErrorText(state.previewError)
            TonalButton(stringResource(Res.string.action_retry), onRetry)
            return@AppPage
        }
        Text(
            stringResource(Res.string.invite_preview, preview.inviterName, preview.householdName),
            style = MaterialTheme.typography.titleLarge,
        )
        FormCard {
            FormField(
                state.displayName,
                onDisplayName,
                stringResource(Res.string.field_your_name),
                error = state.fieldErrors[InviteFields.DISPLAY_NAME],
                capitalization = KeyboardCapitalization.Words,
            )
            FormField(
                state.login,
                onLogin,
                stringResource(Res.string.field_login),
                hint = stringResource(Res.string.field_login_hint),
                error = state.fieldErrors[InviteFields.LOGIN],
            )
            PasswordField(
                state.password,
                onPassword,
                stringResource(Res.string.field_password),
                hint = stringResource(Res.string.field_password_hint),
                error = state.fieldErrors[InviteFields.PASSWORD],
            )
            PasswordField(
                state.passwordRepeat,
                onPasswordRepeat,
                stringResource(Res.string.field_password_repeat),
                error = state.fieldErrors[SetupFields.PASSWORD_REPEAT],
                imeAction = ImeAction.Done,
                onDone = onSubmit,
            )
        }
        ErrorText(state.error)
        PrimaryButton(stringResource(Res.string.invite_submit), onSubmit, loading = state.loading)
    }
}
