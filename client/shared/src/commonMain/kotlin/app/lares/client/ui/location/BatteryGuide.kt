package app.lares.client.ui.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lares.client.resources.Res
import app.lares.client.resources.loc_battery_hint_huawei
import app.lares.client.resources.loc_battery_hint_oneplus
import app.lares.client.resources.loc_battery_hint_other
import app.lares.client.resources.loc_battery_hint_samsung
import app.lares.client.resources.loc_battery_hint_title
import app.lares.client.resources.loc_battery_hint_xiaomi
import app.lares.client.resources.loc_battery_open_settings
import app.lares.client.resources.loc_setup_battery_action
import app.lares.client.resources.loc_setup_battery_text
import app.lares.client.resources.loc_setup_battery_title
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Phone makers whose own battery managers stop background apps (LOC-008); text hints only, no links. */
enum class BatteryVendor { XIAOMI, HUAWEI, SAMSUNG, ONEPLUS, OTHER }

/** `Build.MANUFACTURER` → vendor (lowercase contains). */
fun batteryVendor(manufacturer: String): BatteryVendor {
    val name = manufacturer.lowercase()
    return when {
        listOf("xiaomi", "redmi", "poco").any { it in name } -> BatteryVendor.XIAOMI
        listOf("huawei", "honor").any { it in name } -> BatteryVendor.HUAWEI
        "samsung" in name -> BatteryVendor.SAMSUNG
        listOf("oneplus", "oppo", "realme").any { it in name } -> BatteryVendor.ONEPLUS
        else -> BatteryVendor.OTHER
    }
}

val BatteryVendor.hint: StringResource
    get() =
        when (this) {
            BatteryVendor.XIAOMI -> Res.string.loc_battery_hint_xiaomi
            BatteryVendor.HUAWEI -> Res.string.loc_battery_hint_huawei
            BatteryVendor.SAMSUNG -> Res.string.loc_battery_hint_samsung
            BatteryVendor.ONEPLUS -> Res.string.loc_battery_hint_oneplus
            BatteryVendor.OTHER -> Res.string.loc_battery_hint_other
        }

/** «Ещё на этом телефоне» and the vendor's hint. */
@Composable
fun BatteryHint(manufacturer: String) {
    Column(Modifier.testTag("battery_hint"), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(stringResource(Res.string.loc_battery_hint_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(batteryVendor(manufacturer).hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The battery guide from Settings › Геопозиция › «Экономия заряда». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryGuideSheet(
    unrestricted: Boolean,
    manufacturer: String,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(Res.string.loc_setup_battery_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(Res.string.loc_setup_battery_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BatteryHint(manufacturer)
            if (!unrestricted) PrimaryButton(stringResource(Res.string.loc_setup_battery_action), onRequest)
            TonalButton(stringResource(Res.string.loc_battery_open_settings), onOpenSettings)
        }
    }
}
