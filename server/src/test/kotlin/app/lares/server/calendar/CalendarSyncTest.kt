package app.lares.server.calendar

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.entities.EventData
import app.lares.contract.entities.EventKind
import app.lares.contract.entities.EventOccurrenceData
import app.lares.contract.entities.EventReminderData
import app.lares.contract.entities.Recurrence
import app.lares.contract.entities.RecurrenceMode
import app.lares.contract.entities.RecurrenceUnit
import app.lares.contract.sync.Change
import app.lares.contract.sync.EntityTypes
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.tasks.assertField
import app.lares.server.testing.ADMIN_LOGIN
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.ADULTS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertRejected
import app.lares.server.testing.createRelative
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
import app.lares.server.testing.newEvent
import app.lares.server.testing.newEventReminder
import app.lares.server.testing.newOccurrenceRow
import app.lares.server.testing.newTag
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
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** Calendar on the server (M3-02): CAL-001…CAL-005, CAL-013, CAL-015, CAL-017, TAG-002 — storage, sync, checks, cascade. */
class CalendarSyncTest {
    /** CAL-001, CAL-002, CAL-003, CAL-013, TAG-002: round trip of a repeating event; edits from two sessions both survive. */
    @Test
    fun cal001CreatePullAndFieldLevelEdits() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val adult = client.memberSession(admin.accessToken, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val grandma = deps.createRelative(admin.householdUuid, "Grandma").toString()
            val tag = deps.newTag("School")
            client.pushOne(admin.accessToken, tag).assertApplied()

            val event =
                deps.newEvent(
                    admin.me.memberId,
                    " Swimming ",
                    startDate = "2026-11-02",
                    participantIds = listOf(adult.me.personId, grandma),
                    extra =
                        mapOf(
                            "place" to " Pool ",
                            "notes" to "  ",
                            "tagIds" to jsonStrings(tag.id),
                            "recurrence" to recurrenceJson("fixed", "week", 1, listOf("MONDAY", "WEDNESDAY")),
                            "repeatUntil" to "2026-12-31",
                            "reminderMinutesBefore" to 30,
                        ),
                )
            val trip = deps.newEvent(admin.me.memberId, "Trip", "2026-11-10", "2026-11-14", allDay = true)
            val anniversary =
                deps.newEvent(
                    admin.me.memberId,
                    "Wedding",
                    "2001-06-16",
                    allDay = true,
                    extra = mapOf("kind" to "memorable_date", "countYears" to true, "recurrence" to recurrenceJson("fixed", "year")),
                )
            listOf(event, trip, anniversary).forEach { client.pushOne(admin.accessToken, it).assertApplied() }

            val events = eventsOf(client.pull(adult.accessToken).changes)
            assertEquals(
                EventData(
                    title = "Swimming",
                    createdBy = admin.me.memberId,
                    startDate = LocalDate(2026, 11, 2),
                    endDate = LocalDate(2026, 11, 2),
                    startTime = LocalTime(10, 0),
                    endTime = LocalTime(11, 0),
                    place = "Pool",
                    participantIds = setOf(adult.me.personId, grandma),
                    tagIds = setOf(tag.id),
                    recurrence = Recurrence(RecurrenceMode.FIXED, RecurrenceUnit.WEEK, 1, setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
                    repeatUntil = LocalDate(2026, 12, 31),
                    reminderMinutesBefore = 30,
                ),
                events[event.id],
            )
            assertEquals(LocalDate(2026, 11, 14), events[trip.id]?.endDate)
            assertEquals(true, events[trip.id]?.allDay)
            assertEquals(EventKind.MEMORABLE_DATE, events[anniversary.id]?.kind)
            assertEquals(true, events[anniversary.id]?.countYears)

            // Two sessions change different fields of the same event.
            client.pushOne(admin.accessToken, deps.upsert(EntityTypes.EVENT, event.id, fields("title" to "Swim"))).assertApplied()
            client
                .pushOne(adult.accessToken, deps.upsert(EntityTypes.EVENT, event.id, fields("participantIds" to jsonStrings())))
                .assertApplied()
            val edited = assertNotNull(eventsOf(client.pull(admin.accessToken).changes)[event.id])
            assertEquals("Swim", edited.title)
            assertEquals(emptySet(), edited.participantIds)
            assertEquals(LocalDate(2026, 12, 31), edited.repeatUntil)
        }

    /** CAL-001, CAL-002, CAL-015: field rules, immutable fields and the references the server checks. */
    @Test
    fun cal001InvalidEventsAreRejected() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId

            client.pushOne(token, deps.newEvent(me, "  ")).assertField("title", FieldErrorCode.REQUIRED)
            client
                .pushOne(token, deps.newEvent(me, extra = mapOf("endTime" to "09:00")))
                .assertField("endDate", FieldErrorCode.OUT_OF_RANGE)
            client
                .pushOne(token, deps.newEvent(me, extra = mapOf("repeatUntil" to "2026-12-01")))
                .assertField("repeatUntil", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newEvent(me, extra = mapOf("recurrence" to recurrenceJson("after_completion", "day"))))
                .assertField("recurrence", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newEvent(me, extra = mapOf("kind" to "festival")))
                .assertField("kind", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newEvent(me, participantIds = listOf(Ids.newId())))
                .assertField("participantIds", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newEvent(me, participantIds = listOf("not-a-uuid")))
                .assertField("participantIds", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newEvent(me, extra = mapOf("tagIds" to jsonStrings(Ids.newId()))))
                .assertField("tagIds", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newEvent(me, extra = mapOf("splitFrom" to Ids.newId())))
                .assertField("splitFrom", FieldErrorCode.INVALID_FORMAT)

            // Immutable fields of an existing event.
            val event = deps.newEvent(me, extra = mapOf("recurrence" to recurrenceJson("fixed", "day")))
            client.pushOne(token, event).assertApplied()
            val adult = client.memberSession(token, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            client
                .pushOne(token, deps.upsert(EntityTypes.EVENT, event.id, fields("createdBy" to adult.me.memberId)))
                .assertField("createdBy", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.upsert(EntityTypes.EVENT, event.id, fields("splitFrom" to Ids.newId())))
                .assertField("splitFrom", FieldErrorCode.INVALID_FORMAT)
            val memorable =
                deps.newEvent(
                    me,
                    "Wedding",
                    allDay = true,
                    extra = mapOf("kind" to "memorable_date", "recurrence" to recurrenceJson("fixed", "year")),
                )
            client.pushOne(token, memorable).assertApplied()
            client
                .pushOne(token, deps.upsert(EntityTypes.EVENT, memorable.id, fields("kind" to "event", "recurrence" to null)))
                .assertField("kind", FieldErrorCode.INVALID_FORMAT)

            // "This and following": the new series may point to an event of the household, even a deleted one.
            client.pushOne(token, deps.delete(EntityTypes.EVENT, event.id)).assertApplied()
            val tail = deps.newEvent(me, startDate = "2026-11-10", extra = mapOf("splitFrom" to event.id))
            client.pushOne(token, tail).assertApplied()
            assertEquals(event.id, eventsOf(client.pull(token).changes)[tail.id]?.splitFrom)

            // References into another household.
            deps.createSecondHousehold()
            val other = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            val foreign = deps.newEvent(other.me.memberId)
            client.pushOne(other.accessToken, foreign).assertApplied()
            val foreignTag = deps.newTag("Theirs")
            client.pushOne(other.accessToken, foreignTag).assertApplied()
            client
                .pushOne(token, deps.newEvent(me, extra = mapOf("splitFrom" to foreign.id)))
                .assertField("splitFrom", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newEvent(me, participantIds = listOf(other.me.personId)))
                .assertField("participantIds", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newEvent(me, extra = mapOf("tagIds" to jsonStrings(foreignTag.id))))
                .assertField("tagIds", FieldErrorCode.INVALID_FORMAT)
        }

    /** CAL-003, CAL-015, ADR-0022: occurrence rows have derived ids, merge field by field and never move. */
    @Test
    fun cal015OccurrenceRows() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val adult = client.memberSession(token, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val event = deps.newEvent(me, extra = mapOf("recurrence" to recurrenceJson("fixed", "week", 1, listOf("MONDAY"))))
            client.pushOne(token, event).assertApplied()

            // Two phones change the same occurrence offline: the same derived row, both fields survive.
            val first = deps.newOccurrenceRow(event.id, "2026-11-09", mapOf("place" to "Gym"))
            assertEquals(Ids.occurrenceRowId(event.id, LocalDate(2026, 11, 9)), first.id)
            client.pushOne(token, first).assertApplied()
            client
                .pushOne(
                    adult.accessToken,
                    deps.newOccurrenceRow(
                        event.id,
                        "2026-11-09",
                        mapOf(
                            "startDate" to "2026-11-10",
                            "endDate" to "2026-11-10",
                            "startTime" to "12:00",
                            "endTime" to "13:00",
                        ),
                    ),
                ).assertApplied()
            val row = client.pull(token).changes.single { it.id == first.id }
            assertEquals(EntityTypes.EVENT_OCCURRENCE, row.type)
            assertEquals(
                EventOccurrenceData(
                    eventId = event.id,
                    occurrence = LocalDate(2026, 11, 9),
                    startDate = LocalDate(2026, 11, 10),
                    endDate = LocalDate(2026, 11, 10),
                    startTime = LocalTime(12, 0),
                    endTime = LocalTime(13, 0),
                    place = "Gym",
                ),
                row.dataAs<EventOccurrenceData>(),
            )

            // Cancel, then reset to an empty override (clients never delete these rows).
            val cancel = deps.newOccurrenceRow(event.id, "2026-11-16", mapOf("cancelled" to true))
            client.pushOne(token, cancel).assertApplied()
            client.pushOne(token, deps.newOccurrenceRow(event.id, "2026-11-16", mapOf("cancelled" to false))).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.EVENT_OCCURRENCE, cancel.id)).assertRejected(ErrorCode.FORBIDDEN)

