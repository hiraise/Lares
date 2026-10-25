package app.lares.client.ui.calendar

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.lares.client.notifications.NotificationPermissions
import app.lares.client.resources.Res
import app.lares.client.resources.action_save
import app.lares.client.resources.cal_all_day
import app.lares.client.resources.cal_count_years
import app.lares.client.resources.cal_discard_text
import app.lares.client.resources.cal_editor_cannot_create
import app.lares.client.resources.cal_editor_edit
import app.lares.client.resources.cal_editor_memorable
import app.lares.client.resources.cal_editor_no_rights
import app.lares.client.resources.cal_field_date
import app.lares.client.resources.cal_field_end
import app.lares.client.resources.cal_field_participants
import app.lares.client.resources.cal_field_place
import app.lares.client.resources.cal_field_start
import app.lares.client.resources.cal_field_until
import app.lares.client.resources.cal_kind_event
import app.lares.client.resources.cal_memorable_hint
import app.lares.client.resources.cal_new_event
import app.lares.client.resources.cal_reminder_for_everyone
import app.lares.client.resources.cal_reminder_for_participants
import app.lares.client.resources.cal_reset_text
import app.lares.client.resources.cal_reset_title
import app.lares.client.resources.cal_scope_note_following
import app.lares.client.resources.cal_scope_note_this
import app.lares.client.resources.cal_title_hint_event
import app.lares.client.resources.cal_title_hint_memorable
import app.lares.client.resources.cal_until_none
import app.lares.client.resources.cal_weekday_for
import app.lares.client.resources.cal_weekday_from
import app.lares.client.resources.cal_whole_family
import app.lares.client.resources.cal_year_unknown
import app.lares.client.resources.cal_years_this_year
import app.lares.client.resources.date_day_month
import app.lares.client.resources.date_day_month_year
import app.lares.client.resources.date_with_weekday
import app.lares.client.resources.ic_alarm
import app.lares.client.resources.ic_calendar_month
import app.lares.client.resources.ic_event_repeat
import app.lares.client.resources.ic_group
import app.lares.client.resources.ic_location_on
import app.lares.client.resources.ic_repeat
import app.lares.client.resources.ic_schedule
import app.lares.client.resources.ic_sell
import app.lares.client.resources.months
import app.lares.client.resources.months_short
import app.lares.client.resources.notif_allow
import app.lares.client.resources.notif_prompt_later
import app.lares.client.resources.task_discard_confirm
import app.lares.client.resources.task_discard_keep
import app.lares.client.resources.task_discard_title
import app.lares.client.resources.task_field_notes
import app.lares.client.resources.task_field_reminder
import app.lares.client.resources.task_field_repeat
import app.lares.client.resources.task_field_tags
import app.lares.client.resources.task_field_title
import app.lares.client.resources.task_reminder_needs_notifications_text
import app.lares.client.resources.task_reminder_needs_notifications_title
import app.lares.client.resources.task_reminder_none
import app.lares.client.resources.task_repeat_none
import app.lares.client.resources.task_tags_none
import app.lares.client.resources.weekdays_short
import app.lares.client.ui.common.RepeatPreset
import app.lares.client.ui.common.UiNotices
import app.lares.client.ui.common.rememberNotificationPermissionRequest
import app.lares.client.ui.common.text
import app.lares.client.ui.common.uiText
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.DateDialog
import app.lares.client.ui.components.DayMonthYearDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.FormField
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.SegmentedControl
import app.lares.client.ui.components.TimeDialog
import app.lares.client.ui.family.ReadOnlyHint
import app.lares.client.ui.family.TagSheetDialog
import app.lares.client.ui.tasks.EditorSheet
import app.lares.client.ui.tasks.RepeatSheet
import app.lares.client.ui.tasks.TagsSheet
import app.lares.client.ui.tasks.TasksLogic
import app.lares.client.ui.theme.Spacing
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.Recurrence
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the editor form can do; the defaults do nothing (screen tests). */
class EventEditorActions(
    val onBack: () -> Unit = {},
    val onKind: (EventKind) -> Unit = {},
    val onTitle: (String) -> Unit = {},
    val onAllDay: (Boolean) -> Unit = {},
    val onStartDate: (LocalDate) -> Unit = {},
    val onStartTime: (LocalTime) -> Unit = {},
    val onEndDate: (LocalDate) -> Unit = {},
    val onEndTime: (LocalTime) -> Unit = {},
    val onPreset: (RepeatPreset) -> Unit = {},
    val onRule: (Recurrence) -> Unit = {},
    val onRepeatUntil: (LocalDate?) -> Unit = {},
    val onWholeFamily: () -> Unit = {},
    val onToggleParticipant: (String) -> Unit = {},
    val onPlace: (String) -> Unit = {},
    val onReminder: (Int?) -> Unit = {},
    val onToggleTag: (String) -> Unit = {},
    val onNewTag: () -> Unit = {},
    val onNotes: (String) -> Unit = {},
    val onMemorableDate: (Int, Int, Int?) -> Unit = { _, _, _ -> },
    val onCountYears: (Boolean) -> Unit = {},
    val onSave: () -> Unit = {},
    val onConfirmReset: () -> Unit = {},
    val onDismissReset: () -> Unit = {},
)

