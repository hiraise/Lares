package app.lares.server.tasks

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.GroupCreated
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import app.lares.server.AppDeps
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertRejected
import app.lares.server.testing.delete
import app.lares.server.testing.fields
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.lastSeq
import app.lares.server.testing.memberSession
import app.lares.server.testing.newChecklistItem
import app.lares.server.testing.newCompletion
import app.lares.server.testing.newTask
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.upsert
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tasks spec § Permissions and privacy (TSK-001…TSK-004, Q-017): one test per permission row, enforced by `TaskRules`. */
class TaskPermissionsTest {
    private class Family(
        val admin: AuthResult,
        val adult: AuthResult,
        val kid: AuthResult,
        val kid2: AuthResult,
    )

    private suspend fun HttpClient.family(deps: AppDeps): Family {
        val admin = setup()
        val adults = setOf(deps.groupId(admin.householdUuid, ADULTS))
        val kids = setOf(deps.groupId(admin.householdUuid, KIDS))
        return Family(
            admin,
            memberSession(admin.accessToken, "adult", adults),
            memberSession(admin.accessToken, "kid", kids),
            memberSession(admin.accessToken, "kid2", kids),
        )
    }

    /** TSK-001: Kids create their own tasks, never on someone else's behalf. */
    @Test
    fun kidsCreateOwnTasks() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            client.pushOne(f.kid.accessToken, deps.newTask(f.kid.me.memberId)).assertApplied()
            client.pushOne(f.kid.accessToken, deps.newTask(f.adult.me.memberId)).assertRejected(ErrorCode.FORBIDDEN)
        }

    /** TSK-001: only the creator or `tasks.edit_any` edits and deletes a task. */
    @Test
    fun editingNeedsCreatorOrEditAny() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val adultsTask = deps.newTask(f.adult.me.memberId, assigneeIds = listOf(f.kid.me.memberId))
            client.pushOne(f.adult.accessToken, adultsTask).assertApplied()
            client
                .pushOne(f.kid.accessToken, deps.upsert(EntityTypes.TASK, adultsTask.id, fields("title" to "Mine now")))
                .assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(f.kid.accessToken, deps.delete(EntityTypes.TASK, adultsTask.id)).assertRejected(ErrorCode.FORBIDDEN)

            // Adults have tasks.edit_any; the creator edits their own task.
            val kidsTask = deps.newTask(f.kid.me.memberId)
            client.pushOne(f.kid.accessToken, kidsTask).assertApplied()
            client
                .pushOne(f.adult.accessToken, deps.upsert(EntityTypes.TASK, kidsTask.id, fields("title" to "Tidy up")))
                .assertApplied()
            client
                .pushOne(f.kid.accessToken, deps.upsert(EntityTypes.TASK, kidsTask.id, fields("notes" to "Toys first")))
                .assertApplied()
            client.pushOne(f.adult.accessToken, deps.delete(EntityTypes.TASK, kidsTask.id)).assertApplied()
        }

    /** TSK-003, Q-017: assignees and everybody for "anyone" tasks complete; others do not; a completion is always one's own. */
    @Test
    fun completingNeedsAssigneeOrAnyone() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val forKid = deps.newTask(f.adult.me.memberId, assigneeIds = listOf(f.kid.me.memberId))
            val forAdult = deps.newTask(f.adult.me.memberId, assigneeIds = listOf(f.adult.me.memberId))
            val anyone = deps.newTask(f.adult.me.memberId)
            listOf(forKid, forAdult, anyone).forEach { client.pushOne(f.adult.accessToken, it).assertApplied() }

            client.pushOne(f.kid.accessToken, deps.newCompletion(forKid.id, f.kid.me.memberId)).assertApplied()
            client.pushOne(f.kid.accessToken, deps.newCompletion(forAdult.id, f.kid.me.memberId)).assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(f.kid.accessToken, deps.newCompletion(anyone.id, f.kid.me.memberId)).assertApplied()
            // Even an administrator cannot write a completion in someone else's name.
            client.pushOne(f.admin.accessToken, deps.newCompletion(anyone.id, f.kid.me.memberId)).assertRejected(ErrorCode.FORBIDDEN)
        }

    /** TSK-004: an assignee ticks checklist items but cannot add or rename them. */
    @Test
    fun assigneesTickButDoNotEditChecklist() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val task = deps.newTask(f.adult.me.memberId, assigneeIds = listOf(f.kid.me.memberId))
            val item = deps.newChecklistItem(task.id, "Milk")
            client.pushOne(f.adult.accessToken, task).assertApplied()
            client.pushOne(f.adult.accessToken, item).assertApplied()

            client.pushOne(f.kid.accessToken, deps.upsert(EntityTypes.CHECKLIST_ITEM, item.id, fields("done" to true))).assertApplied()
            client
                .pushOne(f.kid.accessToken, deps.upsert(EntityTypes.CHECKLIST_ITEM, item.id, fields("text" to "Chocolate")))
                .assertRejected(ErrorCode.FORBIDDEN)
            client
                .pushOne(f.kid.accessToken, deps.upsert(EntityTypes.CHECKLIST_ITEM, item.id, fields("done" to false, "position" to 5)))
                .assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(f.kid.accessToken, deps.newChecklistItem(task.id, "Candy")).assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(f.kid.accessToken, deps.delete(EntityTypes.CHECKLIST_ITEM, item.id)).assertRejected(ErrorCode.FORBIDDEN)
            // Someone who is neither assignee nor editor cannot tick.
            client
                .pushOne(f.kid2.accessToken, deps.upsert(EntityTypes.CHECKLIST_ITEM, item.id, fields("done" to false)))
                .assertRejected(ErrorCode.FORBIDDEN)
        }

    /** TSK-003: the author or whoever may edit the task undoes a completion; nobody else. */
    @Test
    fun undoingNeedsAuthorOrEditor() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            // The creator (a kid) undoes another kid's completion of their task.
            val kidsTask = deps.newTask(f.kid.me.memberId)
            client.pushOne(f.kid.accessToken, kidsTask).assertApplied()
            val byKid2 = deps.newCompletion(kidsTask.id, f.kid2.me.memberId)
            client.pushOne(f.kid2.accessToken, byKid2).assertApplied()
            client.pushOne(f.kid.accessToken, deps.delete(EntityTypes.TASK_COMPLETION, byKid2.id)).assertApplied()

            // A kid cannot undo another member's completion of an adult's task, but the author can.
            val adultsTask = deps.newTask(f.adult.me.memberId)
            client.pushOne(f.adult.accessToken, adultsTask).assertApplied()
            val done = deps.newCompletion(adultsTask.id, f.kid2.me.memberId)
            client.pushOne(f.kid2.accessToken, done).assertApplied()
            client.pushOne(f.kid.accessToken, deps.delete(EntityTypes.TASK_COMPLETION, done.id)).assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(f.kid2.accessToken, deps.delete(EntityTypes.TASK_COMPLETION, done.id)).assertApplied()
        }

    /** TSK-015: members without `tasks.view` get no task rows (the cursor still advances) and cannot write tasks. */
    @Test
    fun withoutTasksViewNothingIsVisible() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val response =
                client.send(
                    HttpMethod.Post,
                    "/api/v1/groups",
                    f.admin.accessToken,
                    CreateGroupRequest("Shoppers", setOf(Permission.SHOPPING_VIEW)),
                )
            assertEquals(HttpStatusCode.Created, response.status)
            val group = UUID.fromString(response.body<GroupCreated>().groupId)
            val shopper = client.memberSession(f.admin.accessToken, "shopper", setOf(group))

            val task = deps.newTask(f.adult.me.memberId)
            client.pushOne(f.adult.accessToken, task).assertApplied()
            client.pushOne(f.adult.accessToken, deps.newChecklistItem(task.id, "Milk")).assertApplied()
            client.pushOne(f.adult.accessToken, deps.newCompletion(task.id, f.adult.me.memberId)).assertApplied()

            val (last, changes) = client.pullAll(shopper.accessToken)
            val taskTypes = setOf(EntityTypes.TASK, EntityTypes.CHECKLIST_ITEM, EntityTypes.TASK_COMPLETION)
            assertTrue(changes.none { it.type in taskTypes })
            assertEquals(deps.lastSeq(f.admin.householdUuid), last.cursor)
            client.pushOne(shopper.accessToken, deps.newTask(shopper.me.memberId)).assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(shopper.accessToken, deps.newCompletion(task.id, shopper.me.memberId)).assertRejected(ErrorCode.FORBIDDEN)
        }
}
