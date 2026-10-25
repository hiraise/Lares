package app.lares.client.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.resources.Res
import app.lares.client.resources.cal_chip_no_tasks
import app.lares.client.resources.cal_chip_remove
import app.lares.client.resources.cal_chip_tag
import app.lares.client.resources.cal_filter
import app.lares.client.resources.cal_filter_active
import app.lares.client.resources.cal_go_today
import app.lares.client.resources.cal_new_event
import app.lares.client.resources.cal_next
import app.lares.client.resources.cal_no_events
import app.lares.client.resources.cal_previous
import app.lares.client.resources.cal_show_past
import app.lares.client.resources.cal_view_list
import app.lares.client.resources.cal_view_month
import app.lares.client.resources.cal_view_week
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_calendar_month
import app.lares.client.resources.ic_chevron_left
import app.lares.client.resources.ic_chevron_right
import app.lares.client.resources.ic_close
import app.lares.client.resources.ic_event
import app.lares.client.resources.ic_filter_list
import app.lares.client.resources.ic_today
import app.lares.client.resources.placeholder_coming
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.GradientFab
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ScreenHeader
import app.lares.client.ui.components.SegmentedControl
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.tasks.TaskEventSnackbars
import app.lares.client.ui.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** What the Calendar tab can do; defaults do nothing (tests). */
class CalendarActions(
    val onMode: (CalendarMode) -> Unit = {},
    /** The pager settled on the month starting on this day. */
    val onMonth: (LocalDate) -> Unit = {},
    val onSelect: (LocalDate) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onToday: () -> Unit = {},
    val onExtendList: () -> Unit = {},
    val onShowPast: () -> Unit = {},
    val onPerson: (String?) -> Unit = {},
    val onTag: (String?) -> Unit = {},
    val onShowTasks: (Boolean) -> Unit = {},
    val onResetFilter: () -> Unit = {},
    val onCreate: (EventEditorArgs) -> Unit = {},
    val rows: CalendarRowActions = CalendarRowActions(),
)

/** The Calendar tab inside the main scaffold; [snackbar] is the scaffold's. */
@Composable
fun CalendarRoute(
    snackbar: SnackbarHostState,
    onEvent: (String, LocalDate) -> Unit,
    onEventEditor: (EventEditorArgs) -> Unit,
    onPerson: (String) -> Unit,
    onTask: (String) -> Unit,
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TaskEventSnackbars(viewModel.events, snackbar, onUndo = viewModel::undo)
    CalendarScreen(
        state,
        CalendarActions(
            onMode = viewModel::setMode,
            onMonth = viewModel::showMonth,
            onSelect = viewModel::select,
            onPrevious = viewModel::previous,
            onNext = viewModel::next,
            onToday = viewModel::goToToday,
            onExtendList = viewModel::extendList,
            onShowPast = viewModel::showPast,
            onPerson = viewModel::setPerson,
            onTag = viewModel::setTag,
            onShowTasks = viewModel::setShowTasks,
            onResetFilter = viewModel::resetFilter,
            onCreate = onEventEditor,
            rows = CalendarRowActions(onEvent = onEvent, onPerson = onPerson, onTask = onTask, onToggle = viewModel::toggle),
        ),
    )
}

