package app.familyhub.client.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.familyhub.client.platform.AppLanguage
import app.familyhub.client.platform.AppearanceSettings
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.action_continue
import app.familyhub.client.resources.app_logo
import app.familyhub.client.resources.app_name
import app.familyhub.client.resources.ic_language
import app.familyhub.client.resources.ic_lock
import app.familyhub.client.resources.ic_qr_code_scanner
import app.familyhub.client.resources.language_title
import app.familyhub.client.resources.or
import app.familyhub.client.resources.server_address
import app.familyhub.client.resources.server_address_hint
import app.familyhub.client.resources.server_privacy
import app.familyhub.client.resources.server_scan_qr
import app.familyhub.client.resources.server_tagline
import app.familyhub.client.ui.common.label
import app.familyhub.client.ui.components.AppIcon
import app.familyhub.client.ui.components.AppLogo
import app.familyhub.client.ui.components.AppPage
import app.familyhub.client.ui.components.ChoiceDialog
import app.familyhub.client.ui.components.ErrorText
import app.familyhub.client.ui.components.FormField
import app.familyhub.client.ui.components.PrimaryButton
import app.familyhub.client.ui.components.TonalButton
import app.familyhub.client.ui.theme.FamilyHubTheme
import app.familyhub.client.ui.theme.HeroShape
import app.familyhub.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ServerRoute(
    onNext: (AfterConnect) -> Unit,
    onScanQr: () -> Unit,
    viewModel: ServerViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.next) {
        state.next?.let {
            viewModel.onNavigated()
            onNext(it)
        }
    }
    ServerScreen(state, viewModel::onUrlChange, viewModel::connect, onScanQr, languageSwitch = { LanguageSwitch() })
}

/** FND-060, mockup `Login.dc.html`. */
@Composable
fun ServerScreen(
    state: ServerUiState,
    onUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onScanQr: () -> Unit,
    languageSwitch: @Composable () -> Unit = {},
) {
    AppPage(contentPadding = Spacing.xl) {
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppLogo(stringResource(Res.string.app_logo))
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(Res.string.server_tagline),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 290.dp),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, HeroShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            FormField(
                value = state.url,
                onValueChange = onUrlChange,
                label = stringResource(Res.string.server_address),
                hint = stringResource(Res.string.server_address_hint),
                error = state.urlError,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                onDone = onConnect,
            )
            ErrorText(state.error)
            PrimaryButton(stringResource(Res.string.action_continue), onConnect, loading = state.loading)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Box(Modifier.weight(1f).height(1.dp).background(FamilyHubTheme.colors.divider))
                Text(
                    stringResource(Res.string.or),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f).height(1.dp).background(FamilyHubTheme.colors.divider))
            }
            TonalButton(
                stringResource(Res.string.server_scan_qr),
                onScanQr,
                icon = Res.drawable.ic_qr_code_scanner,
                enabled = !state.loading,
            )
        }
        Spacer(Modifier.height(Spacing.s))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Row(Modifier.widthIn(max = 310.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(Res.drawable.ic_lock, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(Res.string.server_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            languageSwitch()
        }
    }
}

/** Outlined chip with the current UI language; opens the language choice (FND-061). */
@Composable
fun LanguageSwitch(appearance: AppearanceSettings = koinInject()) {
    val prefs by appearance.preferences.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val current = prefs?.language ?: AppLanguage.SYSTEM
    val shown =
        when {
            current != AppLanguage.SYSTEM -> current
            Locale.current.language == "ru" -> AppLanguage.RUSSIAN
            else -> AppLanguage.ENGLISH
        }
    OutlinedButton(onClick = { open = true }, shape = CircleShape, modifier = Modifier.height(40.dp)) {
        AppIcon(Res.drawable.ic_language, null, size = 20.dp, tint = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.padding(start = Spacing.s))
        Text(shown.label(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
    if (open) {
        ChoiceDialog(
            title = stringResource(Res.string.language_title),
            options = AppLanguage.entries,
            selected = current,
            label = { it.label() },
            onSelect = {
                open = false
                appearance.setLanguage(it)
            },
            onDismiss = { open = false },
        )
    }
}
