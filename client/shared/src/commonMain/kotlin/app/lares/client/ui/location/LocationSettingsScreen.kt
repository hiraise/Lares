package app.lares.client.ui.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.resources.Res
import app.lares.client.resources.ic_battery_alert
import app.lares.client.resources.ic_battery_full
import app.lares.client.resources.ic_location_off
import app.lares.client.resources.ic_location_on
import app.lares.client.resources.ic_pause
import app.lares.client.resources.ic_play_arrow
import app.lares.client.resources.loc_action_allow_access
import app.lares.client.resources.loc_action_enable
import app.lares.client.resources.loc_action_pause
import app.lares.client.resources.loc_action_resume
import app.lares.client.resources.loc_action_turn_on_location
import app.lares.client.resources.loc_alert_watcher
import app.lares.client.resources.loc_alert_watchers_none
import app.lares.client.resources.loc_alert_watchers_title
import app.lares.client.resources.loc_battery_restricted
import app.lares.client.resources.loc_battery_row
import app.lares.client.resources.loc_battery_unrestricted
import app.lares.client.resources.loc_level_current
import app.lares.client.resources.loc_level_history
import app.lares.client.resources.loc_me_location_off_text
import app.lares.client.resources.loc_me_needs_permission
import app.lares.client.resources.loc_me_needs_permission_text
import app.lares.client.resources.loc_me_off
import app.lares.client.resources.loc_me_off_text
import app.lares.client.resources.loc_me_paused
import app.lares.client.resources.loc_me_paused_text
import app.lares.client.resources.loc_me_paused_until
import app.lares.client.resources.loc_me_sharing
import app.lares.client.resources.loc_me_sharing_text
import app.lares.client.resources.loc_me_stale
import app.lares.client.resources.loc_me_stale_text
import app.lares.client.resources.loc_no_pause_note
import app.lares.client.resources.loc_online_only
import app.lares.client.resources.loc_settings_this_phone
import app.lares.client.resources.loc_settings_title
import app.lares.client.resources.loc_status_off_location_disabled
import app.lares.client.resources.loc_watchers_admins
import app.lares.client.resources.loc_watchers_none
import app.lares.client.resources.loc_watchers_title
import app.lares.client.ui.common.rememberTimeFormatter
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LocationSettingsRoute(
    onBack: () -> Unit,
    viewModel: LocationSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        viewModel.loadTransparency()
        onPauseOrDispose { }
    }
    LocationSettingsScreen(
        state = state,
        actions =
            LocationSettingsActions(
                onBack = onBack,
                onSetup = viewModel::openSetup,
                onTurnOnLocation = viewModel::openLocationSettings,
                onPause = viewModel::openPause,
                onResume = viewModel::resume,
                onBattery = viewModel::openBattery,
            ),
    )
    if (state.setupOpen) {
        SharingSetupSheet(
            device = viewModel.device,
            watchers = state.watchers,
            enabling = state.enabling,
            error = state.enableError,
            onEnable = viewModel::enable,
            onDismiss = viewModel::closeSetup,
        )
    }
    if (state.pauseOpen) PauseSheet(onPause = viewModel::pause, onDismiss = viewModel::closePause)
    if (state.batteryOpen) {
        BatteryGuideSheet(
            unrestricted = state.device?.batteryUnrestricted == true,
            manufacturer = viewModel.device.manufacturer,
            onRequest = viewModel::requestBattery,
            onOpenSettings = viewModel::openBatterySettings,
            onDismiss = viewModel::closeBattery,
        )
    }
}

class LocationSettingsActions(
    val onBack: () -> Unit,
    val onSetup: () -> Unit,
    val onTurnOnLocation: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onBattery: () -> Unit,
)