            // Wrong derived id, a moved row, bad times.
            client
                .pushOne(token, deps.newOccurrenceRow(event.id, "2026-11-23", id = Ids.newId()))
                .assertField("id", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newOccurrenceRow(event.id, "2026-11-23", id = Ids.occurrenceRowId(event.id, LocalDate(2026, 11, 30))))
                .assertField("id", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.upsert(EntityTypes.EVENT_OCCURRENCE, first.id, fields("occurrence" to "2026-11-16")))
                .assertField("occurrence", FieldErrorCode.INVALID_FORMAT)
            val another = deps.newEvent(me, extra = mapOf("recurrence" to recurrenceJson("fixed", "day")))
            client.pushOne(token, another).assertApplied()
            client
                .pushOne(token, deps.upsert(EntityTypes.EVENT_OCCURRENCE, first.id, fields("eventId" to another.id)))
                .assertField("eventId", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newOccurrenceRow(event.id, "2026-11-23", mapOf("startDate" to "2026-11-24")))
                .assertField("endDate", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newOccurrenceRow(event.id, "2026-11-23", mapOf("participantIds" to jsonStrings(Ids.newId()))))
                .assertField("participantIds", FieldErrorCode.INVALID_FORMAT)

            // Children of a missing or deleted event.
            client.pushOne(token, deps.newOccurrenceRow(Ids.newId(), "2026-11-23")).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            client.pushOne(token, deps.newEventReminder(Ids.newId(), me, 10)).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            client.pushOne(token, deps.delete(EntityTypes.EVENT, another.id)).assertApplied()
            client.pushOne(token, deps.newOccurrenceRow(another.id, "2026-11-23")).assertRejected(ErrorCode.ENTITY_DELETED)
            client.pushOne(token, deps.newEventReminder(another.id, me, 10)).assertRejected(ErrorCode.ENTITY_DELETED)
        }

    /** CAL-006, ADR-0023: reminder rows have derived ids, keep their event and member, and are never deleted by clients. */
    @Test
    fun cal006ReminderRows() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val adult = client.memberSession(token, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val event = deps.newEvent(me, extra = mapOf("reminderMinutesBefore" to 60))
            val other = deps.newEvent(me)
            client.pushOne(token, event).assertApplied()
            client.pushOne(token, other).assertApplied()

            val mine = deps.newEventReminder(event.id, me, 15)
            client.pushOne(token, mine).assertApplied()
            client.pushOne(token, deps.newEventReminder(event.id, me, null)).assertApplied()
            assertEquals(
                EventReminderData(event.id, me, null),
                client
                    .pull(token)
                    .changes
                    .single { it.id == mine.id }
                    .dataAs<EventReminderData>(),
            )
            client.pushOne(token, deps.newEventReminder(event.id, me, 50_000)).assertField("minutesBefore", FieldErrorCode.OUT_OF_RANGE)
            client.pushOne(token, deps.newEventReminder(event.id, me, 5, id = Ids.newId())).assertField("id", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newEventReminder(event.id, me, 5, id = Ids.reminderRowId(other.id, me)))
                .assertField("id", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.upsert(EntityTypes.EVENT_REMINDER, mine.id, fields("eventId" to other.id)))
                .assertField("eventId", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.upsert(EntityTypes.EVENT_REMINDER, mine.id, fields("memberId" to adult.me.memberId)))
                .assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(token, deps.delete(EntityTypes.EVENT_REMINDER, mine.id)).assertRejected(ErrorCode.FORBIDDEN)
        }

    /** CAL-015, ADR-0022: deleting an event tombstones its occurrence and reminder rows, each with its own seq. */
    @Test
    fun cal015DeleteCascadesToChildren() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val adult = client.memberSession(token, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val event = deps.newEvent(me, extra = mapOf("recurrence" to recurrenceJson("fixed", "day")))
            client.pushOne(token, event).assertApplied()
            val children =
                listOf(
                    deps.newOccurrenceRow(event.id, "2026-11-03", mapOf("cancelled" to true)),
                    deps.newOccurrenceRow(event.id, "2026-11-04", mapOf("title" to "Late")),
                    deps.newEventReminder(event.id, me, 5),
                )
            children.forEach { client.pushOne(token, it).assertApplied() }
            val adultsReminder = deps.newEventReminder(event.id, adult.me.memberId, 10)
            client.pushOne(adult.accessToken, adultsReminder).assertApplied()
            val (before, _) = client.pullAll(token)

            client.pushOne(token, deps.delete(EntityTypes.EVENT, event.id)).assertApplied()

            val tombstones = client.pull(token, before.cursor, accessVersion = before.accessVersion, epoch = before.epoch).changes
            assertEquals((children.map { it.id } + adultsReminder.id + event.id).toSet(), tombstones.map { it.id }.toSet())
            assertTrue(tombstones.all { it.deleted && it.data == null })
            assertEquals(5, tombstones.map { it.seq }.toSet().size, "distinct seqs")
            assertNull(client.pushOne(token, deps.delete(EntityTypes.EVENT, event.id)).assertApplied())
        }

    /** ADR-0010: another household can neither read nor write this household's calendar rows. */
    @Test
    fun householdsAreIsolated() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val event = deps.newEvent(me, extra = mapOf("recurrence" to recurrenceJson("fixed", "day")))
            val occurrence = deps.newOccurrenceRow(event.id, "2026-11-03", mapOf("cancelled" to true))
            val reminder = deps.newEventReminder(event.id, me, 5)
            listOf(event, occurrence, reminder).forEach { client.pushOne(token, it).assertApplied() }

            deps.createSecondHousehold()
            val other = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            val ids = setOf(event.id, occurrence.id, reminder.id)
            assertTrue(client.pullAll(other.accessToken).second.none { it.id in ids })

            client.pushOne(other.accessToken, deps.newEvent(other.me.memberId, id = event.id)).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            client.pushOne(other.accessToken, deps.newOccurrenceRow(event.id, "2026-11-05")).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            client
                .pushOne(other.accessToken, deps.newEventReminder(event.id, other.me.memberId, 5))
                .assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            assertNull(client.pushOne(other.accessToken, deps.delete(EntityTypes.EVENT, event.id)).assertApplied())
            val live = client.pullAll(token).second.filter { it.id in ids }
            assertEquals(3, live.size)
            assertTrue(live.none { it.deleted })
        }

    /** Housekeeping purges reminder and occurrence tombstones before their event and raises the purge horizon. */
    @Test
    fun tombstonesArePurged() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val event = deps.newEvent(me, extra = mapOf("recurrence" to recurrenceJson("fixed", "day")))
            client.pushOne(token, event).assertApplied()
            client.pushOne(token, deps.newOccurrenceRow(event.id, "2026-11-03", mapOf("cancelled" to true))).assertApplied()
            client.pushOne(token, deps.newEventReminder(event.id, me, 5)).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.EVENT, event.id)).assertApplied()
            val lastSeq = deps.lastSeq(admin.householdUuid)

            assertEquals(0, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            deps.testClock.advance(91.days)
            client.login(ADMIN_LOGIN, ADMIN_PASSWORD)
            assertEquals(3, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            assertEquals(
                lastSeq,
                deps.db.dbQuery {
                    HouseholdSyncStateTable
                        .selectAll()
                        .single()[HouseholdSyncStateTable.purgeHorizonSeq]
                },
            )
            assertNull(deps.db.dbQuery { CalendarRepository.load(UUID.fromString(event.id)) })
            assertEquals(0, deps.db.dbQuery { EventOccurrenceTable.selectAll().count() + EventReminderTable.selectAll().count() })
        }

    /** CAL-002, TAG-002: a participant or tag deleted since stays on the event through unrelated edits. */
    @Test
    fun cal002DeletedPersonStaysOnUnrelatedEdit() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val grandma = deps.createRelative(admin.householdUuid, "Grandma").toString()
            val tag = deps.newTag("Family")
            client.pushOne(token, tag).assertApplied()
            val event = deps.newEvent(admin.me.memberId, participantIds = listOf(grandma), extra = mapOf("tagIds" to jsonStrings(tag.id)))
            client.pushOne(token, event).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.PERSON, grandma)).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.TAG, tag.id)).assertApplied()

            client.pushOne(token, deps.upsert(EntityTypes.EVENT, event.id, fields("title" to "Visit"))).assertApplied()
            val stored = eventsOf(client.pull(token).changes)[event.id]
            assertEquals(setOf(grandma), stored?.participantIds)
            assertEquals(setOf(tag.id), stored?.tagIds)
            client
                .pushOne(token, deps.newEvent(admin.me.memberId, participantIds = listOf(grandma)))
                .assertField("participantIds", FieldErrorCode.INVALID_FORMAT)
        }
}

internal fun eventsOf(changes: List<Change>): Map<String, EventData> =
    changes.filter { it.type == EntityTypes.EVENT && !it.deleted }.associate { it.id to it.dataAs<EventData>() }
