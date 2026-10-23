package app.lares.contract.entities

import app.lares.contract.api.LaresJson
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class TaskEntitiesTest {
    private fun <T> roundTrip(
        serializer: KSerializer<T>,
        value: T,
    ): String {
        val text = LaresJson.encodeToString(serializer, value)
        assertEquals(value, LaresJson.decodeFromString(serializer, text), text)
        return text
    }

    private val routine =
        TaskData(
            title = "Take out the trash",
            createdBy = "m1",
            notes = "Paper on Thursdays",
            dueDate = LocalDate(2026, 10, 19),
            dueTime = LocalTime(20, 0),
            assigneeIds = setOf("m2", "m3"),
            tagIds = setOf("t1"),
            recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(DayOfWeek.THURSDAY, DayOfWeek.MONDAY)),
            reminderMinutesBefore = 30,
            notifyCreatorOnDone = true,
        )

    @Test
    fun `TSK-001 TSK-010 task round trip with ISO dates, times and weekday names`() {
        val text = roundTrip(TaskData.serializer(), routine)
        assertTrue("\"dueDate\":\"2026-10-19\"" in text, text)
        assertTrue("\"dueTime\":\"20:00\"" in text, text)
        assertTrue("\"weekdays\":[\"MONDAY\",\"THURSDAY\"]" in text, text)
        assertTrue("\"mode\":\"fixed\"" in text && "\"unit\":\"week\"" in text, text)
        roundTrip(TaskData.serializer(), TaskData("Fix the shelf", "m1"))
        roundTrip(
            TaskData.serializer(),
            routine.copy(recurrence = Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.MONTH, 3)),
        )
    }

    @Test
    fun `TSK-011 enum wire names are lowercase snake case`() {
        for (mode in RecurrenceMode.entries) {
            for (unit in RecurrenceUnit.entries) roundTrip(Recurrence.serializer(), Recurrence(mode, unit))
        }
        val text = LaresJson.encodeToString(Recurrence.serializer(), Recurrence(RecurrenceMode.AFTER_COMPLETION, RecurrenceUnit.YEAR))
        assertTrue("\"mode\":\"after_completion\"" in text && "\"unit\":\"year\"" in text, text)
    }

    @Test
    fun `TSK-001 missing optional fields take their defaults`() {
        val task = LaresJson.decodeFromString(TaskData.serializer(), """{"title":"Call grandma","createdBy":"m1"}""")
        assertEquals(TaskData("Call grandma", "m1"), task)
        val item = LaresJson.decodeFromString(ChecklistItemData.serializer(), """{"taskId":"t","text":"Milk","position":3}""")
        assertEquals(ChecklistItemData("t", "Milk", 3), item)
        val completion =
            LaresJson.decodeFromString(
                TaskCompletionData.serializer(),
                """{"taskId":"t","completedBy":"m1","completedAt":"2026-10-23T10:15:30Z"}""",
            )
        assertEquals(TaskCompletionData("t", "m1", Instant.parse("2026-10-23T10:15:30Z")), completion)
    }

    @Test
    fun `TSK-010 unknown recurrence values make the task a one-off`() {
        val unknownUnit =
            """{"title":"x","createdBy":"m1","dueDate":"2026-10-23","recurrence":{"mode":"fixed","unit":"fortnight"}}"""
        assertNull(LaresJson.decodeFromString(TaskData.serializer(), unknownUnit).recurrence)
        val unknownMode =
            """{"title":"x","createdBy":"m1","dueDate":"2026-10-23","recurrence":{"mode":"rotation","unit":"day"}}"""
        val task = LaresJson.decodeFromString(TaskData.serializer(), unknownMode)
        assertNull(task.recurrence)
        assertEquals(LocalDate(2026, 10, 23), task.dueDate)
        val explicitNull = """{"title":"x","createdBy":"m1","recurrence":null}"""
        assertNull(LaresJson.decodeFromString(TaskData.serializer(), explicitNull).recurrence)
        // A newer field inside the rule is ignored, the rule still decodes.
        val newerField =
            """{"title":"x","createdBy":"m1","recurrence":{"mode":"fixed","unit":"day","interval":2,"rotation":["m1"]}}"""
        assertEquals(
            Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY, 2),
            LaresJson.decodeFromString(TaskData.serializer(), newerField).recurrence,
        )
    }

    @Test
    fun `TSK-010 unknown weekday names are dropped`() {
        val rule =
            LaresJson.decodeFromString(
                Recurrence.serializer(),
                """{"mode":"fixed","unit":"week","weekdays":["MONDAY","FUNDAY","FRIDAY"]}""",
            )
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), rule.weekdays)
    }

    @Test
    fun `TSK-004 TSK-015 checklist item and completion round trips`() {
        roundTrip(ChecklistItemData.serializer(), ChecklistItemData("t", "Paper", 2, done = true, doneOccurrence = LocalDate(2026, 10, 22)))
        roundTrip(ChecklistItemData.serializer(), ChecklistItemData("t", "Glass", 0))
        val text =
            roundTrip(
                TaskCompletionData.serializer(),
                TaskCompletionData("t", "m2", Instant.parse("2026-10-23T10:15:30.123Z"), LocalDate(2026, 10, 22), skipped = true),
            )
        assertTrue("\"occurrence\":\"2026-10-22\"" in text, text)
        roundTrip(TaskCompletionData.serializer(), TaskCompletionData("t", "m2", Instant.parse("2026-10-23T10:15:30Z")))
    }
}
