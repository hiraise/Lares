package app.lares.client.notifications

import app.lares.client.auth.SessionManager
import app.lares.client.auth.SessionState
import app.lares.client.data.calendar.CalendarReminderPlanner
import app.lares.client.data.tasks.TaskReminderPlanner
import app.lares.client.platform.PreferencesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The UI language chosen in the app, or the system language when the app follows the system. */
class PreferredLanguage(
    private val preferences: PreferencesStore,
    private val systemLanguage: () -> String,
) : DeviceLanguage {
    override suspend fun current(): String =
        preferences.preferences
            .first()
            .language.tag ?: systemLanguage()
}

/**
 * Ties notifications to the session (plan M0-09): after login (and at every app start while logged in) it schedules
 * periodic background sync, registers with UnifiedPush, uploads the push endpoint, loads mute settings and
 * re-registers reminders, and keeps task and calendar reminders in step with their data (M1-04, M3-04); on logout it
 * cancels background work, stops the reminder planners and unregisters.
 */
class NotificationsCoordinator(
    private val sessionManager: SessionManager,
    private val distributors: PushDistributors,
    private val backgroundSync: BackgroundSync,
    private val reminders: ReminderScheduler,
    private val endpoints: PushEndpoints,
    private val settings: NotificationSettingsRepository,
    private val preferences: PreferencesStore,
    private val taskReminders: TaskReminderPlanner,
    private val calendarReminders: CalendarReminderPlanner,
    private val scope: CoroutineScope,
) {
    private var taskRemindersJob: Job? = null
    private var calendarRemindersJob: Job? = null

    fun start() {
        scope.launch {
            var loggedIn = false
            sessionManager.state.collect { state ->
                val now = state is SessionState.LoggedIn
                if (now && !loggedIn) onLogin()
                if (!now && loggedIn) onLogout()
                loggedIn = now
            }
        }
        scope.launch {
            // The server renders notification texts in the UI language, so it must learn about a change.
            preferences.preferences.map { it.language }.distinctUntilChanged().drop(1).collect {
                if (sessionManager.state.value is SessionState.LoggedIn) endpoints.uploadIfNeeded()
            }
        }
    }

    private fun onLogin() {
        backgroundSync.schedulePeriodic()
        distributors.register()
        scope.launch {
            endpoints.uploadIfNeeded()
            try {
                settings.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: the local mirror stays as it is.
            }
            reminders.rescheduleAll()
        }
        taskRemindersJob?.cancel()
        taskRemindersJob = taskReminders.start(scope)
        calendarRemindersJob?.cancel()
        calendarRemindersJob = calendarReminders.start(scope)
    }

    private fun onLogout() {
        taskRemindersJob?.cancel()
        taskRemindersJob = null
        calendarRemindersJob?.cancel()
        calendarRemindersJob = null
        backgroundSync.cancelAll()
        distributors.unregister()
    }
}
