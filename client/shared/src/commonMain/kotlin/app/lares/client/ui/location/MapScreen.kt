package app.lares.client.ui.location

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.lares.client.resources.Res
import app.lares.client.resources.action_back
import app.lares.client.resources.ic_arrow_back
import app.lares.client.resources.ic_home
import app.lares.client.resources.ic_my_location
import app.lares.client.resources.ic_pause
import app.lares.client.resources.ic_play_arrow
import app.lares.client.resources.ic_push_pin
import app.lares.client.resources.loc_action_allow_access
import app.lares.client.resources.loc_action_pause
import app.lares.client.resources.loc_action_resume
import app.lares.client.resources.loc_action_turn_on_location
import app.lares.client.resources.loc_me_needs_permission
import app.lares.client.resources.loc_me_paused
import app.lares.client.resources.loc_me_paused_until
import app.lares.client.resources.loc_me_stale
import app.lares.client.resources.loc_status_off_location_disabled
import app.lares.client.resources.map_content_description
import app.lares.client.resources.map_enable
import app.lares.client.resources.map_my_location
import app.lares.client.resources.map_no_places
import app.lares.client.resources.map_no_style
import app.lares.client.resources.map_nobody
import app.lares.client.resources.map_offline
import app.lares.client.resources.map_places
import app.lares.client.resources.map_sharing
import app.lares.client.resources.map_show_all
import app.lares.client.resources.section_map
import app.lares.client.ui.common.rememberTimeFormatter
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.NoticeBanner
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.contract.entities.PlaceIcon
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun MapRoute(
    args: MapArgs,
    onBack: () -> Unit,
    onHistory: (String) -> Unit = {},
    onPlaceEditor: (PlaceEditorArgs) -> Unit = {},
    viewModel: MapViewModel = koinViewModel { parametersOf(args) },
    sharing: LocationSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sharingState by sharing.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        sharing.onResume()
        onPauseOrDispose { }
    }
    MapScreen(
        state = state,
        sharing = sharingState,
        actions =
            MapActions(
                onBack = onBack,
                onSelect = viewModel::select,
                onCloseCard = viewModel::closeCard,
                onRefresh = viewModel::refresh,
                onLocate = viewModel::locate,
                onPlaces = viewModel::openPlaces,
                onClosePlaces = viewModel::closePlaces,
                onPlace = viewModel::openPlace,
                onSetup = sharing::openSetup,
                onTurnOnLocation = sharing::openLocationSettings,
                onPause = sharing::openPause,
                onResume = sharing::resume,
                onClosePlace = viewModel::closePlace,
                onEditPlace = { id ->
                    viewModel.closePlace()
                    onPlaceEditor(PlaceEditorArgs(id = id))
                },
                onDeletePlace = viewModel::deletePlace,
                onAddPlace = {
                    viewModel.closePlaces()
                    val center = viewModel.newPlaceCenter()
                    onPlaceEditor(PlaceEditorArgs(lat = center?.first, lon = center?.second))
                },
                onLongPress = { lat, lon -> onPlaceEditor(PlaceEditorArgs(lat = lat, lon = lon)) },
                onHistory = onHistory,
                onCenter = viewModel::onCenter,
                onAlerts = viewModel::openAlerts,
            ),
    )
    state.alerts?.let { AlertsSheet(it, onSave = viewModel::saveAlerts, onDismiss = viewModel::closeAlerts) }
    if (sharingState.setupOpen) {
        SharingSetupSheet(
            device = sharing.device,
            watchers = sharingState.watchers,
            enabling = sharingState.enabling,
            error = sharingState.enableError,
            onEnable = sharing::enable,
            onDismiss = sharing::closeSetup,
        )
    }
    if (sharingState.pauseOpen) PauseSheet(onPause = sharing::pause, onDismiss = sharing::closePause)
}

class MapActions(
    val onBack: () -> Unit,
    val onSelect: (String) -> Unit,
    val onCloseCard: () -> Unit,
    val onRefresh: (String) -> Unit,
    val onLocate: () -> Unit,
    val onPlaces: () -> Unit,
    val onClosePlaces: () -> Unit,
    val onPlace: (String) -> Unit,
    val onSetup: () -> Unit,
    val onTurnOnLocation: () -> Unit,
    val onPause: () -> Unit,
    val onResume: () -> Unit,
    val onClosePlace: () -> Unit = {},
    val onEditPlace: (String) -> Unit = {},
    val onDeletePlace: (String) -> Unit = {},
    val onAddPlace: () -> Unit = {},
    /** A long press on the map (managers only): a new place there. */
    val onLongPress: (Double, Double) -> Unit = { _, _ -> },
    val onHistory: (String) -> Unit = {},
    val onCenter: (Double, Double) -> Unit = { _, _ -> },
    val onAlerts: (AlertsTarget) -> Unit = {},
)

