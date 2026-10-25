package app.lares.client.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lares.client.data.calendar.EventDetail
import app.lares.client.notifications.NotificationPermissions
import app.lares.client.resources.Res
import app.lares.client.resources.action_continue
import app.lares.client.resources.action_delete
import app.lares.client.resources.cal_created_by
import app.lares.client.resources.cal_delete_text
import app.lares.client.resources.cal_delete_title
import app.lares.client.resources.cal_event_changed_note
import app.lares.client.resources.cal_remind_me
import app.lares.client.resources.cal_reminder_as_event
import app.lares.client.resources.cal_reminder_memorable
import app.lares.client.resources.cal_reminder_title
import app.lares.client.resources.cal_scope_delete_title
import app.lares.client.resources.cal_scope_edit_title
import app.lares.client.resources.cal_whole_family
import app.lares.client.resources.ic_alarm
import app.lares.client.resources.ic_calendar_month
import app.lares.client.resources.ic_delete
import app.lares.client.resources.ic_edit
import app.lares.client.resources.ic_group
import app.lares.client.resources.ic_info
import app.lares.client.resources.ic_location_on
import app.lares.client.resources.ic_more_vert
import app.lares.client.resources.ic_notes
import app.lares.client.resources.ic_person
import app.lares.client.resources.ic_repeat
import app.lares.client.resources.ic_sell
import app.lares.client.resources.notif_allow
import app.lares.client.resources.notif_prompt_later
import app.lares.client.resources.task_action_edit
import app.lares.client.resources.task_action_more
import app.lares.client.resources.task_reminder_needs_notifications_text
import app.lares.client.resources.task_reminder_needs_notifications_title
import app.lares.client.ui.common.UiNotices
import app.lares.client.ui.common.messageRes
import app.lares.client.ui.common.rememberNotificationPermissionRequest
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.tasks.EditorSheet
import app.lares.client.ui.tasks.TagChip
import app.lares.client.ui.theme.Spacing
import app.lares.contract.entities.EventKind
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** What the event page can do; defaults do nothing (tests). */
class EventActions(
    val onBack: () -> Unit = {},
    /** Opens the editor for the chosen scope (ALL for one-offs and memorable dates). */
    val onEdit: (RepeatScope) -> Unit = {},
    val onDelete: (RepeatScope) -> Unit = {},
    val onTag: (String) -> Unit = {},
    /** «Напомнить мне»: minutes or null for none. */
    val onMyReminder: (Int?) -> Unit = {},
    /** «Как в событии». */
    val onAsEvent: () -> Unit = {},
)

