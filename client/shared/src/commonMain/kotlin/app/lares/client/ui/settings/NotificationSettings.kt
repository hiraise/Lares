package app.lares.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.lares.client.notifications.NotificationPermissions
import app.lares.client.notifications.NotificationSettingsRepository
import app.lares.client.notifications.PushDistributors
import app.lares.client.notifications.PushStatus
import app.lares.client.notifications.ReminderScheduler
import app.lares.client.platform.PreferencesStore
import app.lares.client.resources.Res
import app.lares.client.resources.ic_notifications
import app.lares.client.resources.ic_schedule
import app.lares.client.resources.ic_schedule_send
import app.lares.client.resources.notif_allow
import app.lares.client.resources.notif_allowed
import app.lares.client.resources.notif_birthday_days
import app.lares.client.resources.notif_birthday_off
import app.lares.client.resources.notif_birthday_on_day
import app.lares.client.resources.notif_birthday_two_weeks
import app.lares.client.resources.notif_birthday_week
import app.lares.client.resources.notif_choose
import app.lares.client.resources.notif_connected_via
import app.lares.client.resources.notif_device
import app.lares.client.resources.notif_diagnostics
import app.lares.client.resources.notif_exact
import app.lares.client.resources.notif_exact_off
import app.lares.client.resources.notif_failed
import app.lares.client.resources.notif_help
import app.lares.client.resources.notif_instant
import app.lares.client.resources.notif_not_set_up
import app.lares.client.resources.notif_permission
import app.lares.client.resources.notif_permission_off
import app.lares.client.resources.notif_prompt_later
import app.lares.client.resources.notif_prompt_text
import app.lares.client.resources.notif_prompt_title
import app.lares.client.resources.notif_send_test
import app.lares.client.resources.notif_test_expired
import app.lares.client.resources.notif_test_failed
import app.lares.client.resources.notif_test_muted
import app.lares.client.resources.notif_test_not_registered
import app.lares.client.resources.notif_test_reminder
import app.lares.client.resources.notif_test_reminder_body
import app.lares.client.resources.notif_test_reminder_set
import app.lares.client.resources.notif_test_reminder_title
import app.lares.client.resources.notif_test_sent
import app.lares.client.resources.notif_type_birthday_reminder
import app.lares.client.resources.notif_type_event_changed
import app.lares.client.resources.notif_type_event_reminder
import app.lares.client.resources.notif_type_system_test
import app.lares.client.resources.notif_type_task_assigned
import app.lares.client.resources.notif_type_task_completed
import app.lares.client.resources.notif_type_task_overdue
import app.lares.client.resources.notif_type_task_reminder
import app.lares.client.resources.notif_types
import app.lares.client.resources.notif_use
import app.lares.client.resources.settings_notifications
import app.lares.client.ui.common.UiError
import app.lares.client.ui.common.attempt
import app.lares.client.ui.common.rememberNotificationPermissionRequest
import app.lares.client.ui.common.toUiError
import app.lares.client.ui.components.AppPage
import app.lares.client.ui.components.ChoiceDialog
import app.lares.client.ui.components.ConfirmDialog
import app.lares.client.ui.components.DetailTopBar
import app.lares.client.ui.components.ErrorText
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.SectionHeader
import app.lares.contract.api.notifications.NotificationChannel
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.api.notifications.PushTestResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Notification types with a switch (FND-052, TSK-034); later modules add theirs. */
val SwitchableNotificationTypes: List<NotificationType> =
    listOf(
        NotificationType.TASK_ASSIGNED,
        NotificationType.TASK_COMPLETED,
        NotificationType.TASK_OVERDUE,
        NotificationType.TASK_REMINDER,
        NotificationType.EVENT_CHANGED,
        NotificationType.EVENT_REMINDER,
        NotificationType.SYSTEM_TEST,
    )

/** The «Дни рождения» choices (CAL-016): null = «Не напоминать» (mutes `birthday.reminder`), else days before. */
val BirthdayChoices: List<Int?> = listOf(null) + NotificationSettings.BIRTHDAY_DAYS_CHOICES