private val SHEET_HANDLE = 20.dp

/**
 * The Map (plan M4-08, LOC-003, LOC-009): the map full screen, the title pill and «Места» on top, the `my_location`
 * button and the family sheet — this phone's sharing row, then the others; a tap shows the member card. Stateless for
 * UI tests (the JVM map is a stand-in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    state: MapUiState,
    sharing: LocationSettingsUiState,
    actions: MapActions,
) {
    val density = LocalDensity.current
    // Sharing row + up to three members, measured; the card is measured whole.
    var peekContent by remember { mutableStateOf(140.dp) }
    val navBar = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val peek = SHEET_HANDLE + peekContent + navBar
    val card = state.card
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = card != null,
        onBackCompleted = actions.onCloseCard,
    )
    BottomSheetScaffold(
        sheetPeekHeight = peek,
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetShadowElevation = 6.dp,
        sheetDragHandle = { SheetHandle() },
        sheetContent = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screen)
                    .navigationBarsPadding()
                    .testTag("family_sheet"),
            ) {
                if (card != null) {
                    MemberCardContent(
                        card = card,
                        initials = initialsFor(card.row.member.name, state.members.map { it.name }),
                        onBack = actions.onCloseCard,
                        onRefresh = { actions.onRefresh(card.row.member.memberId) },
                        onHistory = { actions.onHistory(card.row.member.memberId) },
                        onAlerts = { actions.onAlerts(AlertsTarget.Member(card.row.member.memberId)) },
                        modifier = Modifier.onGloballyPositioned { peekContent = with(density) { it.size.height.toDp() } },
                    )
                } else {
                    FamilyList(state, sharing, actions) { peekContent = it }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            MapLayer(state, actions, bottom = peek)
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.screen, vertical = Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                TopRow(actions)
                if (state.offline) NoticeBanner(stringResource(Res.string.map_offline), Modifier.testTag("offline_banner"))
            }
            val locateLabel =
                stringResource(if (state.nextLocate is CameraKind.Member) Res.string.map_my_location else Res.string.map_show_all)
            FloatingIcon(
                Res.drawable.ic_my_location,
                locateLabel,
                actions.onLocate,
                RoundedCornerShape(16.dp),
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.screen, bottom = peek + Spacing.l)
                    .testTag("locate"),
            )
        }
    }
    if (state.placesOpen) {
        PlacesSheet(state.places, state.members, state.canManagePlaces, actions.onPlace, actions.onAddPlace, actions.onClosePlaces)
    }
    state.place?.let { place ->
        PlaceSheet(
            place,
            state.members,
            state.canManagePlaces,
            onEdit = { actions.onEditPlace(place.id) },
            onDelete = { actions.onDeletePlace(place.id) },
            onDismiss = actions.onClosePlace,
            alertNames = state.placeAlertNames,
            onAlerts = { actions.onAlerts(AlertsTarget.Place(place.id)) },
        )
    }
}

@Composable
private fun MapLayer(
    state: MapUiState,
    actions: MapActions,
    bottom: Dp,
) {
    val dark = LaresTheme.colors.isDark
    val styleUrl = state.config?.let { if (dark) it.mapDarkStyleUrl else it.mapStyleUrl }
    val description = stringResource(Res.string.map_content_description)
    if (styleUrl == null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(bottom = bottom)
                .testTag("map_no_style"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(Res.string.map_no_style),
                Modifier.padding(Spacing.xl),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    val view =
        remember(state.members, state.places, styleUrl, dark) {
            MapViewState(styleUrl, MapLogic.pins(state.members, dark), MapLogic.places(state.places), labelFont = state.config?.labelFont)
        }
    val top = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() } + 80.dp
    if (styleUrl != null || view.pins.isNotEmpty()) {
        FamilyMapView(
            state = view,
            camera = state.camera,
            onMemberClick = actions.onSelect,
            onPlaceClick = actions.onPlace,
            onMapClick = { _, _ -> },
            onMapLongClick = { lat, lon -> if (state.canManagePlaces) actions.onLongPress(lat, lon) },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            contentPadding = PaddingValues(top = top, bottom = bottom),
            onCenterChange = actions.onCenter,
        )
    }
}

@Composable
private fun TopRow(actions: MapActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Surface(
            onClick = actions.onBack,
            modifier =
                Modifier
                    .weight(1f)
                    .height(48.dp)
                    .shadow(4.dp, CircleShape)
                    .testTag("map_title"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Row(Modifier.padding(horizontal = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(Res.drawable.ic_arrow_back), stringResource(Res.string.action_back))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(Res.string.section_map), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        FloatingIcon(
            Res.drawable.ic_push_pin,
            stringResource(Res.string.map_places),
            actions.onPlaces,
            CircleShape,
            Modifier.testTag("places"),
        )
    }
}

@Composable
private fun FloatingIcon(
    icon: DrawableResource,
    description: String,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp).shadow(4.dp, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), description) }
    }
}

@Composable
private fun SheetHandle() {
    Box(Modifier.fillMaxWidth().height(SHEET_HANDLE), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(36.dp, 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
        )
    }
}

/** The sharing row and the member rows; reports the height of the sharing row plus the first three rows. */
@Composable
private fun FamilyList(
    state: MapUiState,
    sharing: LocationSettingsUiState,
    actions: MapActions,
    onPeek: (Dp) -> Unit,
) {
    val density = LocalDensity.current
    val peekIndex = minOf(state.rows.size, 3) - 1
    val names = state.members.map { it.name }
    SharingRow(
        sharing,
        actions,
        Modifier.onGloballyPositioned {
            if (peekIndex < 0) onPeek(with(density) { (it.positionInParent().y + it.size.height).toDp() + Spacing.l })
        },
    )
    if (state.loaded && state.rows.isEmpty()) {
        Text(
            stringResource(Res.string.map_nobody),
            Modifier.padding(vertical = Spacing.m, horizontal = Spacing.xs),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
    state.rows.forEachIndexed { index, row ->
        if (index > 0) ListDivider(inset = 56.dp)
        MemberRowView(
            row,
            initialsFor(row.member.name, names),
            onClick = { actions.onSelect(row.member.memberId) },
            modifier =
                Modifier.onGloballyPositioned {
                    if (index == peekIndex) onPeek(with(density) { (it.positionInParent().y + it.size.height).toDp() + Spacing.s })
                },
        )
    }
    Spacer(Modifier.height(Spacing.l))
}

/** This phone's sharing (principle 5: always visible on the map). */
@Composable
private fun SharingRow(
    sharing: LocationSettingsUiState,
    actions: MapActions,
    modifier: Modifier = Modifier,
) {
    val colors = LaresTheme.colors
    val formatTime = rememberTimeFormatter()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = Spacing.xs, top = Spacing.xs, bottom = Spacing.s)
            .testTag("sharing_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (!sharing.loaded) return@Row
        when (sharing.status) {
            SharingStatusText.SHARING, SharingStatusText.STALE -> {
                val stale = sharing.status == SharingStatusText.STALE
                Box(Modifier.size(8.dp).background(if (stale) colors.warning else colors.success, CircleShape))
                Text(
                    stringResource(if (stale) Res.string.loc_me_stale else Res.string.map_sharing),
                    Modifier.weight(1f),
                    color = if (stale) colors.warning else MaterialTheme.colorScheme.secondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (sharing.showPause) SmallOutlined(stringResource(Res.string.loc_action_pause), Res.drawable.ic_pause, actions.onPause)
            }

            SharingStatusText.PAUSED, SharingStatusText.PAUSED_UNTIL -> {
                val text =
                    sharing.pausedUntil?.let { stringResource(Res.string.loc_me_paused_until, formatTime(it)) }
                        ?: stringResource(Res.string.loc_me_paused)
                Text(
                    text,
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (sharing.showResume) {
                    SmallOutlined(stringResource(Res.string.loc_action_resume), Res.drawable.ic_play_arrow, actions.onResume)
                }
            }

            SharingStatusText.OFF -> {
                TonalButton(stringResource(Res.string.map_enable), actions.onSetup, Modifier.fillMaxWidth().testTag("enable_sharing"))
            }

            SharingStatusText.NEEDS_PERMISSION -> {
                WarningText(stringResource(Res.string.loc_me_needs_permission), Modifier.weight(1f))
                SmallOutlined(stringResource(Res.string.loc_action_allow_access), null, actions.onSetup)
            }

            SharingStatusText.LOCATION_OFF -> {
                WarningText(stringResource(Res.string.loc_status_off_location_disabled), Modifier.weight(1f))
                SmallOutlined(stringResource(Res.string.loc_action_turn_on_location), null, actions.onTurnOnLocation)
            }
        }
    }
}

@Composable
private fun WarningText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, modifier, color = LaresTheme.colors.warning, fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SmallOutlined(
    text: String,
    icon: DrawableResource?,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = Spacing.m),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MemberRowView(
    row: MemberRow,
    initials: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xs, vertical = 10.dp)
            .testTag("member_${row.member.memberId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Avatar(initials, row.member.color, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.member.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.status,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        row.battery?.let { BatteryLabel(it) }
    }
}