/**
 * The event editor (plan M3-06): [onCreated] opens the new event's page instead of the editor; [onSplit] opens the
 * series «Это и следующие» created on [EventEditorArgs.occurrence] instead of the old event's page.
 */
@Composable
fun EventEditorRoute(
    args: EventEditorArgs,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    onSplit: (String, LocalDate?) -> Unit,
    viewModel: EventEditorViewModel = koinViewModel(key = "event-editor-$args") { parametersOf(args) },
    permissions: NotificationPermissions = koinInject(),
    notices: UiNotices = koinInject(),
) {
    // A reminder needs notifications: ask when one is chosen while they are off (as in the task editor).
    var askNotifications by rememberSaveable { mutableStateOf(false) }
    val requestPermission = rememberNotificationPermissionRequest { granted -> if (!granted) permissions.openNotificationSettings() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tagSheet by viewModel.tagSheet.collectAsState()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.result) {
        when (val result = state.result) {
            is EventEditorResult.Created -> {
                onCreated(result.id)
            }

            is EventEditorResult.Saved -> {
                val split = result.splitId
                if (split != null) onSplit(split, args.occurrence) else onBack()
            }

            EventEditorResult.Gone -> {
                onBack()
            }

            EventEditorResult.Forbidden -> {
                notices.post(uiText(Res.string.cal_editor_no_rights))
                onBack()
            }

            null -> {
                Unit
            }
        }
    }
    val back = { if (state.changed && state.allowed) confirmDiscard = true else onBack() }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = state.changed && state.allowed && state.result == null,
        onBackCompleted = { confirmDiscard = true },
    )
    EventEditorScreen(
        state,
        EventEditorActions(
            onBack = back,
            onKind = viewModel::onKind,
            onTitle = viewModel::onTitle,
            onAllDay = viewModel::onAllDay,
            onStartDate = viewModel::onStartDate,
            onStartTime = viewModel::onStartTime,
            onEndDate = viewModel::onEndDate,
            onEndTime = viewModel::onEndTime,
            onPreset = viewModel::onPreset,
            onRule = viewModel::onRecurrence,
            onRepeatUntil = viewModel::onRepeatUntil,
            onWholeFamily = viewModel::onWholeFamily,
            onToggleParticipant = viewModel::toggleParticipant,
            onPlace = viewModel::onPlace,
            onReminder = { minutes ->
                viewModel.onReminder(minutes)
                if (minutes != null && !permissions.notificationsAllowed()) askNotifications = true
            },
            onToggleTag = viewModel::toggleTag,
            onNewTag = viewModel::newTag,
            onNotes = viewModel::onNotes,
            onMemorableDate = viewModel::onMemorableDate,
            onCountYears = viewModel::onCountYears,
            onSave = { viewModel.save() },
            onConfirmReset = { viewModel.save(confirmed = true) },
            onDismissReset = viewModel::dismissReset,
        ),
    )
    tagSheet?.let { TagSheetDialog(it, viewModel.tagEditor) }
    if (askNotifications) {
        ConfirmDialog(
            title = stringResource(Res.string.task_reminder_needs_notifications_title),
            text = stringResource(Res.string.task_reminder_needs_notifications_text),
            confirmLabel = stringResource(Res.string.notif_allow),
            dismissLabel = stringResource(Res.string.notif_prompt_later),
            onConfirm = {
                askNotifications = false
                if (permissions.needsRuntimePermission) requestPermission() else permissions.openNotificationSettings()
            },
            onDismiss = { askNotifications = false },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.task_discard_title),
            text = stringResource(Res.string.cal_discard_text),
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
private enum class EventPicker { START_DATE, START_TIME, END_DATE, END_TIME, REPEAT, UNTIL, PARTICIPANTS, REMINDER, TAGS, DATE }

/** The editor form (stateless apart from which picker is open). */
@Composable
fun EventEditorScreen(
    state: EventEditorUiState,
    actions: EventEditorActions,
) {
    var picker by rememberSaveable { mutableStateOf<EventPicker?>(null) }
    val form = state.form
    val title =
        when {
            state.isNew -> stringResource(if (form.memorable) Res.string.cal_editor_memorable else Res.string.cal_new_event)
            form.memorable -> stringResource(Res.string.cal_editor_memorable)
            else -> stringResource(Res.string.cal_editor_edit)
        }
    AppPage(
        topBar = {
            DetailTopBar(title, actions.onBack) {
                if (state.loaded && state.allowed) {
                    LinkButton(
                        stringResource(Res.string.action_save),
                        actions.onSave,
                        enabled = state.canSave,
                        modifier = Modifier.testTag("event_save"),
                    )
                }
            }
        },
    ) {
        if (!state.loaded) return@AppPage
        if (!state.allowed) {
            if (state.isNew) ReadOnlyHint(stringResource(Res.string.cal_editor_cannot_create))
            return@AppPage
        }
        if (state.isNew) {
            SegmentedControl(
                listOf(EventKind.EVENT, EventKind.MEMORABLE_DATE),
                form.kind,
                label = { stringResource(if (it == EventKind.EVENT) Res.string.cal_kind_event else Res.string.cal_editor_memorable) },
                onSelect = actions.onKind,
                modifier = Modifier.testTag("event_kind"),
            )
        }
        val titleFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { if (state.isNew) titleFocus.requestFocus() }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FormField(
                form.title,
                actions.onTitle,
                stringResource(Res.string.task_field_title),
                modifier = Modifier.testTag("event_title"),
                hint = stringResource(if (form.memorable) Res.string.cal_title_hint_memorable else Res.string.cal_title_hint_event),
                error = state.fieldErrors["title"],
                capitalization = KeyboardCapitalization.Sentences,
                focusRequester = titleFocus,
            )
            ScopeNote(state)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (form.memorable) MemorableFields(state, actions) { picker = it } else EventFields(state, actions) { picker = it }
            FieldErrorLine(state.fieldErrors["endDate"]?.text(), "error_end")
            FieldErrorLine(state.fieldErrors["repeatUntil"]?.text(), "error_until")
            if (form.memorable) {
                Text(
                    stringResource(Res.string.cal_memorable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
        }
        FormField(
            form.notes,
            actions.onNotes,
            stringResource(Res.string.task_field_notes),
            modifier = Modifier.testTag("event_notes"),
            capitalization = KeyboardCapitalization.Sentences,
            singleLine = false,
        )
        ErrorText(state.error)
    }
    Pickers(state, actions, picker) { picker = null }
    if (state.confirmReset) {
        ConfirmDialog(
            title = stringResource(Res.string.cal_reset_title),
            text = stringResource(Res.string.cal_reset_text, state.changedCount),
            confirmLabel = stringResource(Res.string.action_save),
            onConfirm = actions.onConfirmReset,
            onDismiss = actions.onDismissReset,
        )
    }
}

/** «Изменения только для вторника, 28 октября» / «Изменения со вторника, 28 октября». */
@Composable
private fun ScopeNote(state: EventEditorUiState) {
    val occurrence = state.occurrence ?: return
    if (state.isNew || state.scope == RepeatScope.ALL) return
    val onlyThis = state.scope == RepeatScope.THIS
    val weekday = stringArrayResource(if (onlyThis) Res.array.cal_weekday_for else Res.array.cal_weekday_from)[occurrence.dayOfWeek.ordinal]
    val day = CalendarLogic.dayMonth(occurrence, state.today).text()
    Text(
        stringResource(if (onlyThis) Res.string.cal_scope_note_this else Res.string.cal_scope_note_following, weekday, day),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xs).testTag("scope_note"),
    )
}

@Composable
private fun FieldErrorLine(
    text: String?,
    tag: String,
) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = Spacing.xs).testTag(tag),
    )
}

