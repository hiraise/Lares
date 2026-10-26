package app.lares.client.ui.location

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.resources.Res
import app.lares.client.resources.ic_home
import app.lares.client.resources.ic_location_on
import app.lares.client.resources.loc_history_denied
import app.lares.client.resources.loc_history_empty
import app.lares.client.resources.loc_history_offline
import app.lares.client.resources.loc_history_title
import app.lares.client.resources.map_content_description
import app.lares.client.resources.map_no_style
import app.lares.client.ui.common.UiNotices
import app.lares.client.ui.common.message
import app.lares.client.ui.common.text
import app.lares.client.ui.common.uiText
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tones
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun HistoryRoute(
    args: HistoryArgs,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = koinViewModel(key = "history-${args.memberId}") { parametersOf(args) },
    notices: UiNotices = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.denied) {
        if (state.denied) {
            notices.post(uiText(Res.string.loc_history_denied))
            onBack()
        }
    }
    HistoryScreen(state, onBack, viewModel::select, viewModel::showStop)
}

/**
 * A member's day (plan M4-09, LOC-004): «Аня · История», the day strip, the map with the track and the stops, the stop
 * list under it. Stateless for UI tests (the JVM map is a stand-in).
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onDay: (LocalDate) -> Unit,
    onStop: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        DetailTopBar(stringResource(Res.string.loc_history_title, state.name), onBack)
        DayStrip(state, onDay)
        HistoryMap(state, Modifier.fillMaxWidth().height(320.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.screen),
        ) {
            when (state.phase) {
                HistoryPhase.LOADING -> {
                    Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.testTag("history_loading"))
                    }
                }

                HistoryPhase.EMPTY -> {
                    Note(stringResource(Res.string.loc_history_empty), "history_empty")
                }

                HistoryPhase.OFFLINE -> {
                    Note(stringResource(Res.string.loc_history_offline), "history_offline")
                }

                HistoryPhase.FAILED -> {
                    Note(state.error?.message().orEmpty(), "history_failed")
                }

                HistoryPhase.READY -> {
                    if (state.rows.isNotEmpty()) StopList(state.rows, onStop)
                }
            }
        }
    }
}

@Composable
private fun DayStrip(
    state: HistoryUiState,
    onDay: (LocalDate) -> Unit,
) {
    val today = state.today ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screen, vertical = Spacing.s)
            .testTag("day_strip"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        state.days.forEach { day ->
            AppFilterChip(
                selected = day == state.selected,
                label = HistoryLogic.dayLabel(day, today).text(),
                onClick = { onDay(day) },
                modifier = Modifier.testTag("day_$day"),
            )
        }
    }
}

@Composable
private fun HistoryMap(
    state: HistoryUiState,
    modifier: Modifier,
) {
    val dark = LaresTheme.colors.isDark
    val styleUrl = state.config?.let { if (dark) it.mapDarkStyleUrl else it.mapStyleUrl }
    val trackColor = state.color?.tones(dark)?.base
    val view =
        remember(state.track, state.rows, state.places, styleUrl, trackColor) {
            MapViewState(
                styleUrl = styleUrl,
                places = MapLogic.places(state.places),
                track = state.track,
                trackColor = trackColor,
                stops = HistoryLogic.mapStops(state.rows),
                labelFont = state.config?.labelFont,
            )
        }
    val description = stringResource(Res.string.map_content_description)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant).testTag("history_map")) {
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
            onMapClick = { _, _ -> },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            contentPadding = PaddingValues(),
        )
    }
}

@Composable
private fun StopList(
    rows: List<StopRow>,
    onStop: (String) -> Unit,
) {
    ListGroup(Modifier.testTag("stop_list")) {
        rows.forEachIndexed { index, row ->
            if (index > 0) ListDivider()
            ListRow(
                row.title.text(),
                modifier = Modifier.testTag(row.id),
                icon = if (row.home) Res.drawable.ic_home else Res.drawable.ic_location_on,
                trailingText = row.time.text(),
                onClick = { onStop(row.id) },
            )
        }
    }
}

@Composable
private fun Note(
    text: String,
    tag: String,
) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(vertical = Spacing.l).testTag(tag),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}
