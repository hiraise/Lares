package app.lares.client.ui

import app.lares.client.db.LocalDb
import app.lares.client.ui.tasks.RepeatPreset
import app.lares.client.ui.tasks.TaskEditorArgs
import app.lares.client.ui.tasks.TaskEditorResult
import app.lares.client.ui.tasks.TaskEditorViewModel
import app.lares.client.ui.tasks.TasksViewModel
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.GroupData
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskEditorViewModelTest {
    private fun UiTestCore.editor(args: TaskEditorArgs = TaskEditorArgs()) = koin.get<TaskEditorViewModel> { parametersOf(args) }

    /** The fields of the last outbox entry of the task. */
    private fun UiTestCore.sentFields(id: String): JsonObject =
        runBlocking {
            val json =
                koin.get<LocalDb>().read {
                    sync_outboxQueries
                        .byEntity(EntityTypes.TASK, id)
                        .executeAsList()
                        .last()
                        .fields
                }
            LaresJson.parseToJsonElement(json!!).jsonObject
        }

    @Test
    fun `create with a checklist writes the task and its items`() {
        TaskUiFixture.core().use { core ->
            val vm = core.editor()
            awaitState(vm.state) { it.loaded && it.canCreate }
            vm.onTitle("Weekly cleaning")
            vm.onPreset(RepeatPreset.WEEKLY)
            vm.addChecklistItem()
            vm.onChecklistItem(0, "Kitchen")
            vm.addChecklistItem()
            vm.addChecklistItem()
            vm.onChecklistItem(2, " Bathroom ")
            val ready = awaitState(vm.state) { it.form.checklist.size == 3 }
            assertTrue(ready.changed)
            assertEquals(TaskUiFixture.today, ready.form.dueDate, "a repeat without a date starts today")
            assertEquals(TaskUiFixture.today, ready.nextTime)

            vm.save()
            val created = assertIs<TaskEditorResult.Created>(awaitState(vm.state) { it.result != null }.result)
            val outbox = TaskUiFixture.outbox(core)
            assertTrue("task:${created.id}" in outbox)
            assertEquals(2, outbox.count { it.startsWith("checklist_item:") }, "the blank item is dropped")
            val sent = core.sentFields(created.id)
            assertEquals("\"m1\"", sent["createdBy"].toString())
        }
    }

    @Test
    fun `edit sends only the changed fields`() {
        TaskUiFixture.core().use { core ->
            val vm = core.editor(TaskEditorArgs("t-routine"))
            val loaded = awaitState(vm.state) { it.loaded && it.form.title == "Trash" }
            assertFalse(loaded.isNew)
            assertTrue(loaded.canEdit)
            assertFalse(loaded.changed)
            assertFalse(loaded.form.canStopRepeat)
            assertNotNull(loaded.nextTime, "a routine shows the next time")

            vm.onTitle("Take out the trash")
            vm.onPreset(RepeatPreset.NONE)
            awaitState(vm.state) { it.form.title == "Take out the trash" }
            vm.save()
            assertEquals(TaskEditorResult.Saved, awaitState(vm.state) { it.result != null }.result)
            assertEquals(setOf("title"), core.sentFields("t-routine").keys, "the start date and the rule are not rewritten")
            assertEquals(
                RecurrenceUnit.DAY,
                vm.state.value.form.recurrence
                    ?.unit,
            )
        }
    }

    @Test
    fun `defaults of a new task from the Mine scope and a tag filter`() {
        TaskUiFixture.core().use { core ->
            val vm = core.editor(TaskEditorArgs(assignToMe = true, tagId = "t1"))
            val state = awaitState(vm.state) { it.loaded }
            assertEquals(setOf("m1"), state.form.assigneeIds)
            assertEquals(setOf("t1"), state.form.tagIds)
            assertFalse(state.changed, "defaults are not changes")
            assertTrue(state.isCreator)
            assertFalse(state.form.notifyCreatorOnDone)

            val plain = awaitState(core.editor().state) { it.loaded }
            assertTrue(plain.form.assigneeIds.isEmpty(), "anyone by default from other lists")
        }
    }

    @Test
    fun `anyone is exclusive`() {
        TaskUiFixture.core().use { core ->
            val vm = core.editor()
            awaitState(vm.state) { it.loaded && it.members.isNotEmpty() }
            vm.toggleAssignee("m2")
            vm.toggleAssignee("m3")
            assertEquals(setOf("m2", "m3"), awaitState(vm.state) { it.form.assigneeIds.size == 2 }.form.assigneeIds)
            vm.onAnyone()
            assertTrue(awaitState(vm.state) { it.form.assigneeIds.isEmpty() }.form.assigneeIds.isEmpty())
        }
    }

    @Test
    fun `a blank title is not saved`() {
        TaskUiFixture.core().use { core ->
            val vm = core.editor()
            val state = awaitState(vm.state) { it.loaded }
            assertFalse(state.canSave)
            vm.onTitle("   ")
            vm.save()
            assertTrue(TaskUiFixture.outbox(core).none { it.startsWith("task:") })
        }
    }

    @Test
    fun `a new tag from the editor is selected`() {
        TaskUiFixture.core().use { core ->
            val vm = core.editor()
            assertTrue(awaitState(vm.state) { it.loaded }.canManageTags)
            vm.newTag()
            vm.tagEditor.onName("School")
            vm.tagEditor.save()
            val state = awaitState(vm.state) { it.form.tagIds.size == 1 && it.tags.size == 2 }
            assertEquals(
                "School",
                state.tags
                    .first { it.id in state.form.tagIds }
                    .data.name,
            )
        }
    }

    @Test
    fun `a kid without tasks create cannot create`() {
        // The Kids template allows creating tasks; this family took it away.
        val noCreate =
            FamilyFixture.change(
                EntityTypes.GROUP,
                FamilyFixture.KIDS,
                GroupData.serializer(),
                GroupData("Kids", null, setOf(Permission.TASKS_VIEW), setOf("m3")),
            )
        FamilyFixture.core(FamilyFixture.carol, TaskUiFixture.changes + noCreate).use { core ->
            val state = awaitState(core.editor().state) { it.loaded && it.members.isNotEmpty() }
            assertFalse(state.canCreate)
            assertFalse(state.allowed)
            assertFalse(awaitState(core.koin.get<TasksViewModel>().state) { it.loaded }.canCreate, "no FAB")
            // The kid may not edit the routine Olga created for them either.
            assertFalse(awaitState(core.editor(TaskEditorArgs("t-routine")).state) { it.loaded && it.form.title.isNotEmpty() }.canEdit)
        }
    }
}
