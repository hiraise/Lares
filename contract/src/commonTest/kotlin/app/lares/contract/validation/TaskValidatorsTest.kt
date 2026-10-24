package app.lares.contract.validation

import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class TaskValidatorsTest {
    private val date = LocalDate(2026, 10, 23)
    private val task = TaskData("Vacuum", "m1")
    private val weekly = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(DayOfWeek.MONDAY))

    private fun errors(data: TaskData) = Validators.task(data)

    @Test
    fun `TSK-001 valid tasks have no errors`() {
        assertEquals(emptyMap(), errors(task))
        val full =
            task.copy(
                notes = "n".repeat(Validators.NOTES_MAX),
                dueDate = date,
                dueTime = LocalTime(20, 0),
                assigneeIds = (1..20).map { "m$it" }.toSet(),
                tagIds = (1..20).map { "t$it" }.toSet(),
                recurrence = weekly.copy(interval = 999),
                reminderMinutesBefore = Validators.REMINDER_MINUTES_MAX,
            )
        assertEquals(emptyMap(), errors(full))
        assertEquals(emptyMap(), errors(task.copy(dueDate = date, reminderMinutesBefore = 0)))
    }

    @Test
    fun `TSK-001 title is required and limited`() {
        assertEquals(FieldErrorCode.REQUIRED, errors(task.copy(title = "   "))["title"])
        assertEquals(FieldErrorCode.TOO_LONG, errors(task.copy(title = "x".repeat(201)))["title"])
        assertNull(errors(task.copy(title = "x".repeat(200)))["title"])
        assertEquals(FieldErrorCode.TOO_LONG, errors(task.copy(notes = "n".repeat(2001)))["notes"])
    }

    @Test
    fun `TSK-001 time, routine and reminder need a date`() {
        assertEquals(mapOf("dueDate" to FieldErrorCode.REQUIRED), errors(task.copy(dueTime = LocalTime(20, 0))))
        assertEquals(mapOf("dueDate" to FieldErrorCode.REQUIRED), errors(task.copy(recurrence = weekly)))
        assertEquals(mapOf("dueDate" to FieldErrorCode.REQUIRED), errors(task.copy(reminderMinutesBefore = 10)))
    }

    @Test
    fun `TSK-001 due time in whole minutes`() {
        assertEquals(FieldErrorCode.INVALID_FORMAT, errors(task.copy(dueDate = date, dueTime = LocalTime(20, 0, 30)))["dueTime"])
        assertEquals(FieldErrorCode.INVALID_FORMAT, errors(task.copy(dueDate = date, dueTime = LocalTime(20, 0, 0, 1)))["dueTime"])
    }

    @Test
    fun `TSK-010 recurrence interval and weekdays`() {
        val dated = task.copy(dueDate = date)
        assertEquals(FieldErrorCode.OUT_OF_RANGE, errors(dated.copy(recurrence = weekly.copy(interval = 0)))["recurrence"])
        assertEquals(FieldErrorCode.OUT_OF_RANGE, errors(dated.copy(recurrence = weekly.copy(interval = 1000)))["recurrence"])
        assertEquals(FieldErrorCode.INVALID_FORMAT, errors(dated.copy(recurrence = weekly.copy(weekdays = emptySet())))["recurrence"])
        val monthlyWithDays = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.MONTH, 1, setOf(DayOfWeek.MONDAY))
        assertEquals(FieldErrorCode.INVALID_FORMAT, errors(dated.copy(recurrence = monthlyWithDays))["recurrence"])
        val afterWeekWithDays = Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.WEEK, 2, setOf(DayOfWeek.MONDAY))
        assertEquals(FieldErrorCode.INVALID_FORMAT, errors(dated.copy(recurrence = afterWeekWithDays))["recurrence"])
        val afterWeek = Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.WEEK, 2)
        assertEquals(emptyMap(), errors(dated.copy(recurrence = afterWeek)))
    }

    @Test
    fun `TSK-030 reminder range`() {
        val dated = task.copy(dueDate = date)
        assertEquals(FieldErrorCode.OUT_OF_RANGE, errors(dated.copy(reminderMinutesBefore = -1))["reminderMinutesBefore"])
        assertEquals(FieldErrorCode.OUT_OF_RANGE, errors(dated.copy(reminderMinutesBefore = 43201))["reminderMinutesBefore"])
    }

    @Test
    fun `TSK-002 TAG-002 at most 20 assignees and 20 tags`() {
        assertEquals(FieldErrorCode.OUT_OF_RANGE, errors(task.copy(assigneeIds = (0..20).map { "m$it" }.toSet()))["assigneeIds"])
        assertEquals(FieldErrorCode.OUT_OF_RANGE, errors(task.copy(tagIds = (0..20).map { "t$it" }.toSet()))["tagIds"])
    }

    @Test
    fun `TSK-001 normalization trims title and notes`() {
        val normalized = Validators.normalizeTask(task.copy(title = "  Vacuum  ", notes = "  \n "))
        assertEquals(task, normalized)
        assertEquals("Rug", Validators.normalizeTask(task.copy(notes = " Rug ")).notes)
        val item = Validators.normalizeChecklistItem(ChecklistItemData("t", "  Milk ", 0))
        assertEquals("Milk", item.text)
    }

    @Test
    fun `TSK-004 checklist item text and position`() {
        assertEquals(emptyMap(), Validators.checklistItem(ChecklistItemData("t", "Milk", 1_000_000)))
        assertEquals(FieldErrorCode.REQUIRED, Validators.checklistItem(ChecklistItemData("t", " ", 0))["text"])
        assertEquals(FieldErrorCode.TOO_LONG, Validators.checklistItem(ChecklistItemData("t", "x".repeat(201), 0))["text"])
        assertEquals(FieldErrorCode.OUT_OF_RANGE, Validators.checklistItem(ChecklistItemData("t", "Milk", -1))["position"])
        assertEquals(FieldErrorCode.OUT_OF_RANGE, Validators.checklistItem(ChecklistItemData("t", "Milk", 1_000_001))["position"])
    }

    @Test
    fun `TSK-015 skipping is for routines only`() {
        val skip = TaskCompletionData("t", "m1", Instant.parse("2026-10-23T10:00:00Z"), date, skipped = true)
        assertEquals(mapOf("skipped" to FieldErrorCode.INVALID_FORMAT), Validators.taskCompletion(skip, task))
        assertEquals(emptyMap(), Validators.taskCompletion(skip, task.copy(dueDate = date, recurrence = weekly)))
        assertEquals(emptyMap(), Validators.taskCompletion(skip.copy(skipped = false), task))
    }
}