/** The rows of an event; «Только это» hides the all-day switch, repeat, «До», reminder and tags. */
@Composable
private fun EventFields(
    state: EventEditorUiState,
    actions: EventEditorActions,
    open: (EventPicker) -> Unit,
) {
    val form = state.form
    val only = state.onlyThis
    val divider = @Composable { ListDivider() }
    ListGroup {
        if (!only) {
            SwitchRow(stringResource(Res.string.cal_all_day), form.allDay, actions.onAllDay, Modifier.testTag("all_day"))
            divider()
        }
        DateTimeRow(
            stringResource(Res.string.cal_field_start),
            Res.drawable.ic_calendar_month,
            form.startDate,
            form.startTime.takeIf { !form.allDay },
            state.today,
            onDate = { open(EventPicker.START_DATE) },
            onTime = { open(EventPicker.START_TIME) },
            tag = "start",
        )
        divider()
        DateTimeRow(
            stringResource(Res.string.cal_field_end),
            Res.drawable.ic_schedule,
            form.endDate,
            form.endTime.takeIf { !form.allDay },
            state.today,
            onDate = { open(EventPicker.END_DATE) },
            onTime = { open(EventPicker.END_TIME) },
            tag = "end",
        )
        if (!only) {
            divider()
            val rule = form.recurrence
            ListRow(
                stringResource(Res.string.task_field_repeat),
                icon = Res.drawable.ic_repeat,
                supporting = rule?.let { TasksLogic.rule(it, form.startDate).text() } ?: stringResource(Res.string.task_repeat_none),
                onClick = { open(EventPicker.REPEAT) },
                modifier = Modifier.testTag("field_repeat"),
            )
            if (rule != null) {
                divider()
                ListRow(
                    stringResource(Res.string.cal_field_until),
                    icon = Res.drawable.ic_event_repeat,
                    supporting =
                        form.repeatUntil?.let { CalendarLogic.dayMonth(it, state.today).text() }
                            ?: stringResource(Res.string.cal_until_none),
                    onClick = { open(EventPicker.UNTIL) },
                    modifier = Modifier.testTag("field_until"),
                )
            }
        }
        divider()
        ParticipantsRow(state, open)
        divider()
        PlaceRow(form.place, actions.onPlace)
        if (!only) {
            divider()
            val minutes = form.reminderMinutesBefore
            val reminder = CalendarLogic.reminder(minutes, form.allDay).text()
            ListRow(
                stringResource(Res.string.task_field_reminder),
                icon = Res.drawable.ic_alarm,
                supporting =
                    when {
                        minutes == null -> stringResource(Res.string.task_reminder_none)
                        form.participantIds.isEmpty() -> stringResource(Res.string.cal_reminder_for_everyone, reminder)
                        else -> stringResource(Res.string.cal_reminder_for_participants, reminder)
                    },
                onClick = { open(EventPicker.REMINDER) },
                modifier = Modifier.testTag("field_reminder"),
            )
            divider()
            TagsRow(state, open)
        }
    }
}