data class NotificationSettingsUiState(
    val push: PushStatus = PushStatus(),
    val muted: Set<NotificationType> = emptySet(),
    val birthdayDaysBefore: Int = NotificationSettings.DEFAULT_BIRTHDAY_DAYS_BEFORE,
    val needsRuntimePermission: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    val sendingTest: Boolean = false,
    val testResult: PushTestResult? = null,
    /** When the diagnostics reminder fires; null until one is set. */
    val testReminderAt: Instant? = null,
    val error: UiError? = null,
) {
    /** The selected birthday choice: null when birthday reminders are muted. */
    val birthdayChoice: Int? get() = if (NotificationType.BIRTHDAY_REMINDER in muted) null else birthdayDaysBefore
}

/** Settings › Notifications (FND-050…052): push status, test push, muting, device permissions, test reminder. */
class NotificationSettingsViewModel(
    private val settings: NotificationSettingsRepository,
    private val distributors: PushDistributors,
    private val permissions: NotificationPermissions,
    private val reminders: ReminderScheduler,
    private val clock: Clock,
) : ViewModel() {
    private val local = MutableStateFlow(NotificationSettingsUiState())

    val state: StateFlow<NotificationSettingsUiState> =
        combine(local, distributors.status, settings.muted, settings.birthdayDaysBefore) { state, push, muted, days ->
            state.copy(push = push, muted = muted, birthdayDaysBefore = days)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationSettingsUiState())

    init {
        viewModelScope.launch {
            attempt { settings.refresh() }.exceptionOrNull()?.let { e -> local.update { it.copy(error = e.toUiError()) } }
        }
    }

    /** The user may have changed permissions or installed ntfy while away from the screen. */
    fun onResume() {
        distributors.refresh()
        local.update {
            it.copy(
                needsRuntimePermission = permissions.needsRuntimePermission,
                notificationsAllowed = permissions.notificationsAllowed(),
                exactAlarmsAllowed = permissions.exactAlarmsAllowed(),
            )
        }
    }

    fun selectDistributor(id: String) = distributors.select(id)

    fun openExactAlarmSettings() = permissions.openExactAlarmSettings()

    fun sendTest() {
        local.update { it.copy(sendingTest = true, testResult = null, error = null) }
        viewModelScope.launch {
            val result = attempt { settings.sendTest() }
            local.update { state ->
                result.fold(
                    onSuccess = { state.copy(sendingTest = false, testResult = it) },
                    onFailure = { state.copy(sendingTest = false, error = it.toUiError()) },
                )
            }
        }
    }

    fun setEnabled(
        type: NotificationType,
        enabled: Boolean,
    ) {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            attempt { settings.setMuted(type, muted = !enabled) }.exceptionOrNull()?.let { e ->
                local.update { it.copy(error = e.toUiError()) }
            }
        }
    }

    /** «Дни рождения»: null mutes birthday reminders, days unmute them and store the lead time (CAL-016). */
    fun setBirthdayChoice(days: Int?) {
        local.update { it.copy(error = null) }
        viewModelScope.launch {
            attempt {
                if (days ==
                    null
                ) {
                    settings.setMuted(NotificationType.BIRTHDAY_REMINDER, muted = true)
                } else {
                    settings.setBirthdayDaysBefore(days)
                }
            }.exceptionOrNull()?.let { e -> local.update { it.copy(error = e.toUiError()) } }
        }
    }

    /** Diagnostics: a local reminder one minute from now ([title] and [body] in the UI language). */
    fun scheduleTestReminder(
        title: String,
        body: String,
    ) {
        val at = clock.now() + 1.minutes
        viewModelScope.launch {
            // The reminders channel, so the test behaves like real reminders (sound, heads-up).
            reminders.schedule(TEST_REMINDER_ID, at, title, body, NotificationChannel.REMINDERS)
            local.update { it.copy(testReminderAt = at) }
            // Once it has fired, the "set for" line would point to the past.
            delay(at - clock.now())
            local.update { if (it.testReminderAt == at) it.copy(testReminderAt = null) else it }
        }
    }

    companion object {
        const val TEST_REMINDER_ID = "diagnostics.test"
    }
}

