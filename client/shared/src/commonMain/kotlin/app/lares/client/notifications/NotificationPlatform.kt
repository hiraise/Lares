package app.lares.client.notifications

import app.lares.contract.api.notifications.NotificationChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

// What the notification core needs from the platform (plan M0-09). Android implements these in the app module;
// the JVM build and tests use the no-op versions below.

/**
 * What a notification tap opens: an entity such as a task (`entityType` from `EntityTypes`). Null opens the app as
 * it is.
 */
data class NotificationTarget(
    val entityType: String,
    val entityId: String,
)

/**
 * Shows notifications. [id] identifies a notification, so showing the same id again replaces it; a tap opens
 * [target] (plan M1-07).
 */
fun interface NotificationPoster {
    fun show(
        id: String,
        channel: NotificationChannel,
        title: String,
        body: String,
        target: NotificationTarget?,
    )
}

/** The OS alarm behind [Reminders]: wakes the app at [at] to fire reminder [id]. */
interface AlarmScheduler {
    fun set(
        id: String,
        at: Instant,
    )

    fun cancel(id: String)
}

/** Background sync (WorkManager on Android): periodic work while logged in, and a one-time run on push tickles. */
interface BackgroundSync {
    fun schedulePeriodic()

    fun cancelAll()

    fun requestNow()
}

/** An installed UnifiedPush distributor app, e.g. ntfy. */
data class PushDistributor(
    val id: String,
    val label: String,
)

/** UnifiedPush state shown in Settings › Notifications. */
data class PushStatus(
    /** The distributor this app is registered with, `null` if none. */
    val connected: PushDistributor? = null,
    /** Installed distributors; the user picks one when there are several and none is connected. */
    val available: List<PushDistributor> = emptyList(),
    /** The distributor refused the registration (e.g. ntfy needs the user's attention). */
    val failed: Boolean = false,
)

/** UnifiedPush registration (Android connector); endpoints arrive in [PushEndpoints.onNewEndpoint]. */
interface PushDistributors {
    val status: StateFlow<PushStatus>

    /** Registers with the connected distributor, or with the default one if exactly one is installed. */
    fun register()

    /** Uses [id] from now on and registers with it. */
    fun select(id: String)

    fun unregister()

    /** Re-reads installed distributors (after the user returns from installing one). */
    fun refresh()
}

/** Device permissions that decide whether notifications and exact reminders work. */
interface NotificationPermissions {
    /** Android 13+ asks for permission to post notifications. */
    val needsRuntimePermission: Boolean

    fun notificationsAllowed(): Boolean

    /** Exact alarms (Android 12+ needs a special permission); without it reminders may be late by a few minutes. */
    fun exactAlarmsAllowed(): Boolean

    /** Opens the system screen where the user allows exact alarms. */
    fun openExactAlarmSettings()
}

/** The language notification texts from the server should use (`en`, `ru`): the app's UI language. */
fun interface DeviceLanguage {
    suspend fun current(): String
}

class NoopPushDistributors : PushDistributors {
    private val state = MutableStateFlow(PushStatus())
    override val status: StateFlow<PushStatus> = state.asStateFlow()

    override fun register() = Unit

    override fun select(id: String) = Unit

    override fun unregister() = Unit

    override fun refresh() = Unit
}

class NoopBackgroundSync : BackgroundSync {
    override fun schedulePeriodic() = Unit

    override fun cancelAll() = Unit

    override fun requestNow() = Unit
}

class NoopAlarmScheduler : AlarmScheduler {
    override fun set(
        id: String,
        at: Instant,
    ) = Unit

    override fun cancel(id: String) = Unit
}

class AllowAllNotificationPermissions : NotificationPermissions {
    override val needsRuntimePermission: Boolean = false

    override fun notificationsAllowed(): Boolean = true

    override fun exactAlarmsAllowed(): Boolean = true

    override fun openExactAlarmSettings() = Unit
}
