package app.lares.client.ui

import app.lares.client.ui.tasks.RepeatPreset
import app.lares.client.ui.tasks.TaskForm
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskData
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskFormTest {
    private val today = LocalDate(2026, 10, 21) // Wednesday
    private val friday = LocalDate(2026, 10, 23)
    private val form = TaskForm(title = "Trash", dueDate = friday)

    @Test
    fun `presets become rules`() {
        assertEquals(Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY), form.withPreset(RepeatPreset.DAILY, today).recurrence)
        assertEquals(
            Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, weekdays = setOf(DayOfWeek.FRIDAY)),
            form.withPreset(RepeatPreset.WEEKLY, today).recurrence,
        )
        assertEquals(Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.MONTH), form.withPreset(RepeatPreset.MONTHLY, today).recurrence)
        assertEquals(Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.YEAR), form.withPreset(RepeatPreset.YEARLY, today).recurrence)
        assertNull(form.withPreset(RepeatPreset.DAILY, today).withPreset(RepeatPreset.NONE, today).recurrence)
        RepeatPreset.entries.filter { it != RepeatPreset.CUSTOM }.forEach {
            assertEquals(it, form.withPreset(it, today).preset(), "preset $it reads back")
        }
    }

    @Test
    fun `custom rules`() {
        val monThu = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        val weekly = form.withRecurrence(monThu, today)
        assertEquals(monThu, weekly.recurrence)
        assertEquals(RepeatPreset.CUSTOM, weekly.preset())

        // Every 3 months after completion: weekdays are dropped when leaving a weekly schedule.
        val filter = weekly.withRecurrence(Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.MONTH, 3, monThu.weekdays), today)
        assertEquals(Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.MONTH, 3), filter.recurrence)
        assertEquals(RepeatPreset.CUSTOM, filter.preset())
        assertTrue(filter.validate().isEmpty())

        val everyTwoDays = form.withRecurrence(Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY, 2), today)
        assertEquals(RepeatPreset.CUSTOM, everyTwoDays.preset())
    }

    @Test
    fun `a weekly rule gets the weekday of the date`() {
        val rule = form.withRecurrence(Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK), today).recurrence
        assertEquals(setOf(DayOfWeek.FRIDAY), rule?.weekdays)
        // The weekly preset follows a new date; a custom set of days stays.
        val moved = form.withPreset(RepeatPreset.WEEKLY, today).withDate(LocalDate(2026, 10, 26))
        assertEquals(setOf(DayOfWeek.MONDAY), moved.recurrence?.weekdays)
        assertEquals(RepeatPreset.WEEKLY, moved.preset())
        val custom = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)
        val kept =
            form
                .withRecurrence(Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, weekdays = custom), today)
                .withDate(LocalDate(2026, 10, 27))
        assertEquals(custom, kept.recurrence?.weekdays)
    }

    @Test
    fun `choosing a repeat without a date starts it today`() {
        val noDate = TaskForm(title = "Water the plants")
        val daily = noDate.withPreset(RepeatPreset.DAILY, today)
        assertEquals(today, daily.dueDate)
        val weekly = noDate.withPreset(RepeatPreset.WEEKLY, today)
        assertEquals(setOf(DayOfWeek.WEDNESDAY), weekly.recurrence?.weekdays)
        // A routine keeps its date: "Clear" does nothing.
        assertEquals(today, daily.withDate(null).dueDate)
    }

    @Test
    fun `a time needs a date`() {
        val noDate = TaskForm(title = "Call")
        assertNull(noDate.withTime(LocalTime(20, 0)).dueTime)
        val timed = form.withTime(LocalTime(20, 0)).withReminder(15)
        val cleared = timed.withDate(null)
        assertNull(cleared.dueTime)
        assertNull(cleared.reminderMinutesBefore)
        assertNull(noDate.withReminder(0).reminderMinutesBefore)
        assertEquals(mapOf("dueDate" to FieldErrorCode.REQUIRED), TaskForm(title = "x", dueTime = LocalTime(9, 0)).validate())
    }

    @Test
    fun `switching between time and no time resets a reminder that does not fit`() {
        val timed = form.withTime(LocalTime(20, 0)).withReminder(15)
        assertEquals(0, timed.withTime(null).reminderMinutesBefore, "15 minutes before has no meaning without a time")
        assertEquals(1440, timed.withReminder(1440).withTime(null).reminderMinutesBefore, "1 day before exists in both lists")
        assertEquals(0, form.withReminder(2880).withTime(LocalTime(8, 0)).reminderMinutesBefore)
        assertNull(form.withTime(LocalTime(8, 0)).reminderMinutesBefore, "no reminder stays none")
    }

    @Test
    fun `an unchanged start date is not written on edit`() {
        val stored =
            TaskData(
                "Trash",
                "m1",
                dueDate = LocalDate(2026, 1, 31),
                recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.MONTH),
            )
        val edited = TaskForm.of(stored).withTitle("Take out the trash")
        // Meanwhile another phone moved the start date: the form does not bring the old one back.
        val now = stored.copy(dueDate = LocalDate(2026, 2, 28), notes = "Blue bin")
        val saved = edited.applyTo(now)
        assertEquals("Take out the trash", saved.title)
        assertEquals(LocalDate(2026, 2, 28), saved.dueDate)
        assertEquals("Blue bin", saved.notes)
        assertEquals(LocalDate(2026, 3, 31), edited.withDate(LocalDate(2026, 3, 31)).applyTo(now).dueDate)
        assertEquals(stored, TaskForm.of(stored).applyTo(stored), "nothing changed")
    }

    @Test
    fun `a routine cannot stop repeating`() {
        val routine =
            TaskForm.of(
                TaskData("Trash", "m1", dueDate = friday, recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY)),
            )
        assertFalse(routine.canStopRepeat)
        assertEquals(RecurrenceUnit.DAY, routine.withPreset(RepeatPreset.NONE, today).recurrence?.unit)
        assertEquals(RecurrenceUnit.WEEK, routine.withPreset(RepeatPreset.WEEKLY, today).recurrence?.unit, "other rules are fine")
        // A one-off may become a routine and, before saving, a one-off again.
        val oneOff = TaskForm.of(TaskData("Doctor", "m1", dueDate = friday))
        assertTrue(oneOff.canStopRepeat)
        assertNull(oneOff.withPreset(RepeatPreset.DAILY, today).withPreset(RepeatPreset.NONE, today).recurrence)
    }

    @Test
    fun `data and validation`() {
        val data =
            TaskForm(title = "  Music school ", notes = "  ", assigneeIds = setOf("m3"), checklist = listOf(" Notes ", " ", "Shoes"))
                .toData("m1")
        assertEquals("Music school", data.title)
        assertNull(data.notes)
        assertEquals(setOf("m3"), data.assigneeIds)
        assertEquals(
            listOf("Notes", "Shoes"),
            TaskForm(checklist = listOf(" Notes ", " ", "Shoes")).checklistItems(),
        )
        assertEquals(mapOf("title" to FieldErrorCode.REQUIRED), TaskForm(title = " ").validate())
        assertEquals(setOf("m2"), TaskForm().toggleAssignee("m2").assigneeIds)
        assertTrue(TaskForm(assigneeIds = setOf("m2")).withAnyone().assigneeIds.isEmpty())
    }
}