/** The Calendar tab (mockup `Calendar.dc.html`): header with ‹ › «Сегодня» and the filter, the view switch, chips. */
@Composable
fun CalendarScreen(
    state: CalendarUiState,
    actions: CalendarActions = CalendarActions(),
) {
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val listIndex = remember(state.listDays) { ListIndex(state.listDays) }
    val visibleDay by remember(listIndex) { derivedStateOf { listIndex.dateAt(listState.firstVisibleItemIndex) } }
    val listToday = listIndex.firstOnOrAfter(state.today)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val titleDate =
                when (state.mode) {
                    CalendarMode.MONTH -> state.nav.month
                    CalendarMode.WEEK -> state.nav.weekStart
                    CalendarMode.LIST -> visibleDay ?: state.today
                }
            val showToday =
                when (state.mode) {
                    CalendarMode.LIST -> visibleDay != null && visibleDay != listToday?.second
                    else -> !state.atToday
                }
            ScreenHeader(
                CalendarLogic.monthTitle(titleDate.year, titleDate.month.ordinal + 1).text(),
                Modifier.testTag("cal_title"),
                singleLineTitle = true,
            ) {
                if (state.mode != CalendarMode.LIST) {
                    IconButton(onClick = actions.onPrevious, modifier = Modifier.testTag("cal_previous")) {
                        AppIcon(Res.drawable.ic_chevron_left, stringResource(Res.string.cal_previous))
                    }
                    IconButton(onClick = actions.onNext, modifier = Modifier.testTag("cal_next")) {
                        AppIcon(Res.drawable.ic_chevron_right, stringResource(Res.string.cal_next))
                    }
                }
                if (showToday) {
                    IconButton(
                        onClick = {
                            actions.onToday()
                            if (state.mode == CalendarMode.LIST) {
                                listToday?.let { (index, _) -> scope.launch { listState.animateScrollToItem(index) } }
                            }
                        },
                        modifier = Modifier.testTag("cal_today"),
                    ) { AppIcon(Res.drawable.ic_today, stringResource(Res.string.cal_go_today)) }
                }
                FilterButton(state.filtered) { filterOpen = true }
            }
            SegmentedControl(
                CalendarMode.entries,
                state.mode,
                label = { stringResource(it.label()) },
                onSelect = actions.onMode,
                modifier = Modifier.padding(horizontal = Spacing.screen).testTag("cal_modes"),
            )
            if (state.filtered) FilterChips(state, actions)
            when (state.mode) {
                CalendarMode.MONTH -> if (state.loaded) MonthView(state, actions)
                CalendarMode.WEEK -> WeekPlaceholder()
                CalendarMode.LIST -> ListView(state, actions, listState, listIndex)
            }
        }
        if (state.canCreate) {
            val description = stringResource(Res.string.cal_new_event)
            GradientFab(
                Res.drawable.ic_add,
                description,
                {
                    val date = if (state.mode == CalendarMode.MONTH) state.nav.selected else state.today
                    actions.onCreate(EventEditorArgs(date = date))
                },
                Modifier.align(Alignment.BottomEnd).padding(Spacing.l).testTag("cal_fab"),
            )
        }
    }
    if (filterOpen) CalendarFilterSheet(state, actions, onDismiss = { filterOpen = false })
}

@Composable
private fun FilterButton(
    active: Boolean,
    onClick: () -> Unit,
) {
    val description = stringResource(if (active) Res.string.cal_filter_active else Res.string.cal_filter)
    IconButton(onClick = onClick, modifier = Modifier.testTag("cal_filter")) {
        Box {
            AppIcon(Res.drawable.ic_filter_list, description)
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .testTag("cal_filter_badge"),
                )
            }
        }
    }
}

/** Active filters as chips; the cross removes one. */
@Composable
private fun FilterChips(
    state: CalendarUiState,
    actions: CalendarActions,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        state.filterPerson?.let { person ->
            RemovableChip(person.data.name, "cal_chip_person", { actions.onPerson(null) }) {
                Avatar(initialsFor(person.data.name, state.personNames), person.color, size = 20.dp)
            }
        }
        state.filterTag?.let { tag ->
            RemovableChip(stringResource(Res.string.cal_chip_tag, tag.data.name), "cal_chip_tag", { actions.onTag(null) })
        }
        if (state.hidesTasks) {
            RemovableChip(stringResource(Res.string.cal_chip_no_tasks), "cal_chip_tasks", { actions.onShowTasks(true) })
        }
    }
}

@Composable
private fun RemovableChip(
    label: String,
    tag: String,
    onRemove: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    val description = stringResource(Res.string.cal_chip_remove, label)
    AppFilterChip(
        true,
        label,
        onRemove,
        Modifier.testTag(tag).semantics { contentDescription = description },
        trailingIcon = Res.drawable.ic_close,
        leading = leading,
    )
}

