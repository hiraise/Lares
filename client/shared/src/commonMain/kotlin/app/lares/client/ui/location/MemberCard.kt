package app.lares.client.ui.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lares.client.resources.Res
import app.lares.client.resources.action_back
import app.lares.client.resources.alerts_off
import app.lares.client.resources.alerts_title
import app.lares.client.resources.ic_arrow_back
import app.lares.client.resources.ic_battery_1_bar
import app.lares.client.resources.ic_battery_3_bar
import app.lares.client.resources.ic_battery_5_bar
import app.lares.client.resources.ic_battery_6_bar
import app.lares.client.resources.ic_battery_charging_full
import app.lares.client.resources.ic_battery_full
import app.lares.client.resources.ic_history
import app.lares.client.resources.ic_notifications
import app.lares.client.resources.ic_refresh
import app.lares.client.resources.loc_status_kilometers
import app.lares.client.resources.loc_status_meters
import app.lares.client.resources.map_accuracy
import app.lares.client.resources.map_battery
import app.lares.client.resources.map_history
import app.lares.client.resources.map_refresh
import app.lares.client.resources.map_refresh_done
import app.lares.client.resources.map_refresh_no_answer
import app.lares.client.resources.map_refresh_paused
import app.lares.client.resources.map_refresh_waiting
import app.lares.client.ui.common.message
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * The member card (plan M4-08, LOC-011): the sheet's content after a pin or row tap — name, status line, accuracy,
 * battery, «Обновить», «История» (M4-09) and «Сообщать: прибытие и уход» (M4-10).
 */
@Composable
fun MemberCardContent(
    card: MemberCard,
    initials: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onHistory: () -> Unit = {},
    onAlerts: () -> Unit = {},
) {
    val member = card.row.member
    Column(modifier.fillMaxWidth().testTag("member_card"), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("card_back")) {
                Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.action_back))
            }
            Avatar(initials, member.color, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(member.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(card.row.status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            Modifier.padding(start = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            card.accuracyM?.let {
                Text(
                    stringResource(Res.string.map_accuracy, distanceText(it)),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            card.row.battery?.let { BatteryLabel(it) }
        }
        if (card.canRefresh) {
            val refresh = card.refresh
            TonalButton(
                stringResource(Res.string.map_refresh),
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth().testTag("refresh"),
                loading = refresh?.phase == RefreshPhase.WAITING,
                icon = Res.drawable.ic_refresh,
            )
            refresh?.let { RefreshText(it) }
        }
        if (card.canHistory) {
            TonalButton(
                stringResource(Res.string.map_history),
                onClick = onHistory,
                modifier = Modifier.fillMaxWidth().testTag("history"),
                icon = Res.drawable.ic_history,
            )
        }
        if (card.canAlerts) {
            ListGroup {
                AlertsRow(card.alertPlaces, onAlerts)
            }
        }
        Spacer(Modifier.size(Spacing.s))
    }
}

/** «Сообщать: прибытие и уход» with the chosen places («Школа №12, Дом») or «Выключено»; opens the alerts sheet. */
@Composable
fun AlertsRow(
    names: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(Res.string.alerts_title),
) {
    ListRow(
        title,
        modifier.testTag("alerts"),
        icon = Res.drawable.ic_notifications,
        supporting = if (names.isEmpty()) stringResource(Res.string.alerts_off) else names.joinToString(", "),
        showChevron = true,
        onClick = onClick,
    )
}

@Composable
private fun RefreshText(refresh: RefreshUi) {
    val colors = LaresTheme.colors
    val (text, color) =
        when (refresh.phase) {
            RefreshPhase.WAITING -> stringResource(Res.string.map_refresh_waiting) to MaterialTheme.colorScheme.onSurfaceVariant
            RefreshPhase.DONE -> stringResource(Res.string.map_refresh_done) to colors.success
            RefreshPhase.NO_ANSWER -> stringResource(Res.string.map_refresh_no_answer) to colors.warning
            RefreshPhase.PAUSED -> stringResource(Res.string.map_refresh_paused) to colors.warning
            RefreshPhase.FAILED -> (refresh.error?.message() ?: "") to MaterialTheme.colorScheme.error
        }
    Text(text, Modifier.testTag("refresh_status"), fontSize = 13.sp, color = color)
}

/** «64%» with the battery icon; red at 15 % and below (not while charging). */
@Composable
fun BatteryLabel(
    battery: BatteryInfo,
    modifier: Modifier = Modifier,
) {
    val color = if (battery.low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(painterResource(battery.icon.drawable()), null, Modifier.size(18.dp), tint = color)
        Text(stringResource(Res.string.map_battery, battery.percent.toString()), fontSize = 13.sp, color = color)
    }
}

@Composable
private fun distanceText(meters: Int): String =
    if (meters < 1000) {
        stringResource(Res.string.loc_status_meters, meters.toString())
    } else {
        stringResource(Res.string.loc_status_kilometers, (meters / 1000).toString())
    }

private fun BatteryIcon.drawable(): DrawableResource =
    when (this) {
        BatteryIcon.CHARGING -> Res.drawable.ic_battery_charging_full
        BatteryIcon.BAR_1 -> Res.drawable.ic_battery_1_bar
        BatteryIcon.BAR_3 -> Res.drawable.ic_battery_3_bar
        BatteryIcon.BAR_5 -> Res.drawable.ic_battery_5_bar
        BatteryIcon.BAR_6 -> Res.drawable.ic_battery_6_bar
        BatteryIcon.FULL -> Res.drawable.ic_battery_full
    }
