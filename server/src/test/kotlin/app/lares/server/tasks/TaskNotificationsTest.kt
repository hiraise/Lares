package app.lares.server.tasks

import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.api.notifications.PushPayload
import app.lares.contract.sync.EntityTypes
import app.lares.server.AppDeps
import app.lares.server.notifications.FakeDevice
import app.lares.server.notifications.FakePushService
import app.lares.server.notifications.NotificationTexts
import app.lares.server.platform.toContract
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.MEMBER_PASSWORD
import app.lares.server.testing.assertApplied
import app.lares.server.testing.createSecondHousehold
import app.lares.server.testing.delete
import app.lares.server.testing.fields
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.jsonStrings
import app.lares.server.testing.lastSeq
import app.lares.server.testing.login
import app.lares.server.testing.memberSession
import app.lares.server.testing.newCompletion
import app.lares.server.testing.newTask
import app.lares.server.testing.pushOne
import app.lares.server.testing.recurrenceJson
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upsert
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Task notifications on the server (TSK-031…TSK-033, plan M1-03). Deterministic: after each step the test waits for
 * `taskNotifier.awaitIdle()`, by which time every notification has reached the fake push service. Sync tickles that
 * arrive as well are ignored. The household is in Europe/Moscow (UTC+3); the test clock starts on Thursday
 * 2026-10-22 10:00 UTC (13:00 local).
 */
class TaskNotificationsTest {
    private val push = FakePushService()
    private val devices = mutableMapOf<String, FakeDevice>()

    @AfterTest
    fun stop() = push.close()

    private data class Notice(
        val path: String,
        val payload: PushPayload,
    )

    private class Family(
        val admin: AuthResult,
        val adult: AuthResult,
        val kid: AuthResult,
        val kid2: AuthResult,
    ) {
        val adultId get() = adult.me.memberId
        val kidId get() = kid.me.memberId
        val kid2Id get() = kid2.me.memberId
    }

    private fun pushApp(block: suspend ApplicationTestBuilder.(AppDeps) -> Unit) =
        testApp(configure = { it.copy(pushAllowedHosts = setOf("127.0.0.1")) }, block = block)

    private suspend fun HttpClient.register(
        token: String,
        topic: String,
        language: String = "en",
    ) {
        val device = push.device(topic, language)
        devices["/$topic"] = device
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/me/push", token, device.registration).status)
    }

    /** Admin, an adult (en), two kids (ru, en); every member has a registered device named after them. */
    private suspend fun HttpClient.family(deps: AppDeps): Family {
        val admin = setup()
        val adults = setOf(deps.groupId(admin.householdUuid, ADULTS))
        val kids = setOf(deps.groupId(admin.householdUuid, KIDS))
        val family =
            Family(
                admin,
                memberSession(admin.accessToken, "adult", adults),
                memberSession(admin.accessToken, "kid", kids),
                memberSession(admin.accessToken, "kid2", kids),
            )
        register(family.admin.accessToken, "admin")
        register(family.adult.accessToken, "adult")
        register(family.kid.accessToken, "kid", "ru")
        register(family.kid2.accessToken, "kid2")
        return family
    }

    /** Task notifications sent since the last call. */
    private suspend fun AppDeps.notices(): List<Notice> {
        taskNotifier.awaitIdle()
        return push.drain().mapNotNull { request ->
            val payload = devices.getValue(request.path).decrypt(request.body)
            if (payload.kind == PushPayload.KIND_NOTIFICATION) Notice(request.path, payload) else null
        }
    }

    private suspend fun AppDeps.overdueCheckAt(taskId: String): Instant? =
        db.dbQuery {
            TaskTable
                .select(TaskTable.overdueCheckAt)
                .where { TaskTable.id eq UUID.fromString(taskId) }
                .single()[TaskTable.overdueCheckAt]
                ?.toContract()
        }

