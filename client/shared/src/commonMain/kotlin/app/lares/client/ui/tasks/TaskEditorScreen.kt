package app.lares.client.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.lares.client.resources.Res
import app.lares.client.resources.action_cancel
import app.lares.client.resources.action_clear
import app.lares.client.resources.action_done
import app.lares.client.resources.action_save
import app.lares.client.resources.ic_alarm
import app.lares.client.resources.ic_calendar_month
import app.lares.client.resources.ic_close
import app.lares.client.resources.ic_group
import app.lares.client.resources.ic_repeat
import app.lares.client.resources.ic_schedule
import app.lares.client.resources.ic_sell
import app.lares.client.resources.task_anyone
import app.lares.client.resources.task_checklist
import app.lares.client.resources.task_checklist_item
import app.lares.client.resources.task_checklist_remove
import app.lares.client.resources.task_discard_confirm
import app.lares.client.resources.task_discard_keep
import app.lares.client.resources.task_discard_text
import app.lares.client.resources.task_discard_title
import app.lares.client.resources.task_editor_cannot_create
import app.lares.client.resources.task_editor_checklist_add
import app.lares.client.resources.task_editor_edit
import app.lares.client.resources.task_editor_new
import app.lares.client.resources.task_editor_read_only
import app.lares.client.resources.task_field_assignees
import app.lares.client.resources.task_field_date
import app.lares.client.resources.task_field_notes
import app.lares.client.resources.task_field_reminder
import app.lares.client.resources.task_field_repeat
import app.lares.client.resources.task_field_starts
import app.lares.client.resources.task_field_tags
import app.lares.client.resources.task_field_time
import app.lares.client.resources.task_field_title
import app.lares.client.resources.task_next_time
import app.lares.client.resources.task_notify_done
import app.lares.client.resources.task_reminder_none
import app.lares.client.resources.task_repeat_none
import app.lares.client.resources.task_tags_none
import app.lares.client.resources.task_time_none
import app.lares.client.resources.task_value_not_set
import app.lares.client.ui.common.text
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
import app.lares.client.ui.family.ReadOnlyHint
import app.lares.client.ui.family.TagSheetDialog
import app.lares.client.ui.theme.Spacing
import app.lares.contract.entities.Recurrence
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Instant

/** What the editor form can do; the defaults do nothing (screen tests). */
class TaskEditorActions(
    val onBack: () -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onNotes: (String) -> Unit = {},
    val onDate: (LocalDate?) -> Unit = {},
    val onTime: (LocalTime?) -> Unit = {},
    val onPreset: (RepeatPreset) -> Unit = {},
    val onRule: (Recurrence) -> Unit = {},
    val onAnyone: () -> Unit = {},
    val onToggleAssignee: (String) -> Unit = {},
    val onToggleTag: (String) -> Unit = {},
    val onNewTag: () -> Unit = {},
    val onReminder: (Int?) -> Unit = {},
    val onNotifyCreator: (Boolean) -> Unit = {},
    val onAddItem: () -> Unit = {},
    val onItem: (Int, String) -> Unit = { _, _ -> },
    val onRemoveItem: (Int) -> Unit = {},
    val onSave: () -> Unit = {},
)

/** The task editor (plan M1-06): [onCreated] opens the new task's page instead of the editor. */
@Composable
fun TaskEditorRoute(
    args: TaskEditorArgs,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    viewModel: TaskEditorViewModel = koinViewModel(key = "task-editor-$args") { parametersOf(args) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tagSheet by viewModel.tagSheet.collectAsState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.result) {
        when (val result = state.result) {
            is TaskEditorResult.Created -> onCreated(result.id)
            TaskEditorResult.Saved, TaskEditorResult.Gone -> onBack()
            null -> Unit
        }
    }
    val back = { if (state.changed && state.allowed) confirmDiscard = true else onBack() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.changed && state.allowed && state.result == null,
        onBackCompleted = { confirmDiscard = true },
    )
    TaskEditorScreen(
        state,
        TaskEditorActions(
            onBack = back,
            onTitle = viewModel::onTitle,
            onNotes = viewModel::onNotes,
            onDate = viewModel::onDate,
            onTime = viewModel::onTime,
            onPreset = viewModel::onPreset,
            onRule = viewModel::onRecurrence,
            onAnyone = viewModel::onAnyone,
            onToggleAssignee = viewModel::toggleAssignee,
            onToggleTag = viewModel::toggleTag,
            onNewTag = viewModel::newTag,
            onReminder = viewModel::onReminder,
            onNotifyCreator = viewModel::onNotifyCreator,
            onAddItem = viewModel::addChecklistItem,
            onItem = viewModel::onChecklistItem,
            onRemoveItem = viewModel::removeChecklistItem,
            onSave = viewModel::save,
        ),
    )
    tagSheet?.let { TagSheetDialog(it, viewModel.tagEditor) }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.task_discard_title),
            text = stringResource(Res.string.task_discard_text),
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

