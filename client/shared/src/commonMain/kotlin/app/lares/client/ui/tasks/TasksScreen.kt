package app.lares.client.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.data.Tag
import app.lares.client.data.tasks.TaskScope
import app.lares.client.resources.Res
import app.lares.client.resources.action_undo
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_check
import app.lares.client.resources.ic_close
import app.lares.client.resources.ic_group
import app.lares.client.resources.ic_search
import app.lares.client.resources.ic_sell
import app.lares.client.resources.ic_task_alt
import app.lares.client.resources.ic_tune
import app.lares.client.resources.nav_tasks
import app.lares.client.resources.tasks_chip_all
import app.lares.client.resources.tasks_chip_assigned_by_me
import app.lares.client.resources.tasks_chip_mine
import app.lares.client.resources.tasks_chip_tag
import app.lares.client.resources.tasks_done_snackbar
import app.lares.client.resources.tasks_empty_all
import app.lares.client.resources.tasks_empty_filtered
import app.lares.client.resources.tasks_empty_mine
import app.lares.client.resources.tasks_filter_anyone
import app.lares.client.resources.tasks_filter_assignee
import app.lares.client.resources.tasks_filter_state
import app.lares.client.resources.tasks_filters
import app.lares.client.resources.tasks_new
import app.lares.client.resources.tasks_only_assignees
import app.lares.client.resources.tasks_reset_filters
import app.lares.client.resources.tasks_scopes
import app.lares.client.resources.tasks_search
import app.lares.client.resources.tasks_search_close
import app.lares.client.resources.tasks_search_hint
import app.lares.client.resources.tasks_section_done
import app.lares.client.resources.tasks_section_no_date
import app.lares.client.resources.tasks_section_overdue
import app.lares.client.resources.tasks_section_today
import app.lares.client.resources.tasks_section_upcoming
import app.lares.client.resources.tasks_skipped_snackbar
import app.lares.client.resources.tasks_state_done
import app.lares.client.resources.tasks_state_open
import app.lares.client.resources.tasks_tag_clear
import app.lares.client.resources.tasks_tag_picker_title
import app.lares.client.resources.tasks_tags_none
import app.lares.client.ui.common.messageRes
import app.lares.client.ui.components.AppFilterChip
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.ExtendedGradientFab
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.ScreenHeader
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tones
import app.lares.contract.tasks.TaskGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** What the Tasks tab can do; defaults do nothing (previews, tests). */
class TasksActions(
    val onScope: (TaskScope) -> Unit = {},
    val onTag: (String?) -> Unit = {},
    val onAssignee: (String?) -> Unit = {},
    val onShowDone: (Boolean) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onToggleSearch: () -> Unit = {},
    val onResetFilters: () -> Unit = {},
    val onToggle: (TaskRowUi) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onCreate: () -> Unit = {},
)

/** The Tasks tab inside the main scaffold; [snackbar] is the scaffold's. */
@Composable
fun TasksRoute(
    snackbar: SnackbarHostState,
    onTask: (String) -> Unit,
    onNewTask: (TaskEditorArgs) -> Unit,
    viewModel: TasksViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TaskEventSnackbars(viewModel.events, snackbar, onUndo = viewModel::undo)
    TasksScreen(
        state,
        TasksActions(
            onScope = viewModel::setScope,
            onTag = viewModel::setTag,
            onAssignee = viewModel::setAssignee,
            onShowDone = viewModel::setShowDone,
            onQuery = viewModel::setQuery,
            onToggleSearch = viewModel::toggleSearch,
            onResetFilters = viewModel::resetFilters,
            onToggle = viewModel::toggle,
            onOpen = onTask,
            // A new task from "Mine" is mine; from a tag-filtered list it has that tag.
            onCreate = {
                val filter = viewModel.state.value.filter
                onNewTask(TaskEditorArgs(assignToMe = filter.scope == TaskScope.MINE, tagId = filter.tagId))
            },
        ),
    )
}