    private suspend fun AppDeps.runJob(time: String): Int {
        testClock.current = Instant.parse(time)
        return taskOverdueJob.runOnce(clock.now())
    }

    /** A fresh access token after the test clock moved more than an hour. */
    private suspend fun HttpClient.relogin(
        login: String,
        password: String = MEMBER_PASSWORD,
    ): String = login(login, password).body<AuthResult>().accessToken

    private fun assertNotice(
        notice: Notice,
        path: String,
        type: NotificationType,
        title: String,
        body: String,
        taskId: String,
    ) {
        assertEquals(path, notice.path)
        assertEquals(type, notice.payload.type)
        assertEquals(title, notice.payload.title)
        assertEquals(body, notice.payload.body)
        assertEquals(EntityTypes.TASK, notice.payload.entityType)
        assertEquals(taskId, notice.payload.entityId)
    }

    // TSK-031

    @Test
    fun assigneesAreToldInTheirLanguageAndOnlyNewOnes() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            deps.notices()

            val task = deps.newTask(f.adultId, title = "Вынести мусор", assigneeIds = listOf(f.kidId, f.adultId))
            client.pushOne(f.adult.accessToken, task).assertApplied()
            val created = deps.notices()
            assertEquals(1, created.size, "the actor gets nothing: $created")
            assertNotice(
                created.single(),
                "/kid",
                NotificationType.TASK_ASSIGNED,
                "Вам поручена задача",
                "Вынести мусор — от adult",
                task.id,
            )

            val addKid2 = deps.upsert(EntityTypes.TASK, task.id, fields("assigneeIds" to jsonStrings(f.kidId, f.adultId, f.kid2Id)))
            client.pushOne(f.adult.accessToken, addKid2).assertApplied()
            val added = deps.notices()
            assertEquals(1, added.size, "$added")
            assertNotice(added.single(), "/kid2", NotificationType.TASK_ASSIGNED, "New task for you", "Вынести мусор — from adult", task.id)