/** A memorable date: the date, «Считать годы» (only with a year), participants and tags. */
@Composable
private fun MemorableFields(
    state: EventEditorUiState,
    actions: EventEditorActions,
    open: (EventPicker) -> Unit,
) {
    val form = state.form
    val divider = @Composable { ListDivider() }
    ListGroup {
        val date = form.startDate
        val month = stringArrayResource(Res.array.months)[date.month.ordinal]
        ListRow(
            stringResource(Res.string.cal_field_date),
            icon = Res.drawable.ic_calendar_month,
            supporting =
                if (form.yearKnown) {
                    stringResource(Res.string.date_day_month_year, date.day.toString(), month, date.year.toString())
                } else {
                    stringResource(Res.string.date_day_month, date.day.toString(), month)
                },
            onClick = { open(EventPicker.DATE) },
            modifier = Modifier.testTag("field_date"),
        )
        if (form.yearKnown) {
            divider()
            val years = form.yearsThisYear(state.today)
            SwitchRow(
                stringResource(Res.string.cal_count_years),
                form.countYears,
                actions.onCountYears,
                Modifier.testTag("count_years"),
                supporting = years?.let { stringResource(Res.string.cal_years_this_year, CalendarLogic.years(it).text()) },
            )
        }
        divider()
        ParticipantsRow(state, open)
        divider()
        TagsRow(state, open)
    }
}

