package app.familyhub.client.notifications

import app.familyhub.client.db.LocalDb
import app.familyhub.contract.api.notifications.NotificationChannel
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
        poster.show("reminder:$id", channel, row.title, row.body)
        return true
    }
}
