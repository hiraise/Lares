package app.lares.client.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.field_login
import app.lares.client.resources.field_login_hint
import app.lares.client.resources.field_password
import app.lares.client.resources.field_password_hint
import app.lares.client.resources.field_password_repeat
import app.lares.client.resources.field_your_name
import app.lares.client.resources.language_en
import app.lares.client.resources.language_ru
import app.lares.client.resources.setup_code
import app.lares.client.resources.setup_code_hint
import app.lares.client.resources.setup_currency
import app.lares.client.resources.setup_currency_hint
import app.lares.client.resources.setup_family_name
import app.lares.client.resources.setup_language
import app.lares.client.resources.setup_section_admin
import app.lares.client.resources.setup_section_family
import app.lares.client.resources.setup_submit
import app.lares.client.resources.setup_subtitle
import app.lares.client.resources.setup_time_zone
import app.lares.client.resources.setup_time_zone_search
import app.lares.client.resources.setup_title
import app.lares.client.ui.common.FieldError
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.PasswordField
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.theme.HeroShape
import app.lares.client.ui.theme.Spacing
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SetupRoute(onBack: () -> Unit) {
    val locale = Locale.current
    val defaults = remember { setupDefaults(locale.language, locale.region) }
    val viewModel: SetupViewModel = koinViewModel { parametersOf(defaults) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SetupScreen(
        state = state,
        actions =
            SetupActions(
                onSetupCode = viewModel::onSetupCodeChange,
                onHouseholdName = viewModel::onHouseholdNameChange,
                onTimeZone = viewModel::onTimeZoneChange,
                onCurrency = viewModel::onCurrencyChange,
                onLanguage = viewModel::onLanguageChange,
                onDisplayName = viewModel::onDisplayNameChange,
                onLogin = viewModel::onLoginChange,
                onPassword = viewModel::onPasswordChange,
                onPasswordRepeat = viewModel::onPasswordRepeatChange,
                onSubmit = viewModel::submit,
                onBack = onBack,
            ),
    )
}

class SetupActions(
    val onSetupCode: (String) -> Unit,
    val onHouseholdName: (String) -> Unit,
    val onTimeZone: (String) -> Unit,
    val onCurrency: (String) -> Unit,
    val onLanguage: (String) -> Unit,
    val onDisplayName: (String) -> Unit,
    val onLogin: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onPasswordRepeat: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onBack: () -> Unit,
)

/** FND-001: one scrolling form, sections "Family" and "Administrator". */
@Composable
fun SetupScreen(
    state: SetupUiState,
    actions: SetupActions,
) {
    var pickZone by rememberSaveable { mutableStateOf(false) }

    fun error(field: String): FieldError? = state.fieldErrors[field]
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.setup_title), actions.onBack) }) {
        Text(
            stringResource(Res.string.setup_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormCard {
            SectionHeader(stringResource(Res.string.setup_section_family))
            FormField(
                state.setupCode,
                actions.onSetupCode,
                stringResource(Res.string.setup_code),
                hint = stringResource(Res.string.setup_code_hint),
                error = error(SetupFields.SETUP_CODE),
                capitalization = KeyboardCapitalization.Characters,
            )
            FormField(
                state.householdName,
                actions.onHouseholdName,
                stringResource(Res.string.setup_family_name),
                error = error(SetupFields.HOUSEHOLD_NAME),
                capitalization = KeyboardCapitalization.Sentences,
            )
            Box(Modifier.clickable { pickZone = true }) {
                FormField(
                    state.timeZone,
                    {},
                    stringResource(Res.string.setup_time_zone),
                    error = error(SetupFields.TIME_ZONE),
                    readOnly = true,
                    enabled = false,
                )
            }
            FormField(
                state.currency,
                actions.onCurrency,
                stringResource(Res.string.setup_currency),
                hint = stringResource(Res.string.setup_currency_hint),
                error = error(SetupFields.CURRENCY),
                capitalization = KeyboardCapitalization.Characters,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                CURRENCY_SUGGESTIONS.forEach { code ->
                    AppFilterChip(state.currency == code, code, { actions.onCurrency(code) })
                }
            }
            Text(stringResource(Res.string.setup_language), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AppFilterChip(state.language == "ru", stringResource(Res.string.language_ru), { actions.onLanguage("ru") })
                AppFilterChip(state.language == "en", stringResource(Res.string.language_en), { actions.onLanguage("en") })
            }
        }
        FormCard {
            SectionHeader(stringResource(Res.string.setup_section_admin))
            FormField(
                state.displayName,
                actions.onDisplayName,
                stringResource(Res.string.field_your_name),
                error = error(SetupFields.DISPLAY_NAME),
                capitalization = KeyboardCapitalization.Words,
            )
            FormField(
                state.login,
                actions.onLogin,
                stringResource(Res.string.field_login),
                hint = stringResource(Res.string.field_login_hint),
                error = error(SetupFields.LOGIN),
            )
            PasswordField(
                state.password,
                actions.onPassword,
                stringResource(Res.string.field_password),
                hint = stringResource(Res.string.field_password_hint),
                error = error(SetupFields.PASSWORD),
            )
            PasswordField(
                state.passwordRepeat,
                actions.onPasswordRepeat,
                stringResource(Res.string.field_password_repeat),
                error = error(SetupFields.PASSWORD_REPEAT),
                imeAction = ImeAction.Done,
                onDone = actions.onSubmit,
            )
        }
        ErrorText(state.error)
        PrimaryButton(stringResource(Res.string.setup_submit), actions.onSubmit, loading = state.loading)
    }
    if (pickZone) {
        TimeZoneDialog(
            selected = state.timeZone,
            onSelect = {
                pickZone = false
                actions.onTimeZone(it)
            },
            onDismiss = { pickZone = false },
        )
    }
}

@Composable
internal fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, HeroShape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
        content = content,
    )
}

@Composable
internal fun TimeZoneDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val zones = remember { TimeZone.availableZoneIds.filter { it.contains('/') && !it.startsWith("Etc/") }.sorted() }
    val shown = remember(query) { zones.filter { it.contains(query.trim().replace(' ', '_'), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.setup_time_zone), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                FormField(query, { query = it }, stringResource(Res.string.setup_time_zone_search), imeAction = ImeAction.Search)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it }) { zone ->
                        Text(
                            zone.replace('_', ' '),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (zone == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(zone) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { LinkButton(stringResource(Res.string.action_cancel), onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    )
}

internal val CURRENCY_SUGGESTIONS = listOf("RUB", "EUR", "USD")

private val EURO_REGIONS =
    setOf("AT", "BE", "HR", "CY", "EE", "FI", "FR", "DE", "GR", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PT", "SK", "SI", "ES")

/** Device defaults for the setup form: zone of the device, currency of the region, language of the UI. */
fun setupDefaults(
    language: String,
    region: String,
): SetupDefaults {
    val currency =
        when {
            region == "RU" -> "RUB"
            region == "BY" -> "BYN"
            region == "KZ" -> "KZT"
            region == "UA" -> "UAH"
            region == "GB" -> "GBP"
            region in EURO_REGIONS -> "EUR"
            region == "US" -> "USD"
            language == "ru" -> "RUB"
            else -> "EUR"
        }
    return SetupDefaults(
        timeZone = TimeZone.currentSystemDefault().id,
        currency = currency,
        language = if (language == "ru") "ru" else "en",
    )
}
