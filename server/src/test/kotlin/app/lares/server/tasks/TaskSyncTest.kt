package app.lares.server.tasks

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationResult
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.testing.ADMIN_LOGIN
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.ADULTS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertRejected
import app.lares.server.testing.createSecondHousehold
import app.lares.server.testing.dataAs
import app.lares.server.testing.delete
import app.lares.server.testing.fields
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.jsonStrings
import app.lares.server.testing.lastSeq
import app.lares.server.testing.login
import app.lares.server.testing.memberSession
import app.lares.server.testing.newChecklistItem
import app.lares.server.testing.newCompletion
import app.lares.server.testing.newTag
import app.lares.server.testing.newTask
import app.lares.server.testing.pull
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.recurrenceJson
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upsert
import io.ktor.client.call.body
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Tasks on the server (M1-02): TSK-001…TSK-005, TSK-010, TSK-015, TAG-002 — storage, sync, validation, cascade, isolation. */
class TaskSyncTest {
    /** TSK-001, TSK-002, TSK-010: one-off and routine round trip; field-level edits from two sessions both survive. */
    @Test
    fun createPullAndFieldLevelEdits() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val adult = client.memberSession(admin.accessToken, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val tag = deps.newTag("Home")
            client.pushOne(admin.accessToken, tag).assertApplied()

            val oneOff =
                deps.newTask(
                    admin.me.memberId,
                    " Pay rent ",
                    assigneeIds = listOf(adult.me.memberId),
                    extra =
                        mapOf(
                            "notes" to "  ",
                            "dueDate" to "2026-10-30",
                            "dueTime" to "20:00",
                            "tagIds" to jsonStrings(tag.id),
                            "reminderMinutesBefore" to 60,
                            "notifyCreatorOnDone" to true,
                        ),
                )
            val routine =
                deps.newTask(
                    admin.me.memberId,
                    "Water plants",
                    extra =
                        mapOf(
                            "dueDate" to "2026-10-21",
                            "recurrence" to recurrenceJson("fixed", "week", 2, listOf("MONDAY", "THURSDAY")),
                        ),
                )
            client.pushOne(admin.accessToken, oneOff).assertApplied()
            client.pushOne(admin.accessToken, routine).assertApplied()

            val tasks = tasksOf(client.pull(adult.accessToken).changes)
            assertEquals(
                TaskData(
                    title = "Pay rent",
                    createdBy = admin.me.memberId,
                    dueDate = LocalDate(2026, 10, 30),
                    dueTime = LocalTime(20, 0),
                    assigneeIds = setOf(adult.me.memberId),
                    tagIds = setOf(tag.id),
                    reminderMinutesBefore = 60,
                    notifyCreatorOnDone = true,
                ),
                tasks[oneOff.id],
            )
            assertEquals(
                Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 2, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
                tasks[routine.id]?.recurrence,
            )

            // Two sessions change different fields of the same task.
            client.pushOne(admin.accessToken, deps.upsert(EntityTypes.TASK, oneOff.id, fields("title" to "Pay the rent"))).assertApplied()
            client
                .pushOne(adult.accessToken, deps.upsert(EntityTypes.TASK, oneOff.id, fields("assigneeIds" to jsonStrings())))
                .assertApplied()
            val edited = assertNotNull(tasksOf(client.pull(admin.accessToken).changes)[oneOff.id])
            assertEquals("Pay the rent", edited.title)
            assertEquals(emptySet(), edited.assigneeIds)
        }

    /** TSK-001, TSK-003, TSK-010: field rules and the server-only checks. */
    @Test
    fun invalidTasksAreRejected() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId

            suspend fun push(vararg extra: Pair<String, Any?>): MutationResult =
                client.pushOne(token, deps.newTask(me, extra = mapOf(*extra)))

