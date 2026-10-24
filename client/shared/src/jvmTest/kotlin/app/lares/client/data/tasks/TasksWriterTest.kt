package app.lares.client.data.tasks

import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalValidationException
import app.lares.client.sync.LocalWriteException
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskData
import app.lares.contract.tasks.TaskGroup
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasksWriterTest {
    private val f = TaskFixture()
    private val today = LocalDate(2026, 10, 21)
    private val daily = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY)

    private suspend fun fields(index: Int): JsonObject = LaresJson.parseToJsonElement(f.outbox()[index].fields!!).jsonObject

    @Test
    fun createQueuesTheTaskAndItsChecklist() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val id = f.tasksWriter.create(TaskData(" Уборка ", createdBy = "ignored", dueDate = today), listOf("Пол", "Пыль"))
            val outbox = f.outbox()
            assertEquals(listOf("task", "checklist_item", "checklist_item"), outbox.map { it.type })
            assertEquals(id, outbox[0].entity_id)
            val task = fields(0)
            assertEquals("Уборка", task["title"]?.jsonPrimitive?.content)
            assertEquals(OLGA, task["createdBy"]?.jsonPrimitive?.content)
            assertEquals("2026-10-21", task["dueDate"]?.jsonPrimitive?.content)
            assertEquals(listOf("0", "1"), listOf(fields(1), fields(2)).map { it["position"]!!.jsonPrimitive.content })
            assertEquals(listOf("Пол", "Пыль"), f.detail(id).checklist.map { it.data.text })
        }

    @Test
    fun kidCannotEditAnAdultsTaskLocally() =
        runTest {
            f.family()
            f.putTask("t1", TaskData("Помыть машину", createdBy = DIMA, assigneeIds = setOf(ANYA)))
            f.putItem("i1", ChecklistItemData("t1", "Шланг", 0))
            f.loginAs(ANYA, *KID)
            assertFailsWith<LocalForbiddenException> { f.tasksWriter.update("t1", TaskData("Помыть велосипед", createdBy = DIMA)) }
            assertFailsWith<LocalForbiddenException> { f.tasksWriter.delete("t1") }
            assertFailsWith<LocalForbiddenException> { f.tasksWriter.addChecklistItem("t1", "Губка") }
            assertFailsWith<LocalForbiddenException> { f.tasksWriter.renameChecklistItem("i1", "Ведро") }
            assertFailsWith<LocalForbiddenException> { f.tasksWriter.create(TaskData("Своя", createdBy = ANYA)) }
            assertEquals(emptyList(), f.outbox())
            // An assignee may tick items and complete.
            f.tasksWriter.setChecklistItemDone("i1", true)
            f.tasksWriter.complete("t1")
            assertEquals(listOf("checklist_item", "task_completion"), f.outbox().map { it.type })
        }

    @Test
    fun othersCannotCompleteAssignedTasks() =
        runTest {
            f.family()
            f.putTask("t1", TaskData("Помыть машину", createdBy = OLGA, assigneeIds = setOf(DIMA)))
            f.loginAs(ANYA, *KID)
            assertFailsWith<LocalForbiddenException> { f.tasksWriter.complete("t1") }
            assertEquals(emptyList(), f.outbox())
        }

    @Test
    fun completeMovesARoutineToTheNextOccurrenceOfflineAndUndoRestoresIt() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val id = f.tasksWriter.create(TaskData("Посуда", OLGA, dueDate = today, dueTime = LocalTime(20, 0), recurrence = daily))
            assertEquals(today, f.view(id).state.occurrence)
            val completionId = f.tasksWriter.complete(id)
            val completion = fields(1)
            assertEquals("2026-10-21", completion["occurrence"]?.jsonPrimitive?.content)
            assertEquals(OLGA, completion["completedBy"]?.jsonPrimitive?.content)
            assertEquals("2026-10-21T10:00:00Z", completion["completedAt"]?.jsonPrimitive?.content)
            val after = f.view(id)
            assertEquals(LocalDate(2026, 10, 22), after.state.occurrence)
            assertEquals(TaskGroup.UPCOMING, after.group)
            assertEquals(completionId, after.completedToday?.id)

            f.tasksWriter.undo(completionId)
            assertEquals(today, f.view(id).state.occurrence)
            assertEquals("delete", f.outbox().last().op)
        }

    @Test
    fun skipQueuesASkippedCompletionAndIsRefusedForOneOffs() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val routine = f.tasksWriter.create(TaskData("Полив", OLGA, dueDate = today, recurrence = daily))
            f.tasksWriter.skip(routine)
            assertEquals("true", fields(1)["skipped"]?.jsonPrimitive?.content)
            assertEquals(LocalDate(2026, 10, 22), f.view(routine).state.occurrence)
            assertNull(f.view(routine).completedToday)

            val oneOff = f.tasksWriter.create(TaskData("Разово", OLGA))
            val e = assertFailsWith<LocalValidationException> { f.tasksWriter.skip(oneOff) }
            assertEquals(mapOf("skipped" to FieldErrorCode.INVALID_FORMAT), e.fields)
        }

    @Test
    fun reopenDeletesTheCompletionsOfADoneOneOff() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val id = f.tasksWriter.create(TaskData("Оплатить", OLGA, dueDate = today))
            f.tasksWriter.complete(id)
            assertTrue(f.view(id).state.done)
            f.tasksWriter.reopen(id)
            assertFalse(f.view(id).state.done)
            assertEquals(listOf("upsert", "upsert", "delete"), f.outbox().map { it.op })
            assertFailsWith<LocalForbiddenException> { f.tasksWriter.reopen(id) }
        }

    @Test
    fun routineChecklistTicksResetForTheNextOccurrence() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val id = f.tasksWriter.create(TaskData("Уборка", OLGA, dueDate = today, recurrence = daily), listOf("Пол"))
            val item =
                f
                    .detail(id)
                    .checklist
                    .single()
                    .id
            f.tasksWriter.setChecklistItemDone(item, true)
            assertEquals("2026-10-21", fields(2)["doneOccurrence"]?.jsonPrimitive?.content)
            assertEquals(listOf(true), f.detail(id).checklist.map { it.shownDone })
            assertEquals(1, f.view(id).checklistDone)
            f.tasksWriter.complete(id)
            assertEquals(listOf(false), f.detail(id).checklist.map { it.shownDone })
            assertEquals(0, f.view(id).checklistDone)
        }

    @Test
    fun oneOffTicksHaveNoOccurrence() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val id = f.tasksWriter.create(TaskData("Покупки", OLGA, dueDate = today))
            val item = f.tasksWriter.addChecklistItem(id, "Хлеб")
            val second = f.tasksWriter.addChecklistItem(id, "Молоко")
            f.tasksWriter.setChecklistItemDone(item, true)
            f.tasksWriter.renameChecklistItem(second, " Кефир ")
            f.tasksWriter.deleteChecklistItem(item)
            val outbox = f.outbox()
            assertEquals("1", fields(2)["position"]?.jsonPrimitive?.content)
            assertEquals("""{"done":true,"doneOccurrence":null}""", outbox[3].fields)
            assertEquals("""{"text":"Кефир"}""", outbox[4].fields)
            assertEquals("delete", outbox[5].op)
            assertEquals(listOf("Кефир"), f.detail(id).checklist.map { it.data.text })
        }

    @Test
    fun updateSendsOnlyChangedFields() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val data = TaskData("Вынести мусор", OLGA, dueDate = today, assigneeIds = setOf(DIMA), notes = "Вечером")
            val id = f.tasksWriter.create(data)
            f.tasksWriter.update(id, data.copy(title = "Вынести мусор и стекло", notes = null))
            assertEquals("""{"title":"Вынести мусор и стекло","notes":null}""", f.outbox()[1].fields)
            f.tasksWriter.update(id, data.copy(title = "Вынести мусор и стекло", notes = null))
            assertEquals(2, f.outbox().size)
        }

    @Test
    fun aRoutineCannotBecomeAOneOffAndChildrenOfMissingTasksAreRefused() =
        runTest {
            f.family()
            f.loginAs(OLGA, *ADULT)
            val data = TaskData("Полив", OLGA, dueDate = today, recurrence = daily)
            val id = f.tasksWriter.create(data)
            val e = assertFailsWith<LocalValidationException> { f.tasksWriter.update(id, data.copy(recurrence = null)) }
            assertEquals(mapOf("recurrence" to FieldErrorCode.INVALID_FORMAT), e.fields)
            f.tasksWriter.delete(id)
            val missing = assertFailsWith<LocalWriteException> { f.tasksWriter.addChecklistItem(id, "x") }
            assertEquals(ErrorCode.ENTITY_NOT_FOUND, missing.code)
        }
}