/** Shows [events] as snackbars: "Done" with "Undo" for 5 s, "Only assignees can mark this task", errors. */
@Composable
fun TaskEventSnackbars(
    events: Flow<TaskEvent>,
    snackbar: SnackbarHostState,
    onUndo: (String) -> Unit,
) {
    LaunchedEffect(events) {
        events.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            launch {
                when (event) {
                    is TaskEvent.Completed -> {
                        val result =
                            withTimeoutOrNull(UNDO_MS) {
                                snackbar.showSnackbar(
                                    getString(Res.string.tasks_done_snackbar),
                                    getString(Res.string.action_undo),
                                    duration = SnackbarDuration.Indefinite,
                                )
                            }
                        if (result == SnackbarResult.ActionPerformed) onUndo(event.completionId)
                    }

                    TaskEvent.Skipped -> {
                        snackbar.showSnackbar(getString(Res.string.tasks_skipped_snackbar))
                    }

                    TaskEvent.NotAllowed -> {
                        snackbar.showSnackbar(getString(Res.string.tasks_only_assignees))
                    }

                    is TaskEvent.Failed -> {
                        snackbar.showSnackbar(getString(event.error.messageRes()))
                    }
                }
            }
        }
    }
}

private const val UNDO_MS = 5_000L

/** The Tasks tab (mockup `Tasks.dc.html`): header with search and filters, scope chips, sections, FAB. */
@Composable
fun TasksScreen(
    state: TasksUiState,
    actions: TasksActions = TasksActions(),
) {
    var filtersOpen by rememberSaveable { mutableStateOf(false) }
    var tagPickerOpen by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(stringResource(Res.string.nav_tasks)) {
                IconButton(onClick = actions.onToggleSearch) { AppIcon(Res.drawable.ic_search, stringResource(Res.string.tasks_search)) }
                IconButton(onClick = { filtersOpen = true }) { AppIcon(Res.drawable.ic_tune, stringResource(Res.string.tasks_filters)) }
            }
            if (state.searchOpen) SearchField(state.filter.query, actions.onQuery, actions.onToggleSearch)
            ScopeChips(state, actions, onPickTag = { tagPickerOpen = true })
            Column(
                Modifier.padding(horizontal = Spacing.screen).padding(bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                if (state.loaded && state.sections.isEmpty()) {
                    EmptyTasks(state, actions.onResetFilters)
                }
                state.sections.forEach { section -> Section(section, state.allNames, actions) }
            }
        }
        if (state.canCreate) {
            ExtendedGradientFab(
                Res.drawable.ic_add,
                stringResource(Res.string.tasks_new),
                actions.onCreate,
                Modifier.align(Alignment.BottomEnd).padding(Spacing.l).testTag("tasks_fab"),
            )
        }
    }
    if (filtersOpen) FiltersSheet(state, actions, onDismiss = { filtersOpen = false })
    if (tagPickerOpen) {
        TagPickerSheet(state.tags, state.filter.tagId, onPick = {
            actions.onTag(it)
            tagPickerOpen = false
        }, onDismiss = { tagPickerOpen = false })
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Local text: the filter reaches the list asynchronously, and a field fed back from it moves the caret.
    var text by rememberSaveable { mutableStateOf(query) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onQuery(it)
        },
        placeholder = { Text(stringResource(Res.string.tasks_search_hint)) },
        leadingIcon = { AppIcon(Res.drawable.ic_search, null) },
        trailingIcon = {
            IconButton(onClick = onClose) { AppIcon(Res.drawable.ic_close, stringResource(Res.string.tasks_search_close)) }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.m)
                .focusRequester(focus)
                .testTag("tasks_search"),
    )
}

@Composable
private fun ScopeChips(
    state: TasksUiState,
    actions: TasksActions,
    onPickTag: () -> Unit,
) {
    val description = stringResource(Res.string.tasks_scopes)
    val scope = state.filter.scope
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.l)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        val count = { s: TaskScope -> (state.counts[s] ?: 0).toString() }
        AppFilterChip(
            scope == TaskScope.ALL,
            stringResource(Res.string.tasks_chip_all, count(TaskScope.ALL)),
            { actions.onScope(TaskScope.ALL) },
            Modifier.testTag("tasks_chip_ALL"),
        )
        AppFilterChip(
            scope == TaskScope.MINE,
            stringResource(Res.string.tasks_chip_mine, count(TaskScope.MINE)),
            { actions.onScope(TaskScope.MINE) },
            Modifier.testTag("tasks_chip_MINE"),
        )
        AppFilterChip(
            scope == TaskScope.ASSIGNED_BY_ME,
            stringResource(Res.string.tasks_chip_assigned_by_me),
            { actions.onScope(TaskScope.ASSIGNED_BY_ME) },
            Modifier.testTag("tasks_chip_ASSIGNED_BY_ME"),
        )
        val tag = state.selectedTag
        if (tag == null) {
            AppFilterChip(
                false,
                stringResource(Res.string.tasks_chip_tag),
                onPickTag,
                Modifier.testTag("tasks_chip_tag"),
                Res.drawable.ic_sell,
            )
        } else {
            val clear = stringResource(Res.string.tasks_tag_clear)
            AppFilterChip(
                true,
                tag.data.name,
                { actions.onTag(null) },
                Modifier.testTag("tasks_chip_tag").semantics { contentDescription = clear },
                Res.drawable.ic_sell,
                Res.drawable.ic_close,
            )
        }
    }
}

