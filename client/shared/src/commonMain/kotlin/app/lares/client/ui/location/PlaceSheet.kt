package app.lares.client.ui.location

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lares.client.data.location.MemberOnMap
import app.lares.client.data.location.Place
import app.lares.client.resources.Res
import app.lares.client.resources.action_delete
import app.lares.client.resources.alerts_me
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_delete
import app.lares.client.resources.ic_edit
import app.lares.client.resources.ic_fitness_center
import app.lares.client.resources.ic_home
import app.lares.client.resources.ic_location_on
import app.lares.client.resources.ic_school
import app.lares.client.resources.ic_shopping_cart
import app.lares.client.resources.ic_work
import app.lares.client.resources.map_no_places
import app.lares.client.resources.map_places
import app.lares.client.resources.place_delete
import app.lares.client.resources.place_delete_text
import app.lares.client.resources.place_delete_title
import app.lares.client.resources.place_edit
import app.lares.client.resources.place_icon_home
import app.lares.client.resources.place_icon_place
import app.lares.client.resources.place_icon_school
import app.lares.client.resources.place_icon_shop
import app.lares.client.resources.place_icon_sport
import app.lares.client.resources.place_icon_work
import app.lares.client.resources.places_add
import app.lares.client.resources.places_here
import app.lares.client.resources.places_nobody
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.theme.Spacing
import app.lares.contract.entities.PlaceIcon
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One place (plan M4-09): name, address, who is there now; «Изменить» and «Удалить» for members with
 * `location.manage_places`; «Сообщать мне» opens the alerts sheet with the members the viewer sees (M4-10).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceSheet(
    place: Place,
    members: List<MemberOnMap>,
    canManage: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    alertNames: List<String> = emptyList(),
    onAlerts: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding()
                .testTag("place_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                PlaceIconCircle(place.data.icon, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(place.data.name, style = MaterialTheme.typography.headlineSmall)
                    place.data.address?.let {
                        Text(
                            it,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("place_address"),
                        )
                    }
                }
            }
            Text(
                hereText(MapLogic.whoIsHere(place.id, members)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("place_here"),
            )
            ListGroup {
                AlertsRow(alertNames, onAlerts, title = stringResource(Res.string.alerts_me))
            }
            if (canManage) {
                ListGroup {
                    ListRow(
                        stringResource(Res.string.place_edit),
                        Modifier.testTag("place_edit"),
                        icon = Res.drawable.ic_edit,
                        onClick = onEdit,
                    )
                    ListDivider()
                    ListRow(
                        stringResource(Res.string.place_delete),
                        Modifier.testTag("place_delete"),
                        icon = Res.drawable.ic_delete,
                        titleColor = MaterialTheme.colorScheme.error,
                        iconTint = MaterialTheme.colorScheme.error,
                        onClick = { confirmDelete = true },
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        DeletePlaceDialog(place.data.name, onConfirm = {
            confirmDelete = false
            onDelete()
        }, onDismiss = { confirmDelete = false })
    }
}

/** «Удалить место «Школа №12»? Подписки на него перестанут работать.» */
@Composable
fun DeletePlaceDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(Res.string.place_delete_title, name),
        text = stringResource(Res.string.place_delete_text),
        confirmLabel = stringResource(Res.string.action_delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
    )
}
