package app.lares.client.ui

import app.lares.client.db.LocalDb
import app.lares.client.ui.FamilyFixture.change
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.ChecklistItemData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Tasks of the [FamilyFixture] household (Moscow zone), dated relative to the real clock:
 * - `t-over` "Pay internet": yesterday 21:00, Boris, by Olga (overdue);
 * - `t-today` "Pick up Carol": today, Olga, tag `t1`;
 * - `t-kid` "Doctor": tomorrow, Carol, by Olga;
 * - `t-any` "Call the plumber": no date, anyone, by Boris;
 * - `t-routine` "Trash": daily since 5 days ago, Carol, by Olga, two checklist items, done 2 and 1 days ago.
 */
object TaskUiFixture {
    val zone = TimeZone.of("Europe/Moscow")
    val today =
        Clock.System
            .now()
            .toLocalDateTime(zone)
            .date
    private val yesterday = today.minus(1, DateTimeUnit.DAY)

    private fun at(daysAgo: Int) = LocalDateTime(today.minus(daysAgo, DateTimeUnit.DAY), LocalTime(19, 40)).toInstant(zone)

    val changes =
        listOf(
            task("t-over", TaskData("Pay internet", "m1", dueDate = yesterday, dueTime = LocalTime(21, 0), assigneeIds = setOf("m2"))),
            task("t-today", TaskData("Pick up Carol", "m1", dueDate = today, assigneeIds = setOf("m1"), tagIds = setOf("t1"))),
            task("t-kid", TaskData("Doctor", "m1", dueDate = today.plus(1, DateTimeUnit.DAY), assigneeIds = setOf("m3"))),
            task("t-any", TaskData("Call the plumber", "m2", notes = "Leak on the balcony")),
            task(
                "t-routine",
                TaskData(
                    "Trash",
                    "m1",
                    dueDate = today.minus(5, DateTimeUnit.DAY),
                    assigneeIds = setOf("m3"),
                    recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.DAY),
                ),
            ),
            change(EntityTypes.CHECKLIST_ITEM, "i1", ChecklistItemData.serializer(), ChecklistItemData("t-routine", "Kitchen bin", 0)),
            change(EntityTypes.CHECKLIST_ITEM, "i2", ChecklistItemData.serializer(), ChecklistItemData("t-routine", "Bathroom bin", 1)),
            completion("c-old", TaskCompletionData("t-routine", "m3", at(2), today.minus(2, DateTimeUnit.DAY))),
            completion("c-new", TaskCompletionData("t-routine", "m2", at(1), yesterday)),
        )

    private fun task(
        id: String,
        data: TaskData,
    ) = change(EntityTypes.TASK, id, TaskData.serializer(), data)

    private fun completion(
        id: String,
        data: TaskCompletionData,
    ) = change(EntityTypes.TASK_COMPLETION, id, TaskCompletionData.serializer(), data)

    fun core(me: Me = FamilyFixture.olga): UiTestCore = FamilyFixture.core(me, changes)

    /** Entity types and ids waiting in the outbox. */
    fun outbox(core: UiTestCore): List<String> =
        runBlocking {
            core.koin.get<LocalDb>().read {
                sync_outboxQueries.peek(100).executeAsList().map { "${it.type}:${it.entity_id}" }
            }
        }

    fun <T> next(flow: Flow<T>): T = runBlocking { withTimeout(5_000) { flow.first() } }
}