            push("title" to "  ").assertField("title", FieldErrorCode.REQUIRED)
            client
                .pushOne(token, deps.upsert(EntityTypes.TASK, Ids.newId(), fields("createdBy" to me)))
                .assertField("title", FieldErrorCode.REQUIRED)
            push("dueTime" to "20:00").assertField("dueDate", FieldErrorCode.REQUIRED)
            push("recurrence" to recurrenceJson()).assertField("dueDate", FieldErrorCode.REQUIRED)
            push("dueDate" to "2026-10-23", "recurrence" to recurrenceJson("fixed", "week"))
                .assertField("recurrence", FieldErrorCode.INVALID_FORMAT)
            push("dueDate" to "2026-10-23", "recurrence" to recurrenceJson("fixed", "day", weekdays = listOf("MONDAY")))
                .assertField("recurrence", FieldErrorCode.INVALID_FORMAT)
            push("dueDate" to "2026-10-23", "recurrence" to recurrenceJson("fixed", "week", weekdays = listOf("FUNDAY")))
                .assertField("recurrence", FieldErrorCode.INVALID_FORMAT)
            // A rule with an unknown mode decodes leniently to null; the server must not store a one-off instead.
            push("dueDate" to "2026-10-23", "recurrence" to recurrenceJson("on_full_moon", "day"))
                .assertField("recurrence", FieldErrorCode.INVALID_FORMAT)
            push("dueDate" to "2026-10-23", "recurrence" to recurrenceJson(unit = "fortnight"))
                .assertField("recurrence", FieldErrorCode.INVALID_FORMAT)
            push("assigneeIds" to jsonStrings(Ids.newId())).assertField("assigneeIds", FieldErrorCode.INVALID_FORMAT)
            push("assigneeIds" to jsonStrings("not-a-uuid")).assertField("assigneeIds", FieldErrorCode.INVALID_FORMAT)
            push("tagIds" to jsonStrings(Ids.newId())).assertField("tagIds", FieldErrorCode.INVALID_FORMAT)

            // A tag of another household.
            deps.createSecondHousehold()
            val other = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            val foreignTag = deps.newTag("Theirs")
            client.pushOne(other.accessToken, foreignTag).assertApplied()
            push("tagIds" to jsonStrings(foreignTag.id)).assertField("tagIds", FieldErrorCode.INVALID_FORMAT)

            // Routine → one-off is not allowed (ADR-0017); one-off → routine is.
            val routine = deps.newTask(me, extra = mapOf("dueDate" to "2026-10-23", "recurrence" to recurrenceJson()))
            client.pushOne(token, routine).assertApplied()
            client
                .pushOne(token, deps.upsert(EntityTypes.TASK, routine.id, fields("recurrence" to null)))
                .assertField("recurrence", FieldErrorCode.INVALID_FORMAT)
            val oneOff = deps.newTask(me, extra = mapOf("dueDate" to "2026-10-23"))
            client.pushOne(token, oneOff).assertApplied()
            client.pushOne(token, deps.upsert(EntityTypes.TASK, oneOff.id, fields("recurrence" to recurrenceJson()))).assertApplied()

            // The creator never changes.
            val adult = client.memberSession(token, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            client
                .pushOne(token, deps.upsert(EntityTypes.TASK, oneOff.id, fields("createdBy" to adult.me.memberId)))
                .assertField("createdBy", FieldErrorCode.INVALID_FORMAT)
        }

    /** TSK-003, TSK-004, TSK-015: completion and checklist rules that need the parent task. */
    @Test
    fun childrenOfTasks() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val oneOff = deps.newTask(me)
            val routine = deps.newTask(me, extra = mapOf("dueDate" to "2026-10-20", "recurrence" to recurrenceJson()))
            client.pushOne(token, oneOff).assertApplied()
            client.pushOne(token, routine).assertApplied()

            // Skipping is for routines only; completion time at most one day ahead.
            client.pushOne(token, deps.newCompletion(oneOff.id, me, skipped = true)).assertField("skipped", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newCompletion(routine.id, me, "2026-10-21", skipped = true))
                .assertApplied()
            client
                .pushOne(token, deps.newCompletion(routine.id, me, "2026-10-22", at = deps.clock.now() + 2.days))
                .assertField("completedAt", FieldErrorCode.OUT_OF_RANGE)