@Composable
private fun ParticipantsRow(
    state: EventEditorUiState,
    open: (EventPicker) -> Unit,
) {
    val names = state.participants.filter { it.id in state.form.participantIds }.joinToString { it.name }
    ListRow(
        stringResource(Res.string.cal_field_participants),
        icon = Res.drawable.ic_group,
        supporting = names.ifEmpty { stringResource(Res.string.cal_whole_family) },
        onClick = { open(EventPicker.PARTICIPANTS) },
        modifier = Modifier.testTag("field_participants"),
    )
}

@Composable
private fun TagsRow(
    state: EventEditorUiState,
    open: (EventPicker) -> Unit,
) {
    val tags = state.tags.filter { it.id in state.form.tagIds }.joinToString { it.data.name }
    ListRow(
        stringResource(Res.string.task_field_tags),
        icon = Res.drawable.ic_sell,
        supporting = tags.ifEmpty { stringResource(Res.string.task_tags_none) },
        onClick = { open(EventPicker.TAGS) },
        modifier = Modifier.testTag("field_tags"),
    )
}

/** «Начало» / «Конец»: the date opens the date picker, the time chip (timed only) the time picker. */
@Composable
private fun DateTimeRow(
    label: String,
    icon: org.jetbrains.compose.resources.DrawableResource,
    date: LocalDate,
    time: LocalTime?,
    today: LocalDate,
    onDate: () -> Unit,
    onTime: () -> Unit,
    tag: String,
) {
    ListRow(
        label,
        icon = icon,
        supporting = shortDate(date, today),
        onClick = onDate,
        modifier = Modifier.testTag("field_$tag"),
        trailing =
            time?.let {
                {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable(onClick = onTime)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("${tag}_time"),
                    ) { Text(TasksLogic.time(it), style = MaterialTheme.typography.labelLarge) }
                }
            },
    )
}

/** «Вт, 28 окт.» / "Tue, Oct 28", with the year when it is not this year. */
@Composable
private fun shortDate(
    date: LocalDate,
    today: LocalDate,
): String {
    val weekday = stringArrayResource(Res.array.weekdays_short)[date.dayOfWeek.ordinal].replaceFirstChar { it.titlecase() }
    val month = stringArrayResource(Res.array.months_short)[date.month.ordinal]
    val day =
        if (date.year == today.year) {
            stringResource(Res.string.date_day_month, date.day.toString(), month)
        } else {
            stringResource(Res.string.date_day_month_year, date.day.toString(), month, date.year.toString())
        }
    return stringResource(Res.string.date_with_weekday, weekday, day)
}

