package app.lares.client.ui.location

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.lares.client.resources.Res
import app.lares.client.resources.action_save
import app.lares.client.resources.ic_delete
import app.lares.client.resources.ic_my_location
import app.lares.client.resources.loc_status_meters
import app.lares.client.resources.map_content_description
import app.lares.client.resources.map_no_style
import app.lares.client.resources.place_address
import app.lares.client.resources.place_delete
import app.lares.client.resources.place_discard_text
import app.lares.client.resources.place_editor_edit
import app.lares.client.resources.place_editor_new
import app.lares.client.resources.place_editor_read_only
import app.lares.client.resources.place_field_icon
import app.lares.client.resources.place_field_name
import app.lares.client.resources.place_here
import app.lares.client.resources.place_map_hint
import app.lares.client.resources.place_radius
import app.lares.client.resources.place_search
import app.lares.client.resources.place_search_none
import app.lares.client.resources.task_discard_confirm
import app.lares.client.resources.task_discard_keep
import app.lares.client.resources.task_discard_title
import app.lares.client.ui.common.message
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.family.ReadOnlyHint
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.contract.api.location.AddressResult
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.location.LocationLimits
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the editor can do; the defaults do nothing (screen tests). */
class PlaceEditorActions(
    val onBack: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onIcon: (PlaceIcon) -> Unit = {},
    val onMapTap: (Double, Double) -> Unit = { _, _ -> },
    val onHere: () -> Unit = {},
    val onRadius: (Float) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onPick: (AddressResult) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

@Composable
fun PlaceEditorRoute(
    args: PlaceEditorArgs,
    onBack: () -> Unit,
    viewModel: PlaceEditorViewModel = koinViewModel(key = "place-editor-$args") { parametersOf(args) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.result) { if (state.result != null) onBack() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.changed && state.canManage && state.result == null,
        onBackCompleted = { confirmDiscard = true },
    )
    PlaceEditorScreen(
        state,
        PlaceEditorActions(
            onBack = { if (state.changed && state.canManage) confirmDiscard = true else onBack() },
            onName = viewModel::onName,
            onIcon = viewModel::onIcon,
            onMapTap = viewModel::onMapTap,
            onHere = viewModel::useHere,
            onRadius = viewModel::onRadius,
            onQuery = viewModel::onQuery,
            onSearch = viewModel::search,
            onPick = viewModel::pick,
            onSave = viewModel::save,
            onDelete = viewModel::delete,
        ),
    )
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.task_discard_title),
            text = stringResource(Res.string.place_discard_text),
            confirmLabel = stringResource(Res.string.task_discard_confirm),
            dismissLabel = stringResource(Res.string.task_discard_keep),
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
            destructive = true,
        )
    }
}

/**
 * The place editor (plan M4-09, LOC-020, LOC-024): the map with the draft circle stays on top (a tap moves the
 * centre); under it the name, icon chips, «Я здесь», the radius slider and the address search.
 */