@Composable
private fun Section(
    section: TaskSectionUi,
    allNames: List<String>,
    actions: TasksActions,
) {
    val error = MaterialTheme.colorScheme.error
    val header =
        when (section.group) {
            TaskGroup.OVERDUE -> stringResource(Res.string.tasks_section_overdue, section.count.toString())
            TaskGroup.TODAY -> stringResource(Res.string.tasks_section_today, section.count.toString())
            TaskGroup.UPCOMING -> stringResource(Res.string.tasks_section_upcoming)
            TaskGroup.NO_DATE -> stringResource(Res.string.tasks_section_no_date)
            TaskGroup.DONE -> stringResource(Res.string.tasks_section_done)
        }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.testTag("tasks_section_${section.group.name}")) {
        SectionHeader(
            header,
            color = if (section.group == TaskGroup.OVERDUE) error else MaterialTheme.colorScheme.onSurface,
        )
        ListGroup {
            section.rows.forEachIndexed { index, row ->
                if (index > 0) ListDivider(inset = 52.dp)
                TaskRow(row, onToggle = { actions.onToggle(row) }, onClick = { actions.onOpen(row.view.id) }, allNames = allNames)
            }
        }
    }
}

@Composable
private fun EmptyTasks(
    state: TasksUiState,
    onReset: () -> Unit,
) {
    val text =
        when {
            state.filtered -> Res.string.tasks_empty_filtered
            state.filter.scope == TaskScope.MINE -> Res.string.tasks_empty_mine
            state.filter.scope == TaskScope.ALL -> Res.string.tasks_empty_all
            else -> Res.string.tasks_empty_filtered
        }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(Res.drawable.ic_task_alt, stringResource(text))
        if (state.filtered || state.filter.scope == TaskScope.ASSIGNED_BY_ME) {
            LinkButton(stringResource(Res.string.tasks_reset_filters), onReset)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FiltersSheet(
    state: TasksUiState,
    actions: TasksActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background, shape = SheetShape()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(Res.string.tasks_filters), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(Res.string.tasks_filter_assignee), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                val selected = state.filter.assigneeId
                AssigneeOption(stringResource(Res.string.tasks_filter_anyone), selected == null, { actions.onAssignee(null) }) {
                    Box(
                        Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { AppIcon(Res.drawable.ic_group, null, size = 20.dp) }
                }
                state.members.forEach { member ->
                    AssigneeOption(member.name, selected == member.id, { actions.onAssignee(member.id) }) {
                        Avatar(initialsFor(member.name, state.allNames), member.color, size = 40.dp)
                    }
                }
            }
            Text(stringResource(Res.string.tasks_filter_state), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                AppFilterChip(!state.filter.showDone, stringResource(Res.string.tasks_state_open), { actions.onShowDone(false) })
                AppFilterChip(state.filter.showDone, stringResource(Res.string.tasks_state_done), { actions.onShowDone(true) })
            }
        }
    }
}

@Composable
private fun AssigneeOption(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(76.dp)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .then(if (selected) Modifier.background(colors.primaryContainer, MaterialTheme.shapes.small) else Modifier)
            .padding(vertical = Spacing.s, horizontal = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        avatar()
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagPickerSheet(
    tags: List<Tag>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background, shape = SheetShape()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Text(stringResource(Res.string.tasks_tag_picker_title), style = MaterialTheme.typography.headlineSmall)
            if (tags.isEmpty()) {
                Text(
                    stringResource(Res.string.tasks_tags_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ListGroup {
                    tags.forEachIndexed { index, tag ->
                        if (index > 0) ListDivider()
                        val tones = tag.data.color.tones()
                        ListRow(
                            title = tag.data.name,
                            onClick = { onPick(tag.id) },
                            modifier = Modifier.testTag("tag_pick_${tag.id}"),
                            trailing = {
                                if (tag.id == selected) AppIcon(Res.drawable.ic_check, null, tint = MaterialTheme.colorScheme.primary)
                            },
                            leading = {
                                Box(Modifier.size(24.dp).background(tones.base, CircleShape))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetShape() = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0))