/** A row with a switch; the whole row toggles. */
@Composable
private fun SwitchRow(
    text: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = Spacing.l, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** «Место» typed in the row itself (single line). */
@Composable
private fun PlaceRow(
    value: String,
    onChange: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Spacing.l, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppIcon(Res.drawable.ic_location_on, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        val style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
        BasicTextField(
            value,
            onChange,
            modifier = Modifier.weight(1f).testTag("event_place"),
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            stringResource(Res.string.cal_field_place),
                            style = style,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun Pickers(
    state: EventEditorUiState,
    actions: EventEditorActions,
    picker: EventPicker?,
    close: () -> Unit,
) {
    val form = state.form
    when (picker) {
        EventPicker.START_DATE -> {
            DateDialog(form.startDate, canClear = false, onPick = { it?.let(actions.onStartDate) }, onDismiss = close)
        }

        EventPicker.START_TIME -> {
            TimeDialog(form.startTime, onPick = { it?.let(actions.onStartTime) }, canClear = false, onDismiss = close)
        }

        EventPicker.END_DATE -> {
            DateDialog(form.endDate, canClear = false, onPick = { it?.let(actions.onEndDate) }, onDismiss = close)
        }

        EventPicker.END_TIME -> {
            TimeDialog(form.endTime, onPick = { it?.let(actions.onEndTime) }, canClear = false, onDismiss = close)
        }

        EventPicker.REPEAT -> {
            RepeatSheet(
                form.recurrence,
                form.startDate,
                allowAfterCompletion = false,
                onPreset = actions.onPreset,
                onRule = actions.onRule,
                onDismiss = close,
            )
        }

        EventPicker.UNTIL -> {
            DateDialog(
                form.repeatUntil ?: form.startDate,
                canClear = true,
                onPick = actions.onRepeatUntil,
                clearLabel = stringResource(Res.string.cal_until_none),
                onDismiss = close,
            )
        }

        EventPicker.PARTICIPANTS -> {
            ParticipantsSheet(
                state.participants,
                state.allNames,
                form.participantIds,
                actions.onWholeFamily,
                actions.onToggleParticipant,
                close,
            )
        }

        EventPicker.REMINDER -> {
            EventReminderSheet(form.allDay, form.reminderMinutesBefore, actions.onReminder, close)
        }

        EventPicker.TAGS -> {
            TagsSheet(state.tagChoices, form.tagIds, state.canManageTags, actions.onToggleTag, actions.onNewTag, close)
        }

        EventPicker.DATE -> {
            DayMonthYearDialog(
                title = stringResource(Res.string.cal_field_date),
                unknownYear = stringResource(Res.string.cal_year_unknown),
                day = form.startDate.day,
                month = form.startDate.month.number,
                year = form.startDate.year.takeIf { form.yearKnown },
                onSave = { d, m, y ->
                    close()
                    if (d != null && m != null) actions.onMemorableDate(d, m, y)
                },
                onDismiss = close,
            )
        }

        null -> {
            Unit
        }
    }
}

/** The reminder of the event: none and [EventReminderOptions]; a stored value outside them is shown too. */
@Composable
private fun EventReminderSheet(
    allDay: Boolean,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    EditorSheet(stringResource(Res.string.task_field_reminder), onDismiss) {
        EventReminderOptions(allDay, selected) {
            onSelect(it)
            onDismiss()
        }
    }
}

/** «Нет» and the reminder choices of a timed or all-day event (CAL-006). */
@Composable
fun EventReminderOptions(
    allDay: Boolean,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val options = CalendarLogic.reminderOptions(allDay)
    val all: List<Int?> = listOf(null) + options + listOfNotNull(selected?.takeIf { it !in options })
    ListGroup {
        all.forEachIndexed { index, minutes ->
            if (index > 0) ListDivider(inset = Spacing.l)
            ListRow(
                CalendarLogic.reminder(minutes, allDay).text(),
                modifier = Modifier.testTag("reminder_${minutes ?: "none"}"),
                onClick = { onSelect(minutes) },
                trailing = { RadioButton(selected = minutes == selected, onClick = null) },
            )
        }
    }
}
