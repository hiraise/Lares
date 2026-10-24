package app.lares.integration

import app.lares.client.auth.SessionState
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.CreateMemberRequest
import app.lares.contract.api.admin.SetGroupsRequest
import app.lares.contract.api.admin.UpdateGroupRequest
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.contract.sync.EntityTypes
import app.lares.server.testing.MEMBER_PASSWORD
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Tasks on several phones against the real server (plan M1-04): offline completion, checklists, cascade, rights. */
class TaskSyncTest {
    private fun today(): LocalDate =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.of("Europe/Moscow"))
            .date

    private suspend fun Family.secondKid(): TestClient {
        anna.api.createMember(CreateMemberRequest("Dave", "dave", MEMBER_PASSWORD, setOf(kidsId)))
        return TestClient(server.url, "Dave's phone").also {
            it.login("dave", MEMBER_PASSWORD)
            it.sync()
        }
    }

    private suspend fun TestClient.view(id: String) = tasks.observeViews().first().single { it.id == id }

    private suspend fun TestClient.detail(id: String) = tasks.observeDetail(id).first()!!

    @Test
    fun `two kids complete the same routine occurrence offline`() =
        familyTest {
            secondKid().use { dave ->
                val clients = all + dave
                val daveId =
                    dave.session.state.value
                        .let { (it as SessionState.LoggedIn).me.memberId }
                val id =
                    anna.tasksWriter.create(
                        TaskData(
                            "Feed the cat",
                            createdBy = "",
                            dueDate = today(),
                            assigneeIds = setOf(carolMemberId, daveId),
                            recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY),
                        ),
                    )
                anna.sync()
                clients.forEach { it.sync() }

                carol.network.online = false
                dave.network.online = false
                carol.tasksWriter.complete(id)
                dave.tasksWriter.complete(id)
                val tomorrow = today().plus(1, DateTimeUnit.DAY)
                assertEquals(tomorrow, carol.view(id).state.occurrence)
                assertEquals(tomorrow, dave.view(id).state.occurrence)

                carol.network.online = true
                dave.network.online = true
                assertConverged(clients)
                val completions = anna.snapshot().taskCompletions.values
                assertEquals(setOf(carolMemberId, daveId), completions.map { it.completedBy }.toSet())
                assertTrue(completions.all { it.occurrence == today() })
                clients.forEach { assertEquals(tomorrow, it.view(id).state.occurrence) }
            }
        }

    @Test
    fun `two phones tick different checklist items offline`() =
        familyTest {
            val id = anna.tasksWriter.create(TaskData("Clean the room", createdBy = "", dueDate = today()), listOf("Floor", "Desk"))
            anna.sync()
            all.forEach { it.sync() }
            val (floor, desk) = anna.detail(id).checklist.map { it.id }

            boris.network.online = false
            carol.network.online = false
            boris.tasksWriter.setChecklistItemDone(floor, true)
            carol.tasksWriter.setChecklistItemDone(desk, true)
            boris.network.online = true
            carol.network.online = true

            assertConverged(all)
            all.forEach { assertEquals(listOf(true, true), it.detail(id).checklist.map { item -> item.shownDone }) }
        }

    @Test
    fun `a task deleted while another phone ticks an item offline is gone everywhere`() =
        familyTest {
            val id = anna.tasksWriter.create(TaskData("Wash the car", createdBy = "", dueDate = today()), listOf("Bucket"))
            anna.tasksWriter.complete(id)
            anna.sync()
            all.forEach { it.sync() }
            val item =
                anna
                    .detail(id)
                    .checklist
                    .single()
                    .id

            carol.network.online = false
            carol.tasksWriter.setChecklistItemDone(item, true)
            anna.tasksWriter.delete(id)
            anna.sync()

            carol.network.online = true
            assertConverged(all)
            for (client in all) {
                val snapshot = client.snapshot()
                assertTrue(snapshot.tasks.isEmpty() && snapshot.checklistItems.isEmpty() && snapshot.taskCompletions.isEmpty())
            }
            assertEquals(
                ErrorCode.ENTITY_DELETED,
                carol.engine.rejections
                    .first()
                    .single()
                    .code,
            )
        }

    @Test
    fun `3 - a kid's edit allowed by stale permissions is rejected and reverted`() =
        familyTest {
            anna.api.updateGroup(kidsId, UpdateGroupRequest(permissions = PermissionCatalogue.kidsTemplate + Permission.TASKS_EDIT_ANY))
            val id = boris.tasksWriter.create(TaskData("Buy milk", createdBy = ""))
            boris.sync()
            all.forEach { it.sync() }
            carol.permissionsRepository.observeMine().await { it.has(Permission.TASKS_EDIT_ANY) }

            carol.network.online = false
            anna.api.updateGroup(kidsId, UpdateGroupRequest(permissions = PermissionCatalogue.kidsTemplate))
            carol.tasksWriter.update(id, carol.view(id).data.copy(title = "Buy chocolate"))
            assertEquals("Buy chocolate", carol.view(id).data.title)

            carol.network.online = true
            carol.sync()
            assertEquals("Buy milk", carol.view(id).data.title)
            val rejection =
                carol.engine.rejections
                    .first()
                    .single()
            assertEquals(ErrorCode.FORBIDDEN, rejection.code)
            assertEquals(EntityTypes.TASK, rejection.type)
            assertConverged(all)
        }

    @Test
    fun `a member without tasks view has no task rows after the reset`() =
        familyTest {
            val id = anna.tasksWriter.create(TaskData("Water the plants", createdBy = "", dueDate = today()), listOf("Kitchen"))
            anna.tasksWriter.complete(id)
            anna.sync()
            carol.sync()
            assertEquals(1, carol.snapshot().tasks.size)

            val guests = anna.api.createGroup(CreateGroupRequest("Guests", setOf(Permission.CALENDAR_VIEW))).groupId
            anna.api.setMemberGroups(carolMemberId, SetGroupsRequest(setOf(guests)))
            carol.sync()

            val snapshot = carol.snapshot()
            assertTrue(snapshot.tasks.isEmpty() && snapshot.checklistItems.isEmpty() && snapshot.taskCompletions.isEmpty())
            assertEquals(emptyList(), carol.tasks.observeViews().first())
            assertEquals(1, anna.snapshot().tasks.size)
        }
}