/** Which picker or sheet is open. */
private enum class EditorPicker { DATE, TIME, REPEAT, ASSIGNEES, TAGS, REMINDER }

/** The editor form (stateless apart from which picker is open). */
@Composable
fun TaskEditorScreen(
    state: TaskEditorUiState,
    actions: TaskEditorActions,
) {
    var picker by rememberSaveable { mutableStateOf<EditorPicker?>(null) }
    val form = state.form
    AppPage(
        topBar = {
            DetailTopBar(stringResource(if (state.isNew) Res.string.task_editor_new else Res.string.task_editor_edit), actions.onBack)
        },
    ) {
        if (!state.loaded) return@AppPage
        if (!state.allowed) {
            ReadOnlyHint(stringResource(if (state.isNew) Res.string.task_editor_cannot_create else Res.string.task_editor_read_only))
            return@AppPage
        }
        val titleFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { if (state.isNew) titleFocus.requestFocus() }
        FormField(
            form.title,
            actions.onTitle,
            stringResource(Res.string.task_field_title),
            modifier = Modifier.testTag("task_title"),
            error = state.fieldErrors["title"],
            capitalization = KeyboardCapitalization.Sentences,
            focusRequester = titleFocus,
        )
        FormField(
            form.notes,
            actions.onNotes,
            stringResource(Res.string.task_field_notes),
            capitalization = KeyboardCapitalization.Sentences,
            singleLine = false,
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Fields(state) { picker = it }
            state.fieldErrors["dueDate"].text()?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
            val next = state.nextTime
            if (form.isRoutine && next != null) {
                Text(
                    stringResource(Res.string.task_next_time, TasksLogic.day(next, state.today).text()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xs).testTag("next_time"),
                )
            }
        }
        if (state.isCreator) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .toggleable(form.notifyCreatorOnDone, role = Role.Switch, onValueChange = actions.onNotifyCreator)
                    .testTag("notify_done"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.task_notify_done),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = form.notifyCreatorOnDone, onCheckedChange = null)
            }
        }
        if (state.isNew) Checklist(form.checklist, actions)
        ErrorText(state.error)
        PrimaryButton(
            stringResource(Res.string.action_save),
            actions.onSave,
            enabled = state.canSave || state.saving,
            loading = state.saving,
            modifier = Modifier.testTag("task_save"),
        )
    }
    when (picker) {
        EditorPicker.DATE -> {
            DateDialog(form.dueDate ?: state.today, canClear = form.dueDate != null && !form.isRoutine, onPick = actions.onDate) {
                picker = null
            }
        }

        EditorPicker.TIME -> {
            TimeDialog(form.dueTime, onPick = actions.onTime) { picker = null }
        }

        EditorPicker.REPEAT -> {
            RepeatSheet(form, state.today, actions.onPreset, actions.onRule) { picker = null }
        }

        EditorPicker.ASSIGNEES -> {
            AssigneesSheet(state.members, state.allNames, form.assigneeIds, actions.onAnyone, actions.onToggleAssignee) { picker = null }
        }

        EditorPicker.TAGS -> {
            TagsSheet(state.tagChoices, form.tagIds, state.canManageTags, actions.onToggleTag, actions.onNewTag) { picker = null }
        }

        EditorPicker.REMINDER -> {
            ReminderSheet(form.dueTime != null, form.reminderMinutesBefore, actions.onReminder) { picker = null }
        }

        null -> {
            Unit
        }
    }
}

