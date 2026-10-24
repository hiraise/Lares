package app.lares.client.ui.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.data.tasks.ChecklistItem
import app.lares.client.data.tasks.TaskDetail
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.action_delete
import app.lares.client.resources.action_save
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_alarm
import app.lares.client.resources.ic_calendar_month
import app.lares.client.resources.ic_check
import app.lares.client.resources.ic_close
import app.lares.client.resources.ic_delete
import app.lares.client.resources.ic_edit
import app.lares.client.resources.ic_group
import app.lares.client.resources.ic_more_vert
import app.lares.client.resources.ic_notes
import app.lares.client.resources.ic_person
import app.lares.client.resources.ic_repeat
import app.lares.client.resources.ic_sell
import app.lares.client.resources.ic_skip_next
import app.lares.client.resources.ic_undo
import app.lares.client.resources.placeholder_coming
import app.lares.client.resources.task_action_complete
import app.lares.client.resources.task_action_edit
import app.lares.client.resources.task_action_more
import app.lares.client.resources.task_action_reopen
import app.lares.client.resources.task_action_skip
import app.lares.client.resources.task_anyone
import app.lares.client.resources.task_checklist
import app.lares.client.resources.task_checklist_add
import app.lares.client.resources.task_checklist_item
import app.lares.client.resources.task_checklist_progress
import app.lares.client.resources.task_checklist_rename
import app.lares.client.resources.task_created_by
import app.lares.client.resources.task_delete_occurrence
import app.lares.client.resources.task_delete_routine_title
import app.lares.client.resources.task_delete_series
import app.lares.client.resources.task_delete_text
import app.lares.client.resources.task_delete_title
import app.lares.client.resources.task_editor_edit
import app.lares.client.resources.task_editor_new
import app.lares.client.resources.task_history
import app.lares.client.resources.task_history_undo
import app.lares.client.resources.task_mark
import app.lares.client.resources.task_next_time
import app.lares.client.resources.task_no_date
import app.lares.client.resources.task_unmark
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.ChoiceDialog
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.EmptyState
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.PrimaryButton
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.components.TonalButton
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the task page can do; defaults do nothing (tests). */
class TaskDetailActions(
    val onBack: () -> Unit = {},
    val onEdit: () -> Unit = {},
    val onComplete: () -> Unit = {},
    val onReopen: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onUndo: (String) -> Unit = {},
    val onToggleItem: (ChecklistItem) -> Unit = {},
    val onRenameItem: (String, String) -> Unit = { _, _ -> },
    val onDeleteItem: (String) -> Unit = {},
    val onOpenNewItem: () -> Unit = {},
    val onNewItem: (String) -> Unit = {},
    val onAddItem: () -> Unit = {},
    val onCloseNewItem: () -> Unit = {},
)

/** How a routine is deleted (TSK-005). */
enum class DeleteChoice { OCCURRENCE, SERIES }