/** Months in a pager (page [PAGER_BASE] = the current month) and the selected day's rows under the grid. */
@Composable
private fun MonthView(
    state: CalendarUiState,
    actions: CalendarActions,
) {
    val base = remember { firstOfMonth(state.today) }
    // Composed only once loaded, so the first page is the shown month, not a placeholder date.
    val pager = rememberPagerState(initialPage = PAGER_BASE + monthsBetween(base, state.nav.month)) { PAGER_PAGES }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            actions.onMonth(base.plus(page - PAGER_BASE, DateTimeUnit.MONTH))
        }
    }
    LaunchedEffect(state.nav.month) {
        val target = PAGER_BASE + monthsBetween(base, state.nav.month)
        if (pager.currentPage != target) pager.animateScrollToPage(target)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = Spacing.m, bottom = FAB_SPACE),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        HorizontalPager(pager, Modifier.fillMaxWidth().testTag("cal_pager")) { page ->
            val first = base.plus(page - PAGER_BASE, DateTimeUnit.MONTH)
            val cells = state.grids[first] ?: remember(first, state.today) { CalendarViewModel.plainGrid(first, state.today) }
            MonthGrid(cells, state.nav.selected, actions.onSelect)
        }
        DayList(
            header = CalendarLogic.dayHeader(state.nav.selected, state.today).text(),
            rows = state.day,
            today = state.today,
            actions = actions.rows,
            onAdd = if (state.canCreate) ({ actions.onCreate(EventEditorArgs(date = state.nav.selected)) }) else null,
            modifier = Modifier.padding(horizontal = Spacing.screen),
        )
    }
}

@Composable
private fun WeekPlaceholder() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EmptyState(Res.drawable.ic_calendar_month, stringResource(Res.string.placeholder_coming), Modifier.testTag("cal_week_placeholder"))
    }
}

/** «Список»: non-empty days from today; «Показать прошлые» on top, more days when the end comes near. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListView(
    state: CalendarUiState,
    actions: CalendarActions,
    listState: LazyListState,
    index: ListIndex,
) {
    LaunchedEffect(listState, index) {
        snapshotFlow {
            val last =
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - LIST_NEAR_END
        }.distinctUntilChanged()
            .filter { it }
            .collect { actions.onExtendList() }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("cal_list"),
        state = listState,
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = FAB_SPACE),
    ) {
        item(key = "past") {
            Box(Modifier.fillMaxWidth().padding(top = Spacing.s), contentAlignment = Alignment.Center) {
                LinkButton(stringResource(Res.string.cal_show_past), actions.onShowPast, Modifier.testTag("cal_show_past"))
            }
        }
        if (state.loaded && state.listDays.isEmpty()) {
            item(key = "empty") { EmptyState(Res.drawable.ic_event, stringResource(Res.string.cal_no_events)) }
        }
        state.listDays.forEach { day ->
            stickyHeader(key = "h-${day.date}") {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(top = Spacing.l, bottom = Spacing.s)) {
                    DayHeader(CalendarLogic.listHeader(day.date, state.today).text(), Modifier.testTag("list_header_${day.date}"))
                }
            }
            item(key = "d-${day.date}") { DayRows(day.rows, state.today, actions.rows) }
        }
        item(key = "end") { Box(Modifier.height(Spacing.l)) }
    }
}

/** Maps list item indexes (past button, then a header and a group per day) to days. */
private class ListIndex(
    days: List<DayUi>,
) {
    private val dates = days.map { it.date }

    /** The day shown at [itemIndex]; null for the past button and before the data is there. */
    fun dateAt(itemIndex: Int): LocalDate? {
        if (dates.isEmpty()) return null
        val day = ((itemIndex - 1) / 2).coerceIn(0, dates.lastIndex)
        return dates[day]
    }

    /** The header index and date of the first day on or after [date]. */
    fun firstOnOrAfter(date: LocalDate): Pair<Int, LocalDate>? {
        val day = dates.indexOfFirst { it >= date }.takeIf { it >= 0 } ?: return null
        return 1 + day * 2 to dates[day]
    }
}

private fun CalendarMode.label() =
    when (this) {
        CalendarMode.MONTH -> Res.string.cal_view_month
        CalendarMode.WEEK -> Res.string.cal_view_week
        CalendarMode.LIST -> Res.string.cal_view_list
    }

private fun monthsBetween(
    from: LocalDate,
    to: LocalDate,
): Int = (to.year - from.year) * MONTHS + (to.month.ordinal - from.month.ordinal)

private const val PAGER_BASE = 6000
private const val PAGER_PAGES = 12_000
private const val MONTHS = 12
private const val LIST_NEAR_END = 10
private val FAB_SPACE = 104.dp
