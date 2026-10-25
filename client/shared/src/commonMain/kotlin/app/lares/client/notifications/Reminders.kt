package app.lares.client.notifications

import app.lares.client.data.calendar.CalendarReminderPlanner
import app.lares.client.data.tasks.TaskReminderPlanner
import app.lares.client.db.LocalDb
import app.lares.contract.api.notifications.NotificationChannel
import app.lares.contract.sync.EntityTypes
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Local time-based reminders (FND-050): they fire on the device without network. Modules (tasks, calendar)
 * schedule them with a stable [id], so scheduling again replaces the previous reminder.
 */
interface ReminderScheduler {
    suspend fun schedule(
        id: String,
        at: Instant,
        title: String,
        body: String,
        channel: NotificationChannel = NotificationChannel.REMINDERS,
    )

    suspend fun cancel(id: String)

    /** Registers every stored reminder with the OS again (after a reboot or an app update). */
    suspend fun rescheduleAll()
}

/**
 * [ReminderScheduler] that keeps reminders in the local `reminder` table and sets an OS alarm per reminder.
 * The alarm only carries the id; [fire] reads the rest, so a reminder deleted meanwhile (cancel, logout) shows nothing.
 */
class Reminders(
    private val localDb: LocalDb,
    private val alarms: AlarmScheduler,
    private val poster: NotificationPoster,
    private val clock: Clock,
) : ReminderScheduler {
    override suspend fun schedule(
        id: String,
        at: Instant,
        title: String,
        body: String,
        channel: NotificationChannel,
    ) {
        localDb.transaction { reminderQueries.put(id, at.toEpochMilliseconds(), title, body, channel.id) }
        alarms.set(id, at)
    }

    override suspend fun cancel(id: String) {
        localDb.transaction { reminderQueries.remove(id) }
        alarms.cancel(id)
    }

    /** Reminders missed while the phone was off fire right away. */
    override suspend fun rescheduleAll() {
        val now = clock.now()
        localDb.read { reminderQueries.all().executeAsList() }.forEach { row ->
            alarms.set(row.id, maxOf(Instant.fromEpochMilliseconds(row.at), now))
        }
    }

    /** Called by the OS alarm: shows the reminder once and forgets it. Returns false if it no longer exists. */
    suspend fun fire(id: String): Boolean {
        val row =
            localDb.transaction {
                reminderQueries.get(id).executeAsOneOrNull()?.also { reminderQueries.remove(id) }
            } ?: return false
        val channel = NotificationChannel.entries.firstOrNull { it.id == row.channel } ?: NotificationChannel.REMINDERS
        poster.show("reminder:$id", channel, row.title, row.body, target(id))
        return true
    }

    /**
     * Task reminders (`task:<id>`) open their task; event reminders (`event:<eventId>:<occurrence>`) the occurrence;
     * birthday reminders (`birthday:<personId>:<day>`) the person; memorable-date reminders (`memorable:<eventId>:<day>`)
     * the event; other reminders open the app.
     */
    private fun target(id: String): NotificationTarget? =
        when {
            id.startsWith(TaskReminderPlanner.ID_PREFIX) -> {
                NotificationTarget(EntityTypes.TASK, id.removePrefix(TaskReminderPlanner.ID_PREFIX))
            }

            id.startsWith(CalendarReminderPlanner.EVENT_PREFIX) -> {
                NotificationTarget(EntityTypes.EVENT, id.removePrefix(CalendarReminderPlanner.EVENT_PREFIX))
            }

            id.startsWith(CalendarReminderPlanner.BIRTHDAY_PREFIX) -> {
                NotificationTarget(EntityTypes.PERSON, id.removePrefix(CalendarReminderPlanner.BIRTHDAY_PREFIX).substringBefore(':'))
            }

            id.startsWith(CalendarReminderPlanner.MEMORABLE_PREFIX) -> {
                NotificationTarget(EntityTypes.EVENT, id.removePrefix(CalendarReminderPlanner.MEMORABLE_PREFIX).substringBefore(':'))
            }

            else -> {
                null
            }
        }
}