@Composable
fun PlaceEditorScreen(
    state: PlaceEditorUiState,
    actions: PlaceEditorActions,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val form = state.form
    AppPage(
        topBar = {
            DetailTopBar(stringResource(if (state.isNew) Res.string.place_editor_new else Res.string.place_editor_edit), actions.onBack) {
                if (!state.isNew && state.canManage) {
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("place_editor_delete")) {
                        AppIcon(Res.drawable.ic_delete, stringResource(Res.string.place_delete))
                    }
                }
            }
            if (state.loaded && state.canManage) EditorMap(state, actions, Modifier.fillMaxWidth().height(240.dp))
        },
    ) {
        if (!state.loaded) return@AppPage
        if (!state.canManage) {
            ReadOnlyHint(stringResource(Res.string.place_editor_read_only))
            return@AppPage
        }
        Text(
            stringResource(Res.string.place_map_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val nameFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { if (state.isNew) nameFocus.requestFocus() }
        FormField(
            form.name,
            actions.onName,
            stringResource(Res.string.place_field_name),
            modifier = Modifier.testTag("place_name"),
            error = state.fieldErrors["name"],
            capitalization = KeyboardCapitalization.Sentences,
            focusRequester = nameFocus,
        )
        IconChips(form.icon, actions.onIcon)
        if (state.here != null) {
            TonalButton(
                stringResource(Res.string.place_here),
                actions.onHere,
                Modifier.testTag("place_here_button"),
                icon = Res.drawable.ic_my_location,
            )
        }
        Radius(form.radiusM, actions.onRadius)
        if (state.searchAvailable) AddressSearch(state, actions)
        ErrorText(state.error)
        PrimaryButton(
            stringResource(Res.string.action_save),
            actions.onSave,
            enabled = state.canSave || state.saving,
            loading = state.saving,
            modifier = Modifier.testTag("place_save"),
        )
    }
    if (confirmDelete) {
        DeletePlaceDialog(form.original?.name ?: form.name, onConfirm = {
            confirmDelete = false
            actions.onDelete()
        }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun EditorMap(
    state: PlaceEditorUiState,
    actions: PlaceEditorActions,
    modifier: Modifier,
) {
    val dark = LaresTheme.colors.isDark
    val styleUrl = state.config?.let { if (dark) it.mapDarkStyleUrl else it.mapStyleUrl }
    val form = state.form
    val view =
        remember(state.others, form.latitude, form.longitude, form.radiusM, form.name, form.icon, styleUrl) {
            MapViewState(
                styleUrl = styleUrl,
                places = MapLogic.places(state.others),
                draftPlace =
                    MapPlace("draft", form.latitude, form.longitude, form.radiusM.toDouble(), form.name, form.icon == PlaceIcon.HOME),
                labelFont = state.config?.labelFont,
            )
        }
    val description = stringResource(Res.string.map_content_description)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).testTag("place_map")) {
        if (styleUrl == null) {
            Text(
                stringResource(Res.string.map_no_style),
                Modifier.align(Alignment.Center).padding(Spacing.xl),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        FamilyMapView(
            state = view,
            camera = state.camera,
            onMemberClick = { },
            onPlaceClick = { },
            onMapClick = actions.onMapTap,
            onMapLongClick = actions.onMapTap,
            modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            contentPadding = PaddingValues(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconChips(
    selected: PlaceIcon,
    onIcon: (PlaceIcon) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.place_field_icon), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            PlaceForm.ICONS.forEach { icon ->
                AppFilterChip(
                    selected = icon == selected,
                    label = stringResource(icon.label()),
                    onClick = { onIcon(icon) },
                    leadingIcon = icon.drawable(),
                    modifier = Modifier.testTag("icon_${icon.key}"),
                )
            }
        }
    }
}

@Composable
private fun Radius(
    radiusM: Int,
    onRadius: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.place_radius), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(Res.string.loc_status_meters, radiusM.toString()),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("place_radius_value"),
            )
        }
        val min = LocationLimits.MIN_RADIUS_M.toFloat()
        val max = LocationLimits.MAX_RADIUS_M.toFloat()
        // Continuous; the form rounds to steps of 10 m (95 tick marks were too dense to see).
        Slider(
            value = radiusM.toFloat(),
            onValueChange = onRadius,
            valueRange = min..max,
            modifier = Modifier.testTag("place_radius"),
        )
    }
}

@Composable
private fun AddressSearch(
    state: PlaceEditorUiState,
    actions: PlaceEditorActions,
) {
    val search = state.search
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FormField(
            search.query,
            actions.onQuery,
            stringResource(Res.string.place_address),
            modifier = Modifier.testTag("place_search_field"),
            hint = state.form.address,
            imeAction = ImeAction.Search,
            onDone = actions.onSearch,
            trailingIcon = {
                LinkButton(
                    stringResource(Res.string.place_search),
                    actions.onSearch,
                    Modifier.testTag("place_search"),
                    enabled = !search.searching,
                )
            },
        )
        if (search.searching) {
            Text(
                stringResource(Res.string.place_search) + "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        search.error?.let { Text(it.message(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        val results = search.results
        when {
            results == null -> {
                Unit
            }

            results.isEmpty() -> {
                Text(
                    stringResource(Res.string.place_search_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("place_search_none"),
                )
            }

            else -> {
                ListGroup(Modifier.testTag("place_results")) {
                    results.forEachIndexed { index, result ->
                        if (index > 0) ListDivider(inset = 16.dp)
                        ListRow(result.label, Modifier.testTag("place_result_$index"), onClick = { actions.onPick(result) })
                    }
                }
            }
        }
    }
}
