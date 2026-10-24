package app.lares.contract.tasks

import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TaskScheduleTest {
    private val moscow = TimeZone.of("Europe/Moscow")
    private val berlin = TimeZone.of("Europe/Berlin")

    private fun d(text: String) = LocalDate.parse(text)

    private fun at(text: String) = Instant.parse(text)

    private val trash =
        TaskData(
            title = "Take out the trash",
            createdBy = "m1",
            dueDate = d("2026-10-19"),
            recurrence =
                Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
        )

    private val filter =
        TaskData(
            title = "Change the water filter",
            createdBy = "m1",
            dueDate = d("2026-10-01"),
            recurrence = Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.MONTH, 3),
        )

    private fun completion(
        occurrence: String?,
        at: String = "2026-10-20T10:00:00Z",
        by: String = "m2",
        skipped: Boolean = false,
    ) = TaskCompletionData("t1", by, at(at), occurrence?.let(::d), skipped)

    private fun current(
        task: TaskData,
        today: String,
        vararg completions: TaskCompletionData,
        zone: TimeZone = moscow,
    ) = TaskSchedule.state(task, completions.toList(), d(today), zone).occurrence

    @Test
    fun `TSK-012 TSK-013 missed Monday stays current until Thursday`() {
        assertEquals(d("2026-10-19"), current(trash, "2026-10-19"))
        assertEquals(d("2026-10-19"), current(trash, "2026-10-20"))
        assertEquals(d("2026-10-22"), current(trash, "2026-10-22"))
        assertEquals(d("2026-10-26"), current(trash, "2026-10-28"))
        val tuesday = TaskSchedule.state(trash, emptyList(), d("2026-10-20"), moscow)
        assertTrue(TaskSchedule.isOverdue(trash, tuesday, at("2026-10-20T09:00:00Z"), moscow))
    }

    @Test
    fun `TSK-012 completing the missed Monday on Wednesday opens Thursday`() {
        val late = completion("2026-10-19", at = "2026-10-21T10:00:00Z")
        assertEquals(d("2026-10-22"), current(trash, "2026-10-21", late))
        val state = TaskSchedule.state(trash, listOf(late), d("2026-10-21"), moscow)
        assertEquals(TaskGroup.UPCOMING, TaskSchedule.group(trash, state, at("2026-10-21T12:00:00Z"), moscow))
    }

    @Test
    fun `TSK-012 two completions of one occurrence count once`() {
        val first = completion("2026-10-19", at = "2026-10-19T10:00:00Z", by = "m2")
        val second = completion("2026-10-19", at = "2026-10-19T10:00:05Z", by = "m3")
        assertEquals(current(trash, "2026-10-19", first), current(trash, "2026-10-19", first, second))
        assertEquals(d("2026-10-22"), current(trash, "2026-10-19", first, second))
    }

    @Test
    fun `TSK-012 skip advances like a completion and undo restores the occurrence`() {
        val skip = completion("2026-10-19", skipped = true)
        assertEquals(d("2026-10-22"), current(trash, "2026-10-20", skip))
        assertEquals(d("2026-10-19"), current(trash, "2026-10-20"))
    }

    @Test
    fun `TSK-016 fixed routine completed early`() {
        val early = completion("2026-10-22", at = "2026-10-21T08:00:00Z")
        val afterMonday = completion("2026-10-19", at = "2026-10-19T08:00:00Z")
        assertEquals(d("2026-10-26"), current(trash, "2026-10-21", afterMonday, early))
        val beforeStart = completion("2026-10-19", at = "2026-10-15T08:00:00Z")
        assertEquals(d("2026-10-22"), current(trash, "2026-10-15", beforeStart))
    }

    @Test
    fun `TSK-011 TSK-016 after completion counts from the actual completion`() {
        assertEquals(d("2026-10-01"), current(filter, "2026-09-20"))
        val early = completion("2026-10-01", at = "2026-09-25T10:00:00Z")
        assertEquals(d("2026-12-25"), current(filter, "2026-09-25", early))
        val late = completion("2026-10-01", at = "2026-11-10T10:00:00Z")
        assertEquals(d("2027-02-10"), current(filter, "2026-11-10", late))
    }

    @Test
    fun `TSK-011 concurrent completions use the latest completion time`() {
        val a = completion("2026-10-01", at = "2026-10-05T10:00:00Z", by = "m2")
        val b = completion("2026-10-01", at = "2026-10-07T10:00:00Z", by = "m3")
        assertEquals(d("2027-01-07"), current(filter, "2026-10-08", a, b))
        assertEquals(d("2027-01-07"), current(filter, "2026-10-08", b, a))
    }

    @Test
    fun `TSK-011 completion date is taken in the household zone`() {
        // 22:30 UTC on Oct 5 is already Oct 6 in Moscow.
        val c = completion("2026-10-01", at = "2026-10-05T22:30:00Z")
        assertEquals(d("2027-01-06"), current(filter, "2026-10-06", c))
        assertEquals(d("2027-01-05"), current(filter, "2026-10-06", c, zone = TimeZone.UTC))
    }

    @Test
    fun `TSK-011 moving the start date later postpones the next time`() {
        val c = completion("2026-10-01", at = "2026-10-05T10:00:00Z")
        assertEquals(d("2027-03-01"), current(filter.copy(dueDate = d("2027-03-01")), "2026-10-06", c))
    }

    @Test
    fun `TSK-003 one-off done by any non-skipped completion, done by the earliest`() {
        val task = TaskData("Call grandma", "m1", dueDate = d("2026-10-23"))
        val open = TaskSchedule.state(task, emptyList(), d("2026-10-23"), moscow)
        assertFalse(open.done)
        assertEquals(d("2026-10-23"), open.occurrence)
        val skippedOnly = TaskSchedule.state(task, listOf(completion("2026-10-23", skipped = true)), d("2026-10-23"), moscow)
        assertFalse(skippedOnly.done)
        val later = completion("2026-10-23", at = "2026-10-23T12:00:00Z", by = "m3")
        val earlier = completion("2026-10-23", at = "2026-10-23T11:00:00Z", by = "m2")
        val done = TaskSchedule.state(task, listOf(later, earlier), d("2026-10-23"), moscow)
        assertTrue(done.done)
        assertEquals(earlier, done.doneBy)
        assertEquals(TaskGroup.DONE, TaskSchedule.group(task, done, at("2026-10-24T12:00:00Z"), moscow))
        assertFalse(TaskSchedule.isOverdue(task, done, at("2026-10-25T12:00:00Z"), moscow))
    }

    @Test
    fun `TSK-020 one-off without a date has no group date`() {
        val task = TaskData("Fix the shelf", "m1")
        val state = TaskSchedule.state(task, emptyList(), d("2026-10-23"), moscow)
        assertNull(state.occurrence)
        assertEquals(TaskGroup.NO_DATE, TaskSchedule.group(task, state, at("2026-10-23T12:00:00Z"), moscow))
        assertFalse(TaskSchedule.isOverdue(task, state, at("2030-01-01T00:00:00Z"), moscow))
    }

    @Test
    fun `TSK-020 TSK-033 date-only task becomes overdue at midnight in Moscow`() {
        val task = TaskData("Pay rent", "m1", dueDate = d("2026-10-23"))
        val state = TaskSchedule.state(task, emptyList(), d("2026-10-23"), moscow)
        // Midnight in Moscow is 21:00 UTC the day before.
        assertEquals(TaskGroup.UPCOMING, TaskSchedule.group(task, state, at("2026-10-22T20:59:59Z"), moscow))
        assertEquals(TaskGroup.TODAY, TaskSchedule.group(task, state, at("2026-10-22T21:00:00Z"), moscow))
        assertEquals(TaskGroup.TODAY, TaskSchedule.group(task, state, at("2026-10-23T20:59:59Z"), moscow))
        assertEquals(TaskGroup.OVERDUE, TaskSchedule.group(task, state, at("2026-10-23T21:00:00Z"), moscow))
        assertNull(TaskSchedule.dueMoment(task, d("2026-10-23"), moscow))
        assertEquals(at("2026-10-24T06:00:00Z"), TaskSchedule.overdueNoticeAt(task, d("2026-10-23"), moscow))
    }

    @Test
    fun `TSK-020 TSK-033 timed task is overdue at its due time`() {
        val task = TaskData("Pick up Anya", "m1", dueDate = d("2026-10-23"), dueTime = LocalTime(20, 0))
        val state = TaskSchedule.state(task, emptyList(), d("2026-10-23"), moscow)
        assertEquals(at("2026-10-23T17:00:00Z"), TaskSchedule.dueMoment(task, d("2026-10-23"), moscow))
        assertEquals(TaskGroup.TODAY, TaskSchedule.group(task, state, at("2026-10-23T16:59:59Z"), moscow))
        assertEquals(TaskGroup.OVERDUE, TaskSchedule.group(task, state, at("2026-10-23T17:00:00Z"), moscow))
        assertEquals(at("2026-10-23T17:00:00Z"), TaskSchedule.overdueNoticeAt(task, d("2026-10-23"), moscow))
    }

    @Test
    fun `TSK-020 TSK-030 times across the Berlin DST change on the last Sunday of October`() {
        // 2026-10-25: clocks go back from 03:00 CEST (UTC+2) to 02:00 CET (UTC+1).
        val sunday = d("2026-10-25")
        val task = TaskData("Water plants", "m1", dueDate = sunday, dueTime = LocalTime(20, 0), reminderMinutesBefore = 60)
        assertEquals(at("2026-10-25T19:00:00Z"), TaskSchedule.dueMoment(task, sunday, berlin))
        assertEquals(at("2026-10-25T18:00:00Z"), TaskSchedule.reminderAt(task, sunday, berlin))
        val saturdayTask = task.copy(dueDate = d("2026-10-24"))
        assertEquals(at("2026-10-24T18:00:00Z"), TaskSchedule.dueMoment(saturdayTask, d("2026-10-24"), berlin))
        // A reminder 24 h before crosses the change: wall clock 21:00 on Saturday.
        assertEquals(at("2026-10-24T19:00:00Z"), TaskSchedule.reminderAt(task.copy(reminderMinutesBefore = 1440), sunday, berlin))

        val dateOnly = TaskData("Call the bank", "m1", dueDate = d("2026-10-24"))
        val state = TaskSchedule.state(dateOnly, emptyList(), d("2026-10-24"), berlin)
        // Saturday midnight in Berlin is still CEST (22:00 UTC); the notice is Sunday 09:00 CET.
        assertEquals(TaskGroup.TODAY, TaskSchedule.group(dateOnly, state, at("2026-10-24T21:59:59Z"), berlin))
        assertEquals(TaskGroup.OVERDUE, TaskSchedule.group(dateOnly, state, at("2026-10-24T22:00:00Z"), berlin))
        assertEquals(at("2026-10-25T08:00:00Z"), TaskSchedule.overdueNoticeAt(dateOnly, d("2026-10-24"), berlin))
    }

    @Test
    fun `TSK-030 reminder of a date-only task counts from 9 in the morning`() {
        val task = TaskData("Buy a gift", "m1", dueDate = d("2026-10-23"), reminderMinutesBefore = 0)
        assertEquals(at("2026-10-23T06:00:00Z"), TaskSchedule.reminderAt(task, d("2026-10-23"), moscow))
        val dayBefore = task.copy(reminderMinutesBefore = 1440)
        assertEquals(at("2026-10-22T06:00:00Z"), TaskSchedule.reminderAt(dayBefore, d("2026-10-23"), moscow))
        assertNull(TaskSchedule.reminderAt(task.copy(reminderMinutesBefore = null), d("2026-10-23"), moscow))
        assertEquals(LocalTime(9, 0), TaskSchedule.DAY_START)
    }

    @Test
    fun `TSK-004 routine checklist resets every occurrence`() {
        val tickedMonday = ChecklistItemData("t1", "Paper", 0, done = true, doneOccurrence = d("2026-10-19"))
        val monday = TaskSchedule.state(trash, emptyList(), d("2026-10-19"), moscow)
        assertTrue(TaskSchedule.isChecklistItemDone(trash, tickedMonday, monday))
        val thursday = TaskSchedule.state(trash, listOf(completion("2026-10-19")), d("2026-10-22"), moscow)
        assertFalse(TaskSchedule.isChecklistItemDone(trash, tickedMonday, thursday))
        assertFalse(TaskSchedule.isChecklistItemDone(trash, tickedMonday.copy(done = false), monday))

        val oneOff = TaskData("Pack", "m1")
        val ticked = ChecklistItemData("t2", "Passports", 0, done = true)
        val state = TaskSchedule.state(oneOff, emptyList(), d("2026-10-22"), moscow)
        assertTrue(TaskSchedule.isChecklistItemDone(oneOff, ticked, state))
    }

    @Test
    fun `TSK-033 next overdue check`() {
        val now = at("2026-10-20T10:00:00Z")
        // Not notified: the notice time of the current (missed Monday) occurrence, even though it is past.
        assertEquals(at("2026-10-20T06:00:00Z"), TaskSchedule.nextOverdueCheck(trash, emptyList(), null, now, moscow))
        // Notified about Monday: the notice time of Thursday.
        assertEquals(
            at("2026-10-23T06:00:00Z"),
            TaskSchedule.nextOverdueCheck(trash, emptyList(), d("2026-10-19"), now, moscow),
        )
        // After-completion routine already notified: nothing until a completion changes it.
        assertNull(TaskSchedule.nextOverdueCheck(filter, emptyList(), d("2026-10-01"), at("2026-10-05T10:00:00Z"), moscow))
        assertEquals(
            at("2026-10-02T06:00:00Z"),
            TaskSchedule.nextOverdueCheck(filter, emptyList(), null, at("2026-10-05T10:00:00Z"), moscow),
        )
        // Done one-off and one-off without a date: never.
        val oneOff = TaskData("Pay rent", "m1", dueDate = d("2026-10-23"))
        assertNull(TaskSchedule.nextOverdueCheck(oneOff, listOf(completion("2026-10-23")), null, now, moscow))
        assertNull(TaskSchedule.nextOverdueCheck(TaskData("Someday", "m1"), emptyList(), null, now, moscow))
        assertEquals(at("2026-10-24T06:00:00Z"), TaskSchedule.nextOverdueCheck(oneOff, emptyList(), null, now, moscow))
        // A notified one-off that is still open: nothing more.
        assertNull(TaskSchedule.nextOverdueCheck(oneOff, emptyList(), d("2026-10-23"), now, moscow))
    }
}