/** Settings › Геопозиция (plan M4-07): stateless for UI tests. */
@Composable
fun LocationSettingsScreen(
    state: LocationSettingsUiState,
    actions: LocationSettingsActions,
) {
    val formatTime = rememberTimeFormatter()
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.loc_settings_title), actions.onBack) }) {
        ErrorText(state.error)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.loc_settings_this_phone))
            val status = state.status
            val (title, text) =
                when (status) {
                    SharingStatusText.SHARING -> {
                        stringResource(Res.string.loc_me_sharing) to stringResource(Res.string.loc_me_sharing_text)
                    }

                    SharingStatusText.PAUSED -> {
                        stringResource(Res.string.loc_me_paused) to stringResource(Res.string.loc_me_paused_text)
                    }

                    SharingStatusText.PAUSED_UNTIL -> {
                        stringResource(Res.string.loc_me_paused_until, formatTime(state.pausedUntil!!)) to
                            stringResource(Res.string.loc_me_paused_text)
                    }

                    SharingStatusText.OFF -> {
                        stringResource(Res.string.loc_me_off) to stringResource(Res.string.loc_me_off_text)
                    }

                    SharingStatusText.NEEDS_PERMISSION -> {
                        stringResource(Res.string.loc_me_needs_permission) to stringResource(Res.string.loc_me_needs_permission_text)
                    }

                    SharingStatusText.LOCATION_OFF -> {
                        stringResource(Res.string.loc_status_off_location_disabled) to stringResource(Res.string.loc_me_location_off_text)
                    }

                    SharingStatusText.STALE -> {
                        stringResource(Res.string.loc_me_stale) to stringResource(Res.string.loc_me_stale_text)
                    }
                }
            val warning =
                status == SharingStatusText.NEEDS_PERMISSION || status == SharingStatusText.LOCATION_OFF ||
                    status == SharingStatusText.STALE
            ListGroup {
                ListRow(
                    title,
                    modifier = Modifier.testTag("loc_status"),
                    icon = status.icon,
                    iconTint = if (warning) LaresTheme.colors.warning else status.tint(),
                    supporting = text,
                )
            }
        }
        when (state.status) {
            SharingStatusText.OFF -> {
                PrimaryButton(stringResource(Res.string.loc_action_enable), actions.onSetup, icon = Res.drawable.ic_location_on)
            }

            SharingStatusText.NEEDS_PERMISSION -> {
                PrimaryButton(stringResource(Res.string.loc_action_allow_access), actions.onSetup)
            }

            SharingStatusText.LOCATION_OFF -> {
                PrimaryButton(stringResource(Res.string.loc_action_turn_on_location), actions.onTurnOnLocation)
            }

            else -> {}
        }
        if (state.showResume) {
            PrimaryButton(
                stringResource(Res.string.loc_action_resume),
                actions.onResume,
                icon = Res.drawable.ic_play_arrow,
            )
        }
        if (state.showPause) TonalButton(stringResource(Res.string.loc_action_pause), actions.onPause, icon = Res.drawable.ic_pause)
        if (state.showNoPauseNote) {
            Text(
                stringResource(Res.string.loc_no_pause_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val unrestricted = state.device?.batteryUnrestricted == true
        ListGroup {
            ListRow(
                stringResource(Res.string.loc_battery_row),
                modifier = Modifier.testTag("loc_battery"),
                icon = if (unrestricted) Res.drawable.ic_battery_full else Res.drawable.ic_battery_alert,
                iconTint = if (unrestricted) MaterialTheme.colorScheme.onSurfaceVariant else LaresTheme.colors.warning,
                supporting = stringResource(if (unrestricted) Res.string.loc_battery_unrestricted else Res.string.loc_battery_restricted),
                showChevron = true,
                onClick = actions.onBattery,
            )
        }
        TransparencySections(state.transparency)
    }
}

/** «Кто меня видит» and «О вас сообщают» (LOC-015); online only. */
@Composable
private fun TransparencySections(transparency: Transparency) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(Res.string.loc_watchers_title))
        when (transparency) {
            Transparency.Loading -> {}

            Transparency.Unavailable -> {
                Text(
                    stringResource(Res.string.loc_online_only),
                    Modifier.testTag("loc_watchers_offline"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }

            is Transparency.Loaded -> {
                ListGroup(Modifier.testTag("loc_watchers")) {
                    if (transparency.viewers.isEmpty()) ListRow(stringResource(Res.string.loc_watchers_none))
                    transparency.viewers.forEachIndexed { index, viewer ->
                        if (index > 0) ListDivider(inset = 16.dp)
                        ListRow(
                            viewer.name,
                            supporting =
                                stringResource(if (viewer.history) Res.string.loc_level_history else Res.string.loc_level_current),
                        )
                    }
                }
            }
        }
        Text(
            stringResource(Res.string.loc_watchers_admins),
            Modifier.padding(horizontal = Spacing.xs),
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
    if (transparency is Transparency.Loaded) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.loc_alert_watchers_title))
            ListGroup(Modifier.testTag("loc_alert_watchers")) {
                if (transparency.alerts.isEmpty()) ListRow(stringResource(Res.string.loc_alert_watchers_none))
                transparency.alerts.forEachIndexed { index, row ->
                    if (index > 0) ListDivider(inset = 16.dp)
                    ListRow(stringResource(Res.string.loc_alert_watcher, row.name, row.places.joinToString(", ")))
                }
            }
        }
    }
}

private val SharingStatusText.icon: DrawableResource
    get() =
        when (this) {
            SharingStatusText.SHARING, SharingStatusText.STALE -> Res.drawable.ic_location_on
            SharingStatusText.PAUSED, SharingStatusText.PAUSED_UNTIL -> Res.drawable.ic_pause
            SharingStatusText.OFF, SharingStatusText.NEEDS_PERMISSION, SharingStatusText.LOCATION_OFF -> Res.drawable.ic_location_off
        }

@Composable
private fun SharingStatusText.tint(): Color =
    if (this == SharingStatusText.SHARING) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