            // Completions are immutable; an identical upsert is a no-op.
            val at = Instant.parse("2026-10-22T09:30:00Z")
            val completion = deps.newCompletion(routine.id, me, "2026-10-22", at = at)
            client.pushOne(token, completion).assertApplied()
            assertNull(client.pushOne(token, deps.upsert(EntityTypes.TASK_COMPLETION, completion.id, completion.fields!!)).assertApplied())
            client
                .pushOne(token, deps.upsert(EntityTypes.TASK_COMPLETION, completion.id, fields("occurrence" to "2026-10-23")))
                .assertField("occurrence", FieldErrorCode.INVALID_FORMAT)
            assertEquals(
                TaskCompletionData(routine.id, me, at, LocalDate(2026, 10, 22)),
                client
                    .pull(token)
                    .changes
                    .single { it.id == completion.id }
                    .dataAs<TaskCompletionData>(),
            )

            // A checklist item never moves to another task.
            val item = deps.newChecklistItem(oneOff.id, " Milk ", 1)
            client.pushOne(token, item).assertApplied()
            assertEquals(
                ChecklistItemData(oneOff.id, "Milk", 1),
                client
                    .pull(token)
                    .changes
                    .single { it.id == item.id }
                    .dataAs<ChecklistItemData>(),
            )
            client
                .pushOne(token, deps.upsert(EntityTypes.CHECKLIST_ITEM, item.id, fields("taskId" to routine.id)))
                .assertField("taskId", FieldErrorCode.INVALID_FORMAT)
            client.pushOne(token, deps.newChecklistItem(oneOff.id, "X", -1)).assertField("position", FieldErrorCode.OUT_OF_RANGE)

            // Children of a missing, deleted or foreign task.
            client.pushOne(token, deps.newChecklistItem(Ids.newId(), "Eggs")).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            client.pushOne(token, deps.delete(EntityTypes.TASK, oneOff.id)).assertApplied()
            client.pushOne(token, deps.newChecklistItem(oneOff.id, "Eggs")).assertRejected(ErrorCode.ENTITY_DELETED)
            client.pushOne(token, deps.newCompletion(oneOff.id, me)).assertRejected(ErrorCode.ENTITY_DELETED)

