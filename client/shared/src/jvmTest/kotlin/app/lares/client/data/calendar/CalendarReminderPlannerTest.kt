package app.lares.client.data.calendar

import app.lares.client.notifications.AlarmScheduler
import app.lares.client.notifications.NotificationSettingsRepository
import app.lares.client.notifications.Reminders
import app.lares.contract.Ids
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventReminderData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Now: Wed 2026-10-21 13:00 in Moscow (10:00 UTC); 09:00 Moscow = 06:00 UTC. */
class CalendarReminderPlannerTest {
    private class RecordingAlarms : AlarmScheduler {
        val set = mutableMapOf<String, Instant>()

        override fun set(
            id: String,
            at: Instant,
        ) {
            set[id] = at
        }

        override fun cancel(id: String) {
            set.remove(id)
        }
    }

    private val f = CalendarFixture()
    private val alarms = RecordingAlarms()
    private val reminders = Reminders(f.localDb, alarms, { _, _, _, _, _ -> }, f.clock)
    private val planner =
        CalendarReminderPlanner(
            f.repository,
            f.localDb,
            reminders,
            NotificationSettingsRepository(f.api, f.localDb),
            EnglishCalendarReminderTexts(),
            f.clock,
        )

    private fun d(
        day: Int,
        month: Int = 10,
    ) = LocalDate(2026, month, day)

    private val e = Ids.newId()

    // Thu 22 Oct 17:00 Moscow, reminder 30 minutes before = 13:30 UTC.
    private fun dentist(participants: Set<String> = setOf(P_ANYA)) =
        timedEvent(
            "Стоматолог",
            d(22),
            LocalTime(17, 0),
            LocalTime(18, 0),
            participants,
        ).copy(reminderMinutesBefore = 30, place = "Клиника")

    private val eventAt = Instant.parse("2026-10-22T13:30:00Z")

    private suspend fun setUp(login: String) {
        f.family()
        f.loginAs(login, *CAL_ADULT)
    }

    private suspend fun mute(type: NotificationType) = f.localDb.transaction { notification_muteQueries.put(type.key) }

    private suspend fun daysBefore(days: Int) =
        f.localDb.transaction { notification_prefQueries.put("birthday_days_before", days.toString()) }

    private fun ids(prefix: String) =
        alarms.set.keys
            .filter { it.startsWith(prefix) }
            .sorted()

    private suspend fun row(id: String) = f.localDb.read { reminderQueries.get(id).executeAsOne() }

    @Test
    fun `CAL-006 a participant gets the event reminder, others only with their own row`() =
        runTest {
            setUp(ANYA)
            f.putEvent(e, dentist())
            planner.reconcile()
            val id = "event:$e:2026-10-22"
            assertEquals(mapOf(id to eventAt), alarms.set.filterKeys { it.startsWith("event:") })
            assertEquals("Стоматолог", row(id).title)
            // It fires on the day of the visit.
            assertEquals("Today at 17:00 · Клиника", row(id).body)

            // Her own "no reminder for me" cancels it.
            f.putReminder(EventReminderData(e, ANYA, null))
            planner.reconcile()
            assertEquals(emptyList(), ids("event:"))
        }

    @Test
    fun `CAL-006 a non-participant with an own row and a whole-family event`() =
        runTest {
            setUp(DIMA)
            val family = Ids.newId()
            f.putEvent(e, dentist())
            f.putEvent(family, dentist(emptySet()).copy(title = "Семья"))
            planner.reconcile()
            assertEquals(listOf("event:$family:2026-10-22"), ids("event:"))
            f.putReminder(EventReminderData(e, DIMA, 60))
            planner.reconcile()
            assertEquals(Instant.parse("2026-10-22T13:00:00Z"), alarms.set["event:$e:2026-10-22"])
        }

    @Test
    fun `CAL-006 a 14-day horizon and at most 100 event reminders, nearest first`() =
        runTest {
            setUp(OLGA)
            val daily = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY)
            f.putEvent(e, dentist(emptySet()).copy(recurrence = daily))
            planner.reconcile()
            // 22 Oct … 3 Nov: the reminder on 4 Nov 13:30 UTC is beyond 14 days from 21 Oct 10:00 UTC.
            assertEquals(13, ids("event:").size)
            assertEquals("event:$e:2026-11-03", ids("event:").last())
            repeat(7) { f.putEvent(Ids.newId(), dentist(emptySet()).copy(recurrence = daily)) }
            planner.reconcile()
            assertEquals(CalendarReminderPlanner.MAX_EVENT_REMINDERS, ids("event:").size)
            // 8 × 12 up to 2 Nov, then 4 of the 3 Nov ones.
            assertEquals(4, ids("event:").count { it.endsWith("2026-11-03") })
        }

    @Test
    fun `CAL-016 birthday reminders from three days before at 09-00, none for my own`() =
        runTest {
            setUp(ANYA)
            planner.reconcile()
            // Бабушка Валя on 24 Oct: 21 Oct 09:00 is past; Аня's own birthday on 23 Oct gives nothing.
            assertEquals(
                listOf("birthday:$P_VALYA:2026-10-22", "birthday:$P_VALYA:2026-10-23", "birthday:$P_VALYA:2026-10-24"),
                ids("birthday:"),
            )
            assertEquals(Instant.parse("2026-10-22T06:00:00Z"), alarms.set["birthday:$P_VALYA:2026-10-22"])
            val first = row("birthday:$P_VALYA:2026-10-22")
            assertEquals("Birthday: Бабушка Валя", first.title)
            assertEquals("In 2 days, turns 78", first.body)
            assertEquals("Today, 78 years", row("birthday:$P_VALYA:2026-10-24").body)

            daysBefore(0)
            planner.reconcile()
            assertEquals(listOf("birthday:$P_VALYA:2026-10-24"), ids("birthday:"))
        }

    @Test
    fun `CAL-016 memorable dates follow the birthday setting`() =
        runTest {
            setUp(OLGA)
            f.putEvent(
                e,
                EventData(
                    "Годовщина",
                    OLGA,
                    LocalDate(2010, 10, 25),
                    LocalDate(2010, 10, 25),
                    allDay = true,
                    participantIds = setOf(P_OLGA, P_DIMA),
                    recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR, 1),
                    kind = EventKind.MEMORABLE_DATE,
                    countYears = true,
                ),
            )
            daysBefore(1)
            planner.reconcile()
            assertEquals(listOf("memorable:$e:2026-10-24", "memorable:$e:2026-10-25"), ids("memorable:"))
            assertEquals("Tomorrow · 16 years", row("memorable:$e:2026-10-24").body)
            assertEquals(emptyList(), ids("event:"))
        }

    @Test
    fun `FND-052 muted types cancel only their reminders and task reminders stay`() =
        runTest {
            setUp(ANYA)
            f.putEvent(e, dentist())
            reminders.schedule("task:t1", Instant.parse("2026-10-22T10:00:00Z"), "Мусор", "Due tomorrow")
            planner.reconcile()
            assertEquals(1, ids("event:").size)
            assertEquals(3, ids("birthday:").size)

            mute(NotificationType.BIRTHDAY_REMINDER)
            planner.reconcile()
            assertEquals(1, ids("event:").size)
            assertEquals(emptyList(), ids("birthday:"))

            mute(NotificationType.EVENT_REMINDER)
            planner.reconcile()
            assertEquals(emptyList(), ids("event:"))
            assertEquals(listOf("task:t1"), alarms.set.keys.toList())
        }
}
