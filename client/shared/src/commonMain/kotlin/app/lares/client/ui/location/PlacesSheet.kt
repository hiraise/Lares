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
import androidx.compose.material3.rememberModalBottomSheetState
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

fun PlaceIcon.drawable(): DrawableResource =
    when (this) {
        PlaceIcon.HOME -> Res.drawable.ic_home
        PlaceIcon.SCHOOL -> Res.drawable.ic_school
        PlaceIcon.WORK -> Res.drawable.ic_work
        PlaceIcon.SPORT -> Res.drawable.ic_fitness_center
        PlaceIcon.SHOP -> Res.drawable.ic_shopping_cart
        PlaceIcon.PLACE -> Res.drawable.ic_location_on
    }

fun PlaceIcon.label(): StringResource =
    when (this) {
        PlaceIcon.HOME -> Res.string.place_icon_home
        PlaceIcon.SCHOOL -> Res.string.place_icon_school
        PlaceIcon.WORK -> Res.string.place_icon_work
        PlaceIcon.SPORT -> Res.string.place_icon_sport
        PlaceIcon.SHOP -> Res.string.place_icon_shop
        PlaceIcon.PLACE -> Res.string.place_icon_place
    }

/** «Тимур, Аня здесь» or «Никого». */
@Composable
fun hereText(names: List<String>): String =
    if (names.isEmpty()) {
        stringResource(Res.string.places_nobody)
    } else {
        pluralStringResource(Res.plurals.places_here, names.size, names.joinToString(", "))
    }

/** The place's icon in a `secondaryContainer` circle. */
@Composable
fun PlaceIconCircle(
    icon: PlaceIcon,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Box(
        Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) { AppIcon(icon.drawable(), null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
}

/**
 * «Места» (plan M4-09, LOC-020): every place with who is there; members with `location.manage_places` also see
 * «Добавить место».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesSheet(
    places: List<Place>,
    members: List<MemberOnMap>,
    canManage: Boolean,
    onPlace: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Opened fully: half height hid «Добавить место» under the navigation bar with four or more places.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding()
                .testTag("places_sheet"),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(Res.string.map_places), style = MaterialTheme.typography.headlineSmall)
            if (places.isEmpty()) {
                Text(stringResource(Res.string.map_no_places), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                ListGroup {
                    places.forEachIndexed { index, place ->
                        if (index > 0) ListDivider(inset = 70.dp)
                        PlaceRow(place, MapLogic.whoIsHere(place.id, members), onClick = { onPlace(place.id) })
                    }
                }
            }
            if (canManage) {
                TonalButton(
                    stringResource(Res.string.places_add),
                    onAdd,
                    Modifier.testTag("add_place"),
                    icon = Res.drawable.ic_add,
                )
            }
        }
    }
}

@Composable
private fun PlaceRow(
    place: Place,
    here: List<String>,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.l, vertical = 12.dp)
            .testTag("place_row_${place.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PlaceIconCircle(place.data.icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(place.data.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(hereText(here), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