@Composable
fun NotificationSettingsRoute(
    onBack: () -> Unit,
    viewModel: NotificationSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    val requestPermission = rememberNotificationPermissionRequest { viewModel.onResume() }
    val reminderTitle = stringResource(Res.string.notif_test_reminder_title)
    val reminderBody = stringResource(Res.string.notif_test_reminder_body)
    var birthdayDialog by rememberSaveable { mutableStateOf(false) }
    if (birthdayDialog) {
        ChoiceDialog(
            title = stringResource(Res.string.notif_type_birthday_reminder),
            options = BirthdayChoices,
            selected = state.birthdayChoice,
            label = { birthdayChoiceLabel(it) },
            onSelect = {
                birthdayDialog = false
                viewModel.setBirthdayChoice(it)
            },
            onDismiss = { birthdayDialog = false },
        )
    }
    AppPage(topBar = { DetailTopBar(stringResource(Res.string.settings_notifications), onBack) }) {
        ErrorText(state.error)
        InstantNotifications(state, viewModel::selectDistributor, viewModel::sendTest)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.notif_types))
            ListGroup {
                SwitchableNotificationTypes.forEachIndexed { index, type ->
                    if (type == NotificationType.SYSTEM_TEST) {
                        // «Дни рождения» goes with the calendar switches, before the test notifications.
                        ListDivider(inset = 16.dp)
                        ListRow(
                            stringResource(Res.string.notif_type_birthday_reminder),
                            modifier = Modifier.testTag("notif_birthdays"),
                            supporting = birthdayChoiceLabel(state.birthdayChoice),
                            showChevron = true,
                            onClick = { birthdayDialog = true },
                        )
                    }
                    if (index > 0) ListDivider(inset = 16.dp)
                    ListRow(
                        stringResource(type.label()),
                        onClick = { viewModel.setEnabled(type, type in state.muted) },
                        trailing = {
                            Switch(checked = type !in state.muted, onCheckedChange = { viewModel.setEnabled(type, it) })
                        },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.notif_device))
            ListGroup {
                if (state.needsRuntimePermission) {
                    PermissionRow(
                        stringResource(Res.string.notif_permission),
                        allowed = state.notificationsAllowed,
                        offText = stringResource(Res.string.notif_permission_off),
                        onAllow = requestPermission,
                    )
                    ListDivider(inset = 16.dp)
                }
                PermissionRow(
                    stringResource(Res.string.notif_exact),
                    allowed = state.exactAlarmsAllowed,
                    offText = stringResource(Res.string.notif_exact_off),
                    onAllow = viewModel::openExactAlarmSettings,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(stringResource(Res.string.notif_diagnostics))
            ListGroup {
                ListRow(
                    stringResource(Res.string.notif_test_reminder),
                    icon = Res.drawable.ic_schedule,
                    supporting = state.testReminderAt?.let { stringResource(Res.string.notif_test_reminder_set, it.localTime()) },
                    onClick = { viewModel.scheduleTestReminder(reminderTitle, reminderBody) },
                )
            }
        }
    }
}