            deps.createSecondHousehold()
            val other = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            val foreign = deps.newTask(other.me.memberId)
            client.pushOne(other.accessToken, foreign).assertApplied()
            client.pushOne(token, deps.newChecklistItem(foreign.id, "Eggs")).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            client.pushOne(token, deps.newCompletion(foreign.id, me)).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
        }

    /** TSK-005: deleting a task tombstones its checklist items and completions, each with its own seq. */
    @Test
    fun deleteCascadesToChildren() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val task = deps.newTask(me, extra = mapOf("dueDate" to "2026-10-20", "recurrence" to recurrenceJson()))
            client.pushOne(token, task).assertApplied()
            val children =
                listOf(
                    deps.newChecklistItem(task.id, "One", 0),
                    deps.newChecklistItem(task.id, "Two", 1),
                    deps.newCompletion(task.id, me, "2026-10-20"),
                    deps.newCompletion(task.id, me, "2026-10-21"),
                )
            children.forEach { client.pushOne(token, it).assertApplied() }
            val (before, _) = client.pullAll(token)

            client.pushOne(token, deps.delete(EntityTypes.TASK, task.id)).assertApplied()

            val tombstones = client.pull(token, before.cursor, accessVersion = before.accessVersion, epoch = before.epoch).changes
            assertEquals((children.map { it.id } + task.id).toSet(), tombstones.map { it.id }.toSet())
            assertTrue(tombstones.all { it.deleted && it.data == null })
            assertEquals(5, tombstones.map { it.seq }.toSet().size, "distinct seqs")
            val (_, paged) = client.pullAll(token, before, limit = 2)
            assertEquals(tombstones.map { it.id }, paged.map { it.id })
            // Deleting again is a no-op.
            assertNull(client.pushOne(token, deps.delete(EntityTypes.TASK, task.id)).assertApplied())
        }

    /** ADR-0010: another household can neither read nor write this household's tasks, items or completions. */
    @Test
    fun householdsAreIsolated() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val task = deps.newTask(me)
            val item = deps.newChecklistItem(task.id, "Milk")
            val completion = deps.newCompletion(task.id, me)
            listOf(task, item, completion).forEach { client.pushOne(token, it).assertApplied() }

            deps.createSecondHousehold()
            val other = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            val ids = setOf(task.id, item.id, completion.id)
            assertTrue(client.pullAll(other.accessToken).second.none { it.id in ids })

            // Same ids from the other household: upserts are not found, deletes are no-ops.
            client.pushOne(other.accessToken, deps.newTask(other.me.memberId, id = task.id)).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            val theirTask = deps.newTask(other.me.memberId)
            client.pushOne(other.accessToken, theirTask).assertApplied()
            client
                .pushOne(other.accessToken, deps.newChecklistItem(theirTask.id, "X", id = item.id))
                .assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            client
                .pushOne(other.accessToken, deps.newCompletion(theirTask.id, other.me.memberId, id = completion.id))
                .assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            for ((type, id) in listOf(
                EntityTypes.TASK to task.id,
                EntityTypes.CHECKLIST_ITEM to item.id,
                EntityTypes.TASK_COMPLETION to completion.id,
            )) {
                assertNull(client.pushOne(other.accessToken, deps.delete(type, id)).assertApplied())
            }
            val live = client.pullAll(token).second.filter { it.id in ids }
            assertEquals(3, live.size)
            assertTrue(live.none { it.deleted })
        }

    /** Housekeeping purges task tombstones after 90 days and raises the purge horizon. */
    @Test
    fun tombstonesArePurged() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            var token = admin.accessToken
            val me = admin.me.memberId
            val task = deps.newTask(me)
            client.pushOne(token, task).assertApplied()
            client.pushOne(token, deps.newChecklistItem(task.id, "Milk")).assertApplied()
            client.pushOne(token, deps.newCompletion(task.id, me)).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.TASK, task.id)).assertApplied()
            val lastSeq = deps.lastSeq(admin.householdUuid)

            assertEquals(0, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            deps.testClock.advance(91.days)
            token = client.login(ADMIN_LOGIN, ADMIN_PASSWORD).body<AuthResult>().accessToken
            assertEquals(3, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            val horizon =
                deps.db.dbQuery {
                    HouseholdSyncStateTable
                        .selectAll()
                        .where { HouseholdSyncStateTable.householdId eq admin.householdUuid }
                        .single()[HouseholdSyncStateTable.purgeHorizonSeq]
                }
            assertEquals(lastSeq, horizon)
            assertNull(deps.db.dbQuery { TaskRepository.load(UUID.fromString(task.id)) })
        }

    /** TAG-002: a stored tag that was deleted since stays on the task through unrelated edits. */
    @Test
    fun deletedTagStaysOnUnrelatedEdit() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val tag = deps.newTag("Car")
            client.pushOne(token, tag).assertApplied()
            val task = deps.newTask(admin.me.memberId, extra = mapOf("tagIds" to jsonStrings(tag.id)))
            client.pushOne(token, task).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.TAG, tag.id)).assertApplied()

            client.pushOne(token, deps.upsert(EntityTypes.TASK, task.id, fields("title" to "Wash the car"))).assertApplied()
            assertEquals(setOf(tag.id), tasksOf(client.pull(token).changes)[task.id]?.tagIds)
            // Adding it again is rejected: it is no longer a live tag.
            val another = deps.newTask(admin.me.memberId, extra = mapOf("tagIds" to jsonStrings(tag.id)))
            client.pushOne(token, another).assertField("tagIds", FieldErrorCode.INVALID_FORMAT)
        }
}

internal fun tasksOf(changes: List<app.lares.contract.sync.Change>): Map<String, TaskData> =
    changes.filter { it.type == EntityTypes.TASK && !it.deleted }.associate { it.id to it.dataAs<TaskData>() }

internal fun MutationResult.assertField(
    field: String,
    code: FieldErrorCode,
) {
    assertRejected(ErrorCode.VALIDATION_FAILED)
    assertEquals(code, error?.fields?.get(field), "result: $this")
}