@Composable
fun TaskDetailRoute(
    id: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    viewModel: TaskDetailViewModel = koinViewModel(key = "task-$id") { parametersOf(TaskDetailArgs(id)) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val newItem by viewModel.newItem.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // Deleted here (whole series) or on another phone.
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    TaskEventSnackbars(viewModel.events, snackbar, onUndo = viewModel::undo)
    Box(Modifier.fillMaxSize()) {
        TaskDetailScreen(
            state,
            newItem,
            TaskDetailActions(
                onBack = onBack,
                onEdit = onEdit,
                onComplete = viewModel::complete,
                onReopen = viewModel::reopen,
                onSkip = viewModel::skip,
                onDelete = viewModel::delete,
                onUndo = viewModel::undo,
                onToggleItem = viewModel::toggleItem,
                onRenameItem = viewModel::renameItem,
                onDeleteItem = viewModel::deleteItem,
                onOpenNewItem = viewModel::openNewItem,
                onNewItem = viewModel::onNewItem,
                onAddItem = viewModel::addItem,
                onCloseNewItem = viewModel::closeNewItem,
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

/** The task page (plan M1-05): title and status, main action, facts, checklist, history. */
@Composable
fun TaskDetailScreen(
    state: TaskDetailUiState,
    newItem: String?,
    actions: TaskDetailActions = TaskDetailActions(),
) {
    val detail = state.detail
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    AppPage(topBar = { TopBar(detail, actions, onDelete = { confirmDelete = true }) }) {
        if (detail == null) return@AppPage
        val view = detail.task
        val routine = view.data.recurrence != null
        Header(state, detail)
        when {
            view.canComplete && !view.state.done -> PrimaryButton(stringResource(Res.string.task_action_complete), actions.onComplete)
            view.state.done && state.canReopen -> TonalButton(stringResource(Res.string.task_action_reopen), actions.onReopen)
        }
        Facts(state, detail)
        if (detail.checklist.isNotEmpty() || view.canEdit) Checklist(detail, newItem, actions)
        if (routine) History(state, actions.onUndo)
    }
    if (confirmDelete && detail != null) {
        if (detail.task.data.recurrence == null) {
            ConfirmDialog(
                title = stringResource(Res.string.task_delete_title),
                text = stringResource(Res.string.task_delete_text),
                confirmLabel = stringResource(Res.string.action_delete),
                onConfirm = {
                    confirmDelete = false
                    actions.onDelete()
                },
                onDismiss = { confirmDelete = false },
                destructive = true,
            )
        } else {
            ChoiceDialog<DeleteChoice?>(
                title = stringResource(Res.string.task_delete_routine_title),
                options = DeleteChoice.entries,
                selected = null,
                label = {
                    stringResource(if (it == DeleteChoice.SERIES) Res.string.task_delete_series else Res.string.task_delete_occurrence)
                },
                onSelect = {
                    confirmDelete = false
                    if (it == DeleteChoice.SERIES) actions.onDelete() else actions.onSkip()
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

@Composable
private fun TopBar(
    detail: TaskDetail?,
    actions: TaskDetailActions,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    DetailTopBar("", actions.onBack) {
        val view = detail?.task ?: return@DetailTopBar
        val routine = view.data.recurrence != null
        if (view.canEdit) {
            IconButton(onClick = actions.onEdit) { AppIcon(Res.drawable.ic_edit, stringResource(Res.string.task_action_edit)) }
        }
        val canSkip = routine && view.canComplete
        if (canSkip || view.canEdit) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("task_more")) {
                    AppIcon(Res.drawable.ic_more_vert, stringResource(Res.string.task_action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (canSkip) {
                        MenuItem(Res.string.task_action_skip, Res.drawable.ic_skip_next) {
                            menu = false
                            actions.onSkip()
                        }
                    }
                    if (view.canEdit) {
                        MenuItem(Res.string.action_delete, Res.drawable.ic_delete) {
                            menu = false
                            onDelete()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    label: org.jetbrains.compose.resources.StringResource,
    icon: DrawableResource,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { AppIcon(icon, null) },
        onClick = onClick,
    )
}

@Composable
private fun Header(
    state: TaskDetailUiState,
    detail: TaskDetail,
) {
    val view = detail.task
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(view.data.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("task_title"))
        TasksLogic.status(view, state.today, state.zone, state.names)?.let {
            Text(
                it.text(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (view.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("task_status"),
            )
        }
    }
}

@Composable
private fun Facts(
    state: TaskDetailUiState,
    detail: TaskDetail,
) {
    val view = detail.task
    val data = view.data
    val rows = mutableListOf<@Composable () -> Unit>()
    val occurrence = view.state.occurrence
    rows += {
        val title =
            if (occurrence ==
                null
            ) {
                stringResource(Res.string.task_no_date)
            } else {
                TasksLogic.due(occurrence, data.dueTime, state.today).text()
            }
        ListRow(title, icon = Res.drawable.ic_calendar_month)
    }
    data.recurrence?.let { rule ->
        rows += {
            val next = TasksLogic.nextOccurrence(data, view.state)?.let { TasksLogic.day(it, state.today).text() }
            ListRow(
                TasksLogic.rule(rule, data.dueDate).text(),
                icon = Res.drawable.ic_repeat,
                supporting = next?.let { stringResource(Res.string.task_next_time, it) },
            )
        }
    }
    rows += {
        val names = view.assignees.joinToString { it.name }.ifEmpty { stringResource(Res.string.task_anyone) }
        ListRow(names, icon = Res.drawable.ic_group, trailing = { AssigneeAvatars(view.assignees) })
    }
    if (view.tags.isNotEmpty()) {
        rows += {
            FactRow(Res.drawable.ic_sell) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { view.tags.forEach { TagChip(it) } }
            }
        }
    }
    data.reminderMinutesBefore?.let { minutes ->
        rows += { ListRow(TasksLogic.reminder(minutes, data.dueTime != null).text(), icon = Res.drawable.ic_alarm) }
    }
    data.notes?.takeIf { it.isNotBlank() }?.let { notes -> rows += { ListRow(notes.trim(), icon = Res.drawable.ic_notes) } }
    detail.creator?.let { creator ->
        rows += { ListRow(stringResource(Res.string.task_created_by, creator.name), icon = Res.drawable.ic_person) }
    }
    ListGroup {
        rows.forEachIndexed { index, row ->
            if (index > 0) ListDivider()
            row()
        }
    }
}

@Composable
private fun FactRow(
    icon: DrawableResource,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Spacing.l, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppIcon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), content = content)
    }
}

@Composable
private fun Checklist(
    detail: TaskDetail,
    newItem: String?,
    actions: TaskDetailActions,
) {
    val view = detail.task
    var renaming by remember { mutableStateOf<ChecklistItem?>(null) }
    val done = detail.checklist.count { it.shownDone }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(
            if (detail.checklist.isEmpty()) {
                stringResource(Res.string.task_checklist)
            } else {
                stringResource(Res.string.task_checklist_progress, "$done/${detail.checklist.size}")
            },
        )
        ListGroup {
            detail.checklist.forEachIndexed { index, item ->
                if (index > 0) ListDivider(inset = 52.dp)
                ChecklistRow(item, view.canComplete, view.canEdit, actions, onRename = { renaming = item })
            }
            if (view.canEdit) {
                if (detail.checklist.isNotEmpty()) ListDivider(inset = 52.dp)
                if (newItem == null) {
                    ListRow(
                        stringResource(Res.string.task_checklist_add),
                        icon = Res.drawable.ic_add,
                        titleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = actions.onOpenNewItem,
                        modifier = Modifier.testTag("checklist_add"),
                    )
                } else {
                    NewItemField(newItem, actions)
                }
            }
        }
    }
    renaming?.let { item ->
        RenameDialog(item.data.text, onSave = {
            actions.onRenameItem(item.id, it)
            renaming = null
        }, onDismiss = { renaming = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChecklistRow(
    item: ChecklistItem,
    canTick: Boolean,
    canEdit: Boolean,
    actions: TaskDetailActions,
    onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { actions.onToggleItem(item) }, onLongClick = { if (canEdit) menu = true })
                .padding(start = 6.dp, end = Spacing.l)
                .testTag("checklist_item_${item.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            TaskCheckbox(
                checked = item.shownDone,
                onToggle = { actions.onToggleItem(item) },
                label = stringResource(if (item.shownDone) Res.string.task_unmark else Res.string.task_mark, item.data.text),
                enabled = canTick,
                size = 20.dp,
            )
            Text(
                item.data.text,
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        textDecoration = if (item.shownDone) TextDecoration.LineThrough else null,
                    ),
                color = if (item.shownDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem(Res.string.task_checklist_rename, Res.drawable.ic_edit) {
                menu = false
                onRename()
            }
            MenuItem(Res.string.action_delete, Res.drawable.ic_delete) {
                menu = false
                actions.onDeleteItem(item.id)
            }
        }
    }
}

@Composable
private fun NewItemField(
    text: String,
    actions: TaskDetailActions,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = actions.onNewItem,
        placeholder = { Text(stringResource(Res.string.task_checklist_item)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.onAddItem() }),
        trailingIcon = {
            IconButton(onClick = actions.onCloseNewItem) {
                AppIcon(Res.drawable.ic_close, stringResource(Res.string.action_cancel))
            }
        },
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(Spacing.s)
                .focusRequester(focus)
                .testTag("checklist_new"),
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Caret at the end of the current name.
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.task_checklist_rename), style = MaterialTheme.typography.headlineSmall) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave(text.text) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { LinkButton(stringResource(Res.string.action_save), { onSave(text.text) }, enabled = text.text.isNotBlank()) },
        dismissButton = { LinkButton(stringResource(Res.string.action_cancel), onDismiss) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    )
}

@Composable
private fun History(
    state: TaskDetailUiState,
    onUndo: (String) -> Unit,
) {
    if (state.history.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(stringResource(Res.string.task_history))
        ListGroup {
            state.history.forEachIndexed { index, entry ->
                if (index > 0) ListDivider()
                val skipped = entry.completion.data.skipped
                ListRow(
                    TasksLogic.history(entry.completion, state.zone, state.names).text(),
                    icon = if (skipped) Res.drawable.ic_skip_next else Res.drawable.ic_check,
                    modifier = Modifier.testTag("history_${entry.completion.id}"),
                    trailing = {
                        if (entry.canUndo) {
                            IconButton(onClick = { onUndo(entry.completion.id) }) {
                                AppIcon(Res.drawable.ic_undo, stringResource(Res.string.task_history_undo))
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Until M1-06: the editor route shows "Coming in the next versions". */
@Composable
fun TaskEditorRoute(
    id: String?,
    onBack: () -> Unit,
) {
    AppPage(topBar = {
        DetailTopBar(
            stringResource(
                if (id ==
                    null
                ) {
                    Res.string.task_editor_new
                } else {
                    Res.string.task_editor_edit
                },
            ),
            onBack,
        )
    }) {
        EmptyState(Res.drawable.ic_edit, stringResource(Res.string.placeholder_coming))
    }
}