@Composable
private fun InstantNotifications(
    state: NotificationSettingsUiState,
    onSelect: (String) -> Unit,
    onSendTest: () -> Unit,
) {
    val push = state.push
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(Res.string.notif_instant))
        ListGroup {
            val connected = push.connected
            ListRow(
                if (connected != null) {
                    stringResource(Res.string.notif_connected_via, connected.label)
                } else {
                    stringResource(Res.string.notif_not_set_up)
                },
                icon = Res.drawable.ic_notifications,
                supporting =
                    when {
                        connected != null && push.failed -> stringResource(Res.string.notif_failed, connected.label)
                        connected == null && push.available.size > 1 -> stringResource(Res.string.notif_choose)
                        connected == null -> stringResource(Res.string.notif_help)
                        else -> null
                    },
            )
            if (connected == null) {
                push.available.forEach { distributor ->
                    ListDivider()
                    ListRow(stringResource(Res.string.notif_use, distributor.label), onClick = { onSelect(distributor.id) })
                }
            } else {
                ListDivider()
                ListRow(
                    stringResource(Res.string.notif_send_test),
                    icon = Res.drawable.ic_schedule_send,
                    supporting = state.testResult?.let { stringResource(it.message()) },
                    enabled = !state.sendingTest,
                    onClick = onSendTest,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    allowed: Boolean,
    offText: String,
    onAllow: () -> Unit,
) {
    ListRow(
        title,
        supporting = if (allowed) stringResource(Res.string.notif_allowed) else offText,
        trailing =
            if (allowed) {
                null
            } else {
                { LinkButton(stringResource(Res.string.notif_allow), onAllow) }
            },
    )
}

/** Whether to show the post-login notification permission explanation: Android 13+, not granted, not shown before. */
class NotificationPromptViewModel(
    private val permissions: NotificationPermissions,
    private val preferences: PreferencesStore,
) : ViewModel() {
    val show: StateFlow<Boolean> =
        preferences.preferences
            .map { !it.notificationPromptShown && permissions.needsRuntimePermission && !permissions.notificationsAllowed() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun done() {
        viewModelScope.launch { preferences.setNotificationPromptShown() }
    }
}

/** Shown on the main screen after login (plan M0-09: not at first launch). */
@Composable
fun NotificationPermissionPromptHost(viewModel: NotificationPromptViewModel = koinViewModel()) {
    val show by viewModel.show.collectAsStateWithLifecycle()
    val request = rememberNotificationPermissionRequest { viewModel.done() }
    if (show) {
        NotificationPermissionPrompt(
            onAllow = {
                viewModel.done()
                request()
            },
            onLater = viewModel::done,
        )
    }
}

/** Post-login explanation before the system asks for the notification permission (Android 13+), shown once. */
@Composable
fun NotificationPermissionPrompt(
    onAllow: () -> Unit,
    onLater: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(Res.string.notif_prompt_title),
        text = stringResource(Res.string.notif_prompt_text),
        confirmLabel = stringResource(Res.string.notif_allow),
        onConfirm = onAllow,
        onDismiss = onLater,
        dismissLabel = stringResource(Res.string.notif_prompt_later),
    )
}

/** «Не напоминать», «Только в день», «За 1 день», «За 3 дня», «За неделю», «За 2 недели». */
@Composable
private fun birthdayChoiceLabel(days: Int?): String =
    when (days) {
        null -> stringResource(Res.string.notif_birthday_off)
        0 -> stringResource(Res.string.notif_birthday_on_day)
        7 -> stringResource(Res.string.notif_birthday_week)
        14 -> stringResource(Res.string.notif_birthday_two_weeks)
        else -> pluralStringResource(Res.plurals.notif_birthday_days, days, days)
    }

private fun NotificationType.label(): StringResource =
    when (this) {
        NotificationType.SYSTEM_TEST -> Res.string.notif_type_system_test
        NotificationType.TASK_ASSIGNED -> Res.string.notif_type_task_assigned
        NotificationType.TASK_OVERDUE -> Res.string.notif_type_task_overdue
        NotificationType.TASK_COMPLETED -> Res.string.notif_type_task_completed
        NotificationType.TASK_REMINDER -> Res.string.notif_type_task_reminder
        NotificationType.EVENT_CHANGED -> Res.string.notif_type_event_changed
        NotificationType.EVENT_REMINDER -> Res.string.notif_type_event_reminder
        NotificationType.BIRTHDAY_REMINDER -> Res.string.notif_type_birthday_reminder
    }

private fun PushTestResult.message(): StringResource =
    when (this) {
        PushTestResult.SENT -> Res.string.notif_test_sent
        PushTestResult.MUTED -> Res.string.notif_test_muted
        PushTestResult.NOT_REGISTERED -> Res.string.notif_test_not_registered
        PushTestResult.EXPIRED -> Res.string.notif_test_expired
        PushTestResult.FAILED -> Res.string.notif_test_failed
    }

private fun Instant.localTime(): String {
    val time = toLocalDateTime(TimeZone.currentSystemDefault())
    return "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}