@Composable
fun EventRoute(
    id: String,
    occurrence: LocalDate?,
    onBack: () -> Unit,
    onEdit: (EventEditorArgs) -> Unit,
    onTag: (String) -> Unit = {},
    viewModel: EventViewModel = koinViewModel(key = "event-$id-$occurrence") { parametersOf(EventArgs(id, occurrence)) },
    notices: UiNotices = koinInject(),
    permissions: NotificationPermissions = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // A reminder needs notifications: ask when one is chosen while they are off (as in the task editor).
    var askNotifications by rememberSaveable { mutableStateOf(false) }
    val requestPermission = rememberNotificationPermissionRequest { granted -> if (!granted) permissions.openNotificationSettings() }
    // Deleted here (whole event) or on another phone.
    LaunchedEffect(state.gone) { if (state.gone) onBack() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is EventEvent.Close -> {
                    event.message?.let(notices::post)
                    onBack()
                }

                is EventEvent.Failed -> {
                    snackbar.showSnackbar(getString(event.error.messageRes()))
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        EventScreen(
            state,
            EventActions(
                onBack = onBack,
                onEdit = { scope ->
                    val occ = state.detail?.occ?.occurrence
                    onEdit(EventEditorArgs(id = id, occurrence = occ.takeIf { scope != RepeatScope.ALL || state.asksScope }, scope = scope))
                },
                onDelete = viewModel::delete,
                onTag = onTag,
                onMyReminder = { minutes ->
                    viewModel.setMyReminder(minutes)
                    if (minutes != null && !permissions.notificationsAllowed()) askNotifications = true
                },
                onAsEvent = {
                    viewModel.clearMyReminder()
                    if (state.detail?.eventReminder != null && !permissions.notificationsAllowed()) askNotifications = true
                },
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
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
}

private enum class PageDialog { EDIT_SCOPE, DELETE_SCOPE, DELETE_CONFIRM, REMINDER }

/** The event page (CAL-014): title, the changed-occurrence note, facts, «Напомнить мне»; edit and delete for who may. */
@Composable
fun EventScreen(
    state: EventUiState,
    actions: EventActions = EventActions(),
) {
    val detail = state.detail
    var dialog by rememberSaveable { mutableStateOf<PageDialog?>(null) }
    AppPage(
        topBar = {
            TopBar(
                detail,
                onBack = actions.onBack,
                onEdit = { if (state.asksScope) dialog = PageDialog.EDIT_SCOPE else actions.onEdit(RepeatScope.ALL) },
                onDelete = { dialog = if (state.asksScope) PageDialog.DELETE_SCOPE else PageDialog.DELETE_CONFIRM },
            )
        },
    ) {
        if (detail == null) return@AppPage
        Text(
            detail.occ?.title ?: detail.event.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("event_title"),
        )
        if (detail.occ?.changed == true) ChangedNote()
        Facts(state, detail, actions, onReminder = { dialog = PageDialog.REMINDER })
    }
    if (detail == null) return
    val close = { dialog = null }
    when (dialog) {
        PageDialog.EDIT_SCOPE -> {
            ScopeDialog(stringResource(Res.string.cal_scope_edit_title), stringResource(Res.string.action_continue), {
                close()
                actions.onEdit(it)
            }, close)
        }

        PageDialog.DELETE_SCOPE -> {
            ScopeDialog(stringResource(Res.string.cal_scope_delete_title), stringResource(Res.string.action_delete), {
                close()
                actions.onDelete(it)
            }, close, destructive = true)
        }

        PageDialog.DELETE_CONFIRM -> {
            ConfirmDialog(
                title = stringResource(Res.string.cal_delete_title, detail.occ?.title ?: detail.event.title),
                text = stringResource(Res.string.cal_delete_text),
                confirmLabel = stringResource(Res.string.action_delete),
                onConfirm = {
                    close()
                    actions.onDelete(RepeatScope.ALL)
                },
                onDismiss = close,
                destructive = true,
            )
        }

        PageDialog.REMINDER -> {
            MyReminderSheet(detail, actions, close)
        }

        null -> {}
    }
}

@Composable
private fun TopBar(
    detail: EventDetail?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    DetailTopBar("", onBack) {
        if (detail?.canEdit != true) return@DetailTopBar
        IconButton(onClick = onEdit, modifier = Modifier.testTag("event_edit")) {
            AppIcon(Res.drawable.ic_edit, stringResource(Res.string.task_action_edit))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("event_more")) {
                AppIcon(Res.drawable.ic_more_vert, stringResource(Res.string.task_action_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_delete), style = MaterialTheme.typography.bodyLarge) },
                    leadingIcon = { AppIcon(Res.drawable.ic_delete, null) },
                    onClick = {
                        menu = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("event_delete"),
                )
            }
        }
    }
}

@Composable
private fun ChangedNote() {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.secondaryContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = Spacing.l, vertical = Spacing.m)
            .testTag("event_changed_note"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(Res.drawable.ic_info, null, size = 20.dp, tint = colors.onSecondaryContainer)
        Text(
            stringResource(Res.string.cal_event_changed_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSecondaryContainer,
        )
    }
}

@Composable
private fun Facts(
    state: EventUiState,
    detail: EventDetail,
    actions: EventActions,
    onReminder: () -> Unit,
) {
    val event = detail.event
    val occ = detail.occ
    val memorable = event.kind == EventKind.MEMORABLE_DATE
    val rows = mutableListOf<@Composable () -> Unit>()
    if (memorable) {
        val date = occ?.startDate ?: event.startDate
        rows += {
            ListRow(
                CalendarLogic.memorableWhen(date).text(),
                icon = Res.drawable.ic_calendar_month,
                supporting = detail.years?.let { CalendarLogic.years(it).text() },
                modifier = Modifier.testTag("event_when"),
            )
        }
    } else if (occ != null) {
        rows +=
            {
                ListRow(
                    CalendarLogic.detailWhen(occ, state.today).text(),
                    icon = Res.drawable.ic_calendar_month,
                    modifier = Modifier.testTag("event_when"),
                )
            }
        detail.rule?.let { rule ->
            rows += {
                ListRow(
                    CalendarLogic.rule(rule, event.startDate, event.repeatUntil, state.today).text(),
                    icon = Res.drawable.ic_repeat,
                    modifier = Modifier.testTag("event_rule"),
                )
            }
        }
    }
    (occ?.place ?: event.place)?.takeIf { it.isNotBlank() }?.let { place ->
        rows += { ListRow(place.trim(), icon = Res.drawable.ic_location_on, modifier = Modifier.testTag("event_place")) }
    }
    rows += {
        val names = detail.participants.joinToString { it.data.name }.ifEmpty { stringResource(Res.string.cal_whole_family) }
        ListRow(
            names,
            icon = Res.drawable.ic_group,
            modifier = Modifier.testTag("event_participants"),
            trailing = { PersonAvatars(detail.participants, max = 4) },
        )
    }
    if (detail.tags.isNotEmpty()) {
        rows += {
            FactRow(Res.drawable.ic_sell) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    detail.tags.forEach { tag ->
                        TagChip(
                            tag,
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { actions.onTag(tag.id) }
                                .testTag("event_tag_${tag.id}"),
                        )
                    }
                }
            }
        }
    }
    (occ?.notes ?: event.notes)?.takeIf { it.isNotBlank() }?.let { notes ->
        rows += { ListRow(notes.trim(), icon = Res.drawable.ic_notes, modifier = Modifier.testTag("event_notes")) }
    }
    rows += {
        if (memorable) {
            ListRow(
                stringResource(Res.string.cal_reminder_title),
                icon = Res.drawable.ic_alarm,
                supporting = stringResource(Res.string.cal_reminder_memorable),
                modifier = Modifier.testTag("event_reminder"),
            )
        } else {
            val allDay = occ?.allDay ?: event.allDay
            ListRow(
                stringResource(Res.string.cal_remind_me),
                icon = Res.drawable.ic_alarm,
                supporting = CalendarLogic.myReminder(detail.myReminder, allDay, own = detail.ownReminderSet).text(),
                onClick = onReminder,
                showChevron = true,
                modifier = Modifier.testTag("event_reminder"),
            )
        }
    }
    detail.creator?.let { creator ->
        rows += { ListRow(stringResource(Res.string.cal_created_by, creator.name), icon = Res.drawable.ic_person) }
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

/** «Как в событии» (when the event reminds me), «Нет» and the editor's options; a choice applies and closes. */
@Composable
private fun MyReminderSheet(
    detail: EventDetail,
    actions: EventActions,
    onDismiss: () -> Unit,
) {
    val allDay = detail.occ?.allDay ?: detail.event.allDay
    val options = CalendarLogic.reminderOptions(allDay)
    val own = detail.ownReminderSet || detail.eventReminder == null
    val choices: List<Int?> = listOf(null) + options + listOfNotNull(detail.myReminder?.takeIf { own && it !in options })
    EditorSheet(stringResource(Res.string.cal_remind_me), onDismiss) {
        ListGroup {
            if (detail.eventReminder != null) {
                ListRow(
                    stringResource(Res.string.cal_reminder_as_event),
                    supporting = CalendarLogic.reminder(detail.eventReminder, allDay).text(),
                    modifier = Modifier.testTag("my_reminder_event"),
                    onClick = {
                        actions.onAsEvent()
                        onDismiss()
                    },
                    trailing = { RadioButton(selected = !detail.ownReminderSet, onClick = null) },
                )
                ListDivider(inset = Spacing.l)
            }
            choices.forEachIndexed { index, minutes ->
                if (index > 0) ListDivider(inset = Spacing.l)
                ListRow(
                    CalendarLogic.reminder(minutes, allDay).text(),
                    modifier = Modifier.testTag("my_reminder_${minutes ?: "none"}"),
                    onClick = {
                        actions.onMyReminder(minutes)
                        onDismiss()
                    },
                    trailing = { RadioButton(selected = own && detail.myReminder == minutes, onClick = null) },
                )
            }
        }
    }
}