            // Removals and other edits notify nobody.
            client.pushOne(f.adult.accessToken, deps.upsert(EntityTypes.TASK, task.id, fields("assigneeIds" to jsonStrings(f.kidId))))
            client.pushOne(f.adult.accessToken, deps.upsert(EntityTypes.TASK, task.id, fields("title" to "Renamed")))
            assertEquals(emptyList(), deps.notices())
        }

    @Test
    fun anyoneTaskMutedTypeAndMissingViewSendNothing() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val guest = client.memberSession(f.admin.accessToken, "guest", emptySet())
            client.register(guest.accessToken, "guest")
            deps.notices()

            client.pushOne(f.adult.accessToken, deps.newTask(f.adultId)).assertApplied()
            assertEquals(emptyList(), deps.notices(), "anyone task")

            client.send(
                HttpMethod.Put,
                "/api/v1/me/notification-settings",
                f.kid.accessToken,
                NotificationSettings(setOf(NotificationType.TASK_ASSIGNED)),
            )
            client.pushOne(f.adult.accessToken, deps.newTask(f.adultId, assigneeIds = listOf(f.kidId))).assertApplied()
            assertEquals(emptyList(), deps.notices(), "muted")

            client.pushOne(f.adult.accessToken, deps.newTask(f.adultId, assigneeIds = listOf(guest.me.memberId))).assertApplied()
            assertEquals(emptyList(), deps.notices(), "no tasks.view")
        }

    @Test
    fun longTitlesAreShortened() {
        val long = "x".repeat(150)
        assertEquals(100, NotificationTexts.short(long).length)
        assertEquals("x".repeat(99) + "…", NotificationTexts.short(long))
        assertEquals("x".repeat(100), NotificationTexts.short("x".repeat(100)))
    }

    // TSK-032

    @Test
    fun creatorWhoOptedInIsToldOnceWhenSomeoneElseCompletes() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val task =
                deps.newTask(
                    f.adultId,
                    title = "Homework",
                    assigneeIds = listOf(f.kidId, f.kid2Id),
                    extra = mapOf("notifyCreatorOnDone" to true),
                )
            client.pushOne(f.adult.accessToken, task).assertApplied()
            deps.notices()

            client.pushOne(f.kid.accessToken, deps.newCompletion(task.id, f.kidId)).assertApplied()
            val done = deps.notices()
            assertEquals(1, done.size, "$done")
            assertNotice(done.single(), "/adult", NotificationType.TASK_COMPLETED, "Task done", "Homework — kid", task.id)

            // A second completion of the same occurrence tells nobody.
            client.pushOne(f.kid2.accessToken, deps.newCompletion(task.id, f.kid2Id)).assertApplied()
            assertEquals(emptyList(), deps.notices())
        }

    @Test
    fun ownCompletionSkipAndOptOutSendNothing() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val optIn = mapOf("notifyCreatorOnDone" to true)

            val own = deps.newTask(f.adultId, assigneeIds = listOf(f.adultId), extra = optIn)
            client.pushOne(f.adult.accessToken, own).assertApplied()
            client.pushOne(f.adult.accessToken, deps.newCompletion(own.id, f.adultId)).assertApplied()

            val optOut = deps.newTask(f.adultId, assigneeIds = listOf(f.kidId))
            client.pushOne(f.adult.accessToken, optOut).assertApplied()
            deps.notices()
            client.pushOne(f.kid.accessToken, deps.newCompletion(optOut.id, f.kidId)).assertApplied()
            assertEquals(emptyList(), deps.notices(), "own completion and opt-out")

            val routine =
                deps.newTask(
                    f.adultId,
                    assigneeIds = listOf(f.kidId),
                    extra = optIn + mapOf("dueDate" to "2026-10-22", "recurrence" to recurrenceJson("fixed", "day")),
                )
            client.pushOne(f.adult.accessToken, routine).assertApplied()
            deps.notices()
            client.pushOne(f.kid.accessToken, deps.newCompletion(routine.id, f.kidId, "2026-10-22", skipped = true)).assertApplied()
            assertEquals(emptyList(), deps.notices(), "skip")

            // The next occurrence completed: the creator is told.
            client.pushOne(f.kid.accessToken, deps.newCompletion(routine.id, f.kidId, "2026-10-23")).assertApplied()
            assertEquals(listOf("/adult"), deps.notices().map { it.path })
        }

    // TSK-033

    @Test
    fun timedOneOffIsReportedOnceAtDueTime() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val task =
                deps.newTask(
                    f.adultId,
                    title = "Call grandma",
                    assigneeIds = listOf(f.kidId),
                    extra = mapOf("dueDate" to "2026-10-22", "dueTime" to "15:00"),
                )
            client.pushOne(f.adult.accessToken, task).assertApplied()
            deps.notices()
            assertEquals(Instant.parse("2026-10-22T12:00:00Z"), deps.overdueCheckAt(task.id))

            assertEquals(0, deps.runJob("2026-10-22T11:59:00Z"))
            assertEquals(1, deps.runJob("2026-10-22T12:00:00Z"))
            val notices = deps.notices()
            assertEquals(1, notices.size, "$notices")
            assertNotice(notices.single(), "/adult", NotificationType.TASK_OVERDUE, "Task overdue", "Call grandma", task.id)
            assertNull(deps.overdueCheckAt(task.id))

            assertEquals(0, deps.runJob("2026-10-22T13:00:00Z"))
            assertEquals(emptyList(), deps.notices())
        }

    @Test
    fun dateOnlyTaskIsReportedAtNineNextMorning() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val task = deps.newTask(f.adultId, assigneeIds = listOf(f.kidId), extra = mapOf("dueDate" to "2026-10-22"))
            client.pushOne(f.adult.accessToken, task).assertApplied()
            deps.notices()

            // 09:00 in Moscow is 06:00 UTC.
            assertEquals(0, deps.runJob("2026-10-23T05:59:00Z"))
            assertEquals(1, deps.runJob("2026-10-23T06:00:00Z"))
            assertEquals(listOf("/adult"), deps.notices().map { it.path })
        }

    @Test
    fun completedOrAlreadyPastOrDeletedTasksAreNotReported() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val timed = mapOf("dueDate" to "2026-10-22", "dueTime" to "15:00")

            val completed = deps.newTask(f.adultId, assigneeIds = listOf(f.kidId), extra = timed)
            client.pushOne(f.adult.accessToken, completed).assertApplied()
            client.pushOne(f.kid.accessToken, deps.newCompletion(completed.id, f.kidId, "2026-10-22")).assertApplied()
            assertNull(deps.overdueCheckAt(completed.id))

            // Saved when its date was already past: the notice moment (Oct 22 09:00 local) is before the save.
            val past = deps.newTask(f.adultId, extra = mapOf("dueDate" to "2026-10-21"))
            client.pushOne(f.adult.accessToken, past).assertApplied()

            val deleted = deps.newTask(f.adultId, extra = timed)
            client.pushOne(f.adult.accessToken, deleted).assertApplied()
            client.pushOne(f.adult.accessToken, deps.delete(EntityTypes.TASK, deleted.id)).assertApplied()
            assertNull(deps.overdueCheckAt(deleted.id))
            deps.notices()

            assertEquals(0, deps.runJob("2026-10-22T10:00:00Z"))
            assertEquals(0, deps.runJob("2026-10-22T12:30:00Z"))
            assertEquals(emptyList(), deps.notices())
            assertNull(deps.overdueCheckAt(past.id), "handled once, nothing left to check")
        }

    @Test
    fun fixedRoutineReportsEachMissedOccurrenceOnce() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val routine =
                deps.newTask(
                    f.adultId,
                    assigneeIds = listOf(f.kidId),
                    extra =
                        mapOf(
                            "dueDate" to "2026-10-26",
                            "dueTime" to "18:00",
                            "recurrence" to recurrenceJson("fixed", "week", weekdays = listOf("MONDAY", "THURSDAY")),
                        ),
                )
            client.pushOne(f.adult.accessToken, routine).assertApplied()
            deps.notices()

            // Monday 18:00 local = 15:00 UTC.
            assertEquals(1, deps.runJob("2026-10-26T15:00:00Z"))
            assertEquals(Instant.parse("2026-10-29T15:00:00Z"), deps.overdueCheckAt(routine.id))
            assertEquals(0, deps.runJob("2026-10-27T15:00:00Z"))
            assertEquals(0, deps.runJob("2026-10-29T14:59:00Z"))
            // Thursday missed too.
            assertEquals(1, deps.runJob("2026-10-29T15:00:00Z"))
            assertEquals(listOf("/adult", "/adult"), deps.notices().map { it.path })
        }

    @Test
    fun afterCompletionRoutineIsReportedOnceUntilCompleted() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val routine =
                deps.newTask(
                    f.adultId,
                    assigneeIds = listOf(f.kidId),
                    extra =
                        mapOf(
                            "dueDate" to "2026-10-22",
                            "dueTime" to "15:00",
                            "recurrence" to recurrenceJson("after_completion", "day", interval = 2),
                        ),
                )
            client.pushOne(f.adult.accessToken, routine).assertApplied()
            deps.notices()

            assertEquals(1, deps.runJob("2026-10-22T12:00:00Z"))
            assertNull(deps.overdueCheckAt(routine.id))
            assertEquals(0, deps.runJob("2026-10-24T12:00:00Z"))

            // Completed on Oct 24: next time Oct 26 15:00 local.
            val kidToken = client.relogin("kid")
            client.pushOne(kidToken, deps.newCompletion(routine.id, f.kidId, "2026-10-22")).assertApplied()
            assertEquals(Instant.parse("2026-10-26T12:00:00Z"), deps.overdueCheckAt(routine.id))
            assertEquals(1, deps.runJob("2026-10-26T12:00:00Z"))
            assertEquals(2, deps.notices().size)
        }

    @Test
    fun afterDowntimeOnlyTheCurrentOccurrenceIsReported() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val timed = mapOf("dueDate" to "2026-10-22", "dueTime" to "15:00")
            val oneOff = deps.newTask(f.adultId, extra = timed)
            val daily = deps.newTask(f.adultId, extra = timed + mapOf("recurrence" to recurrenceJson("fixed", "day")))
            client.pushOne(f.adult.accessToken, oneOff).assertApplied()
            client.pushOne(f.adult.accessToken, daily).assertApplied()
            deps.notices()

            // The job was down for three days.
            assertEquals(1, deps.runJob("2026-10-25T12:30:00Z"))
            val notices = deps.notices()
            assertEquals(listOf(daily.id), notices.map { it.payload.entityId }, "the one-off notice is stale")
            assertEquals(0, deps.runJob("2026-10-25T12:31:00Z"))
            assertEquals(Instant.parse("2026-10-26T12:00:00Z"), deps.overdueCheckAt(daily.id))
            assertNull(deps.overdueCheckAt(oneOff.id))
        }

    @Test
    fun oneJobServesEveryHouseholdSeparately() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            deps.createSecondHousehold("admin-b")
            val adminB = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            client.register(adminB.accessToken, "adminB")
            val timed = mapOf("dueDate" to "2026-10-22", "dueTime" to "15:00")
            val taskA = deps.newTask(f.adultId, extra = timed)
            val taskB = deps.newTask(adminB.me.memberId, extra = timed)
            client.pushOne(f.adult.accessToken, taskA).assertApplied()
            client.pushOne(adminB.accessToken, taskB).assertApplied()
            deps.notices()

            assertEquals(2, deps.runJob("2026-10-22T12:00:00Z"))
            val byPath = deps.notices().associate { it.path to it.payload.entityId }
            assertEquals(mapOf("/adult" to taskA.id, "/adminB" to taskB.id), byPath)
        }

    @Test
    fun editsRecomputeTheCheckTimeWithoutNewSeq() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val task = deps.newTask(f.adultId, extra = mapOf("dueDate" to "2026-10-22", "dueTime" to "15:00"))
            client.pushOne(f.adult.accessToken, task).assertApplied()
            val seq =
                client
                    .pushOne(
                        f.adult.accessToken,
                        deps.upsert(EntityTypes.TASK, task.id, fields("dueTime" to "18:00")),
                    ).assertApplied()
            assertEquals(seq, deps.lastSeq(f.admin.householdUuid), "refresh writes no seq")
            assertEquals(Instant.parse("2026-10-22T15:00:00Z"), deps.overdueCheckAt(task.id))
            deps.notices()

            assertEquals(0, deps.runJob("2026-10-22T12:00:00Z"))
            assertEquals(1, deps.runJob("2026-10-22T15:00:00Z"))
            assertEquals(seq, deps.lastSeq(f.admin.householdUuid), "the job writes no seq")
            assertEquals(1, deps.notices().size)
        }

    @Test
    fun overdueCheckSurvivesManyDays() =
        pushApp { deps ->
            // The creator's session outlives the access token: notices reach a phone that has been offline for days.
            val client = jsonClient()
            val f = client.family(deps)
            val task = deps.newTask(f.adultId, extra = mapOf("dueDate" to "2026-10-30"))
            client.pushOne(f.adult.accessToken, task).assertApplied()
            deps.notices()
            assertEquals(1, deps.runJob("2026-10-31T06:00:00Z"))
            assertEquals(listOf("/adult"), deps.notices().map { it.path })
        }
}