/** The rows that open pickers; time and reminder only with a date. */
@Composable
private fun Fields(
    state: TaskEditorUiState,
    open: (EditorPicker) -> Unit,
) {
    val form = state.form
    val date = form.dueDate
    val divider = @Composable { ListDivider() }
    ListGroup {
        ListRow(
            stringResource(if (form.isRoutine) Res.string.task_field_starts else Res.string.task_field_date),
            icon = Res.drawable.ic_calendar_month,
            supporting = date?.let { TasksLogic.day(it, state.today).text() } ?: stringResource(Res.string.task_value_not_set),
            onClick = { open(EditorPicker.DATE) },
            modifier = Modifier.testTag("field_date"),
        )
        if (date != null) {
            divider()
            ListRow(
                stringResource(Res.string.task_field_time),
                icon = Res.drawable.ic_schedule,
                supporting = form.dueTime?.let(TasksLogic::time) ?: stringResource(Res.string.task_time_none),
                onClick = { open(EditorPicker.TIME) },
                modifier = Modifier.testTag("field_time"),
            )
        }
        divider()
        ListRow(
            stringResource(Res.string.task_field_repeat),
            icon = Res.drawable.ic_repeat,
            supporting = form.recurrence?.let { TasksLogic.rule(it, date).text() } ?: stringResource(Res.string.task_repeat_none),
            onClick = { open(EditorPicker.REPEAT) },
            modifier = Modifier.testTag("field_repeat"),
        )
        divider()
        val names = state.members.filter { it.id in form.assigneeIds }.joinToString { it.name }
        ListRow(
            stringResource(Res.string.task_field_assignees),
            icon = Res.drawable.ic_group,
            supporting = names.ifEmpty { stringResource(Res.string.task_anyone) },
            onClick = { open(EditorPicker.ASSIGNEES) },
            modifier = Modifier.testTag("field_assignees"),
        )
        divider()
        val tags = state.tags.filter { it.id in form.tagIds }.joinToString { it.data.name }
        ListRow(
            stringResource(Res.string.task_field_tags),
            icon = Res.drawable.ic_sell,
            supporting = tags.ifEmpty { stringResource(Res.string.task_tags_none) },
            onClick = { open(EditorPicker.TAGS) },
            modifier = Modifier.testTag("field_tags"),
        )
        if (date != null) {
            divider()
            ListRow(
                stringResource(Res.string.task_field_reminder),
                icon = Res.drawable.ic_alarm,
                supporting =
                    form.reminderMinutesBefore?.let { TasksLogic.reminder(it, form.dueTime != null).text() }
                        ?: stringResource(Res.string.task_reminder_none),
                onClick = { open(EditorPicker.REMINDER) },
                modifier = Modifier.testTag("field_reminder"),
            )
        }
    }
}

/** Checklist items typed on create (TSK-004); a new item gets the focus. */
@Composable
private fun Checklist(
    items: List<String>,
    actions: TaskEditorActions,
) {
    val focus = remember { FocusRequester() }
    var focusIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(focusIndex, items.size) {
        if (focusIndex in items.indices) {
            focus.requestFocus()
            focusIndex = -1
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(Res.string.task_checklist), style = MaterialTheme.typography.labelLarge)
        items.forEachIndexed { index, text ->
            OutlinedTextField(
                value = text,
                onValueChange = { actions.onItem(index, it) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("checklist_item_$index")
                        .then(if (index == focusIndex) Modifier.focusRequester(focus) else Modifier),
                placeholder = { Text(stringResource(Res.string.task_checklist_item)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                trailingIcon = {
                    IconButton(onClick = { actions.onRemoveItem(index) }) {
                        AppIcon(Res.drawable.ic_close, stringResource(Res.string.task_checklist_remove))
                    }
                },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        }
        LinkButton(
            stringResource(Res.string.task_editor_checklist_add),
            {
                focusIndex = items.size
                actions.onAddItem()
            },
            modifier = Modifier.testTag("checklist_add"),
        )
    }
}

/** Material 3 date picker; "Clear" removes the date (not offered for routines, which always have a start). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
    initial: LocalDate,
    canClear: Boolean,
    onPick: (LocalDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LinkButton(stringResource(Res.string.action_done), {
                pickerState.selectedDateMillis?.let {
                    onPick(
                        Instant
                            .fromEpochMilliseconds(it)
                            .toLocalDateTime(TimeZone.UTC)
                            .date,
                    )
                }
                onDismiss()
            })
        },
        dismissButton = {
            Row {
                if (canClear) {
                    LinkButton(stringResource(Res.string.action_clear), {
                        onPick(null)
                        onDismiss()
                    })
                }
                LinkButton(stringResource(Res.string.action_cancel), onDismiss)
            }
        },
    ) { DatePicker(pickerState) }
}

/** Material 3 time picker (24 h by the system setting); "Clear" removes the time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    initial: LocalTime?,
    onPick: (LocalTime?) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState = rememberTimePickerState(initialHour = initial?.hour ?: DEFAULT_HOUR, initialMinute = initial?.minute ?: 0)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.task_field_time), style = MaterialTheme.typography.labelLarge) },
        confirmButton = {
            LinkButton(stringResource(Res.string.action_done), {
                onPick(LocalTime(pickerState.hour, pickerState.minute))
                onDismiss()
            })
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    LinkButton(stringResource(Res.string.action_clear), {
                        onPick(null)
                        onDismiss()
                    })
                }
                LinkButton(stringResource(Res.string.action_cancel), onDismiss)
            }
        },
    ) { TimePicker(pickerState) }
}

private const val DEFAULT_HOUR = 9
