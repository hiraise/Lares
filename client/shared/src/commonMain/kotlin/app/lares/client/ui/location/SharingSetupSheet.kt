package app.lares.client.ui.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lares.client.data.location.LocationDevice
import app.lares.client.resources.Res
import app.lares.client.resources.action_continue
import app.lares.client.resources.action_done
import app.lares.client.resources.action_retry
import app.lares.client.resources.loc_setup_allow
import app.lares.client.resources.loc_setup_background_refused
import app.lares.client.resources.loc_setup_background_text
import app.lares.client.resources.loc_setup_background_title
import app.lares.client.resources.loc_setup_battery_action
import app.lares.client.resources.loc_setup_battery_text
import app.lares.client.resources.loc_setup_battery_title
import app.lares.client.resources.loc_setup_enabling
import app.lares.client.resources.loc_setup_explain_text
import app.lares.client.resources.loc_setup_explain_title
import app.lares.client.resources.loc_setup_fine_refused
import app.lares.client.resources.loc_setup_fine_text
import app.lares.client.resources.loc_setup_fine_title
import app.lares.client.resources.loc_setup_later
import app.lares.client.resources.loc_setup_notifications_refused
import app.lares.client.resources.loc_setup_notifications_text
import app.lares.client.resources.loc_setup_notifications_title
import app.lares.client.resources.loc_setup_open_settings
import app.lares.client.resources.loc_setup_skip
import app.lares.client.resources.loc_setup_watchers
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.rememberNotificationPermissionRequest
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * The sharing setup (plan M4-07, LOC-001, LOC-008; design § UI «Sharing setup»): explanation, precise location,
 * background location, notifications, battery saving, then [onEnable]. The steps come from [SharingSetupLogic] and the
 * phone's [device] status, checked again whenever the app is visible again. [watchers] are the names of those who will
 * see the member (null when unknown). «Не сейчас» closes the sheet without enabling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingSetupSheet(
    device: LocationDevice,
    watchers: List<String>?,
    enabling: Boolean,
    error: UiError?,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember { mutableStateOf(SharingSetupLogic.start()) }
    LifecycleResumeEffect(Unit) {
        state = SharingSetupLogic.onResume(state, device.status())
        onPauseOrDispose { }
    }
    val requestFine = rememberLocationPermissionRequest { state = SharingSetupLogic.onResult(state, SetupStep.FINE, device.status()) }
    val requestBackground =
        rememberBackgroundLocationRequest(device::openAppSettings) {
            state = SharingSetupLogic.onResult(state, SetupStep.BACKGROUND, device.status())
        }
    val requestNotifications =
        rememberNotificationPermissionRequest { state = SharingSetupLogic.onResult(state, SetupStep.NOTIFICATIONS, device.status()) }
    LaunchedEffect(state.step) { if (state.step == SetupStep.DONE) onEnable() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
    ) {
        SharingSetupContent(
            state = state,
            backgroundLabel = device.backgroundOptionLabel(),
            manufacturer = device.manufacturer,
            watchers = watchers,
            enabling = enabling,
            error = error,
            actions =
                SetupActions(
                    onContinue = { state = SharingSetupLogic.onContinue(state, device.status()) },
                    onAllowFine = {
                        state = SharingSetupLogic.onAsk(state)
                        if (state.refused) device.openAppSettings() else requestFine()
                    },
                    onAllowBackground = {
                        state = SharingSetupLogic.onAsk(state)
                        requestBackground()
                    },
                    onAllowNotifications = {
                        state = SharingSetupLogic.onAsk(state)
                        if (state.refused) device.openAppSettings() else requestNotifications()
                    },
                    onBatteryRequest = {
                        state = SharingSetupLogic.onBatteryRequested(state)
                        device.requestIgnoreBatteryOptimizations()
                    },
                    onBatteryDone = { state = SharingSetupLogic.onBatteryDone(state) },
                    onRetry = onEnable,
                    onLater = onDismiss,
                ),
        )
    }
}

/** What the setup's buttons do. */
class SetupActions(
    val onContinue: () -> Unit,
    val onAllowFine: () -> Unit,
    val onAllowBackground: () -> Unit,
    val onAllowNotifications: () -> Unit,
    val onBatteryRequest: () -> Unit,
    val onBatteryDone: () -> Unit,
    val onRetry: () -> Unit,
    val onLater: () -> Unit,
)

/** The sheet's content for one [SetupState]; stateless for UI tests. */
@Composable
fun SharingSetupContent(
    state: SetupState,
    backgroundLabel: String,
    manufacturer: String,
    watchers: List<String>?,
    enabling: Boolean,
    error: UiError?,
    actions: SetupActions,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("setup_${state.step.name.lowercase()}")
            .padding(horizontal = Spacing.screen)
            .padding(bottom = Spacing.xl)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        val openSettings = stringResource(Res.string.loc_setup_open_settings)
        val allow = stringResource(Res.string.loc_setup_allow)
        when (state.step) {
            SetupStep.EXPLAIN -> {
                Title(Res.string.loc_setup_explain_title)
                Body(stringResource(Res.string.loc_setup_explain_text))
                if (!watchers.isNullOrEmpty()) Body(stringResource(Res.string.loc_setup_watchers, watchers.joinToString(", ")))
                PrimaryButton(stringResource(Res.string.action_continue), actions.onContinue)
            }

            SetupStep.FINE -> {
                Title(Res.string.loc_setup_fine_title)
                Body(stringResource(if (state.refused) Res.string.loc_setup_fine_refused else Res.string.loc_setup_fine_text))
                PrimaryButton(if (state.refused) openSettings else allow, actions.onAllowFine)
            }

            SetupStep.BACKGROUND -> {
                Title(Res.string.loc_setup_background_title)
                Body(stringResource(Res.string.loc_setup_background_text, backgroundLabel))
                if (state.refused) Refused(stringResource(Res.string.loc_setup_background_refused))
                PrimaryButton(if (backgroundLocationInSettings() || state.refused) openSettings else allow, actions.onAllowBackground)
            }

            SetupStep.NOTIFICATIONS -> {
                Title(Res.string.loc_setup_notifications_title)
                Body(
                    stringResource(
                        if (state.refused) Res.string.loc_setup_notifications_refused else Res.string.loc_setup_notifications_text,
                    ),
                )
                PrimaryButton(if (state.refused) openSettings else allow, actions.onAllowNotifications)
            }

            SetupStep.BATTERY -> {
                Title(Res.string.loc_setup_battery_title)
                Body(stringResource(Res.string.loc_setup_battery_text))
                if (state.batteryRequested) {
                    BatteryHint(manufacturer)
                    PrimaryButton(stringResource(Res.string.action_done), actions.onBatteryDone)
                } else {
                    PrimaryButton(stringResource(Res.string.loc_setup_battery_action), actions.onBatteryRequest)
                    LinkButton(
                        stringResource(Res.string.loc_setup_skip),
                        actions.onBatteryDone,
                        Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }

            SetupStep.DONE -> {
                Title(Res.string.loc_setup_explain_title)
                if (error == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Body(stringResource(Res.string.loc_setup_enabling))
                    }
                } else {
                    ErrorText(error)
                    PrimaryButton(stringResource(Res.string.action_retry), actions.onRetry, loading = enabling)
                }
            }
        }
        if (state.step != SetupStep.BATTERY && !(state.step == SetupStep.DONE && error == null)) {
            LinkButton(stringResource(Res.string.loc_setup_later), actions.onLater, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun Title(text: org.jetbrains.compose.resources.StringResource) {
    Text(stringResource(text), style = MaterialTheme.typography.headlineSmall)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Refused(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
