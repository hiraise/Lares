package app.lares.server.calendar

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.GroupCreated
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.Change
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
import app.lares.server.testing.newEvent
import app.lares.server.testing.newEventReminder
import app.lares.server.testing.newOccurrenceRow
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.recurrenceJson
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

/** Calendar spec § Permissions and privacy (CAL-001, CAL-006, CAL-015, CAL-017): enforced by `EventRules`, ADR-0023. */
class CalendarPermissionsTest {
    private class Family(
        val admin: AuthResult,
        val adult: AuthResult,
        val kid: AuthResult,
        val viewer: AuthResult,
        val outsider: AuthResult,
    )

    private suspend fun HttpClient.group(
        admin: AuthResult,
        name: String,
        permissions: Set<Permission>,
    ): UUID {
        val response = send(HttpMethod.Post, "/api/v1/groups", admin.accessToken, CreateGroupRequest(name, permissions))
        assertEquals(HttpStatusCode.Created, response.status)
        return UUID.fromString(response.body<GroupCreated>().groupId)
    }

    private suspend fun HttpClient.family(deps: AppDeps): Family {
        val admin = setup()
        val token = admin.accessToken
        return Family(
            admin,
            memberSession(token, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS))),
            memberSession(token, "kid", setOf(deps.groupId(admin.householdUuid, KIDS))),
            memberSession(token, "viewer", setOf(group(admin, "Viewers", setOf(Permission.CALENDAR_VIEW)))),
            memberSession(token, "shopper", setOf(group(admin, "Shoppers", setOf(Permission.SHOPPING_VIEW)))),
        )
    }

    /** CAL-001: Kids create their own events, never on someone else's behalf; view-only members create nothing. */
    @Test
    fun cal001CreatingNeedsCalendarCreate() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            client.pushOne(f.kid.accessToken, deps.newEvent(f.kid.me.memberId)).assertApplied()
            client.pushOne(f.kid.accessToken, deps.newEvent(f.adult.me.memberId)).assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(f.viewer.accessToken, deps.newEvent(f.viewer.me.memberId)).assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(f.adult.accessToken, deps.newEvent(f.adult.me.memberId)).assertApplied()
            client.pushOne(f.admin.accessToken, deps.newEvent(f.admin.me.memberId)).assertApplied()
        }

    /** CAL-001, CAL-015: only the creator or `calendar.edit_any` edits, deletes or changes occurrences of an event. */
    @Test
    fun cal015EditingNeedsCreatorOrEditAny() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val daily = mapOf("recurrence" to recurrenceJson("fixed", "day"))
            val adultsEvent = deps.newEvent(f.adult.me.memberId, extra = daily)
            client.pushOne(f.adult.accessToken, adultsEvent).assertApplied()
            for (other in listOf(f.kid, f.viewer)) {
                client
                    .pushOne(other.accessToken, deps.upsert(EntityTypes.EVENT, adultsEvent.id, fields("title" to "Mine now")))
                    .assertRejected(ErrorCode.FORBIDDEN)
                client.pushOne(other.accessToken, deps.delete(EntityTypes.EVENT, adultsEvent.id)).assertRejected(ErrorCode.FORBIDDEN)
                client
                    .pushOne(other.accessToken, deps.newOccurrenceRow(adultsEvent.id, "2026-11-03", mapOf("cancelled" to true)))
                    .assertRejected(ErrorCode.FORBIDDEN)
            }

            // The creator edits their own event; Adults and the admin have calendar.edit_any.
            val kidsEvent = deps.newEvent(f.kid.me.memberId, extra = daily)
            client.pushOne(f.kid.accessToken, kidsEvent).assertApplied()
            client.pushOne(f.kid.accessToken, deps.upsert(EntityTypes.EVENT, kidsEvent.id, fields("place" to "Park"))).assertApplied()
            client
                .pushOne(f.kid.accessToken, deps.newOccurrenceRow(kidsEvent.id, "2026-11-03", mapOf("title" to "Zoo")))
                .assertApplied()
            client
                .pushOne(f.adult.accessToken, deps.upsert(EntityTypes.EVENT, kidsEvent.id, fields("title" to "Picnic")))
                .assertApplied()
            client
                .pushOne(f.adult.accessToken, deps.newOccurrenceRow(kidsEvent.id, "2026-11-04", mapOf("cancelled" to true)))
                .assertApplied()
            client
                .pushOne(f.admin.accessToken, deps.newOccurrenceRow(adultsEvent.id, "2026-11-04", mapOf("place" to "Home")))
                .assertApplied()
            client.pushOne(f.admin.accessToken, deps.delete(EntityTypes.EVENT, kidsEvent.id)).assertApplied()
        }

    /** CAL-006, ADR-0023: everybody who sees the calendar sets their own reminder; nobody sets someone else's. */
    @Test
    fun cal006RemindersAreForOneself() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val event = deps.newEvent(f.adult.me.memberId)
            client.pushOne(f.adult.accessToken, event).assertApplied()

            for (member in listOf(f.admin, f.adult, f.kid, f.viewer)) {
                client.pushOne(member.accessToken, deps.newEventReminder(event.id, member.me.memberId, 15)).assertApplied()
            }
            client
                .pushOne(f.admin.accessToken, deps.newEventReminder(event.id, f.kid.me.memberId, 5))
                .assertRejected(ErrorCode.FORBIDDEN)
            client
                .pushOne(f.kid.accessToken, deps.newEventReminder(event.id, f.adult.me.memberId, 5))
                .assertRejected(ErrorCode.FORBIDDEN)
            client
                .pushOne(f.outsider.accessToken, deps.newEventReminder(event.id, f.outsider.me.memberId, 5))
                .assertRejected(ErrorCode.FORBIDDEN)
        }

    /** ADR-0023: a member pulls only their own reminder rows (an administrator too); tombstones reach everybody. */
    @Test
    fun cal006ReminderRowsArePrivate() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val event = deps.newEvent(f.adult.me.memberId)
            client.pushOne(f.adult.accessToken, event).assertApplied()
            val adults = deps.newEventReminder(event.id, f.adult.me.memberId, 10)
            val kids = deps.newEventReminder(event.id, f.kid.me.memberId, 20)
            client.pushOne(f.adult.accessToken, adults).assertApplied()
            client.pushOne(f.kid.accessToken, kids).assertApplied()

            fun reminderIds(changes: List<Change>) = changes.filter { it.type == EntityTypes.EVENT_REMINDER }.map { it.id }.toSet()

            assertEquals(setOf(adults.id), reminderIds(client.pullAll(f.adult.accessToken).second))
            assertEquals(setOf(kids.id), reminderIds(client.pullAll(f.kid.accessToken).second))
            assertEquals(emptySet(), reminderIds(client.pullAll(f.admin.accessToken).second))
            val (last, _) = client.pullAll(f.admin.accessToken)
            assertEquals(deps.lastSeq(f.admin.householdUuid), last.cursor)

            // The event's cascade tombstones both rows; a tombstone carries no data and is pulled by everybody.
            client.pushOne(f.adult.accessToken, deps.delete(EntityTypes.EVENT, event.id)).assertApplied()
            val tombstones = client.pullAll(f.admin.accessToken, last).second.filter { it.type == EntityTypes.EVENT_REMINDER }
            assertEquals(setOf(adults.id, kids.id), tombstones.map { it.id }.toSet())
            assertTrue(tombstones.all { it.deleted && it.data == null })
        }

    /** CAL-017: members without `calendar.view` get no calendar rows (the cursor still advances) and cannot write them. */
    @Test
    fun withoutCalendarViewNothingIsVisible() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val event = deps.newEvent(f.adult.me.memberId, extra = mapOf("recurrence" to recurrenceJson("fixed", "day")))
            client.pushOne(f.adult.accessToken, event).assertApplied()
            client
                .pushOne(f.adult.accessToken, deps.newOccurrenceRow(event.id, "2026-11-03", mapOf("cancelled" to true)))
                .assertApplied()
            client.pushOne(f.adult.accessToken, deps.newEventReminder(event.id, f.adult.me.memberId, 5)).assertApplied()

            val (last, changes) = client.pullAll(f.outsider.accessToken)
            val calendarTypes = setOf(EntityTypes.EVENT, EntityTypes.EVENT_OCCURRENCE, EntityTypes.EVENT_REMINDER)
            assertTrue(changes.none { it.type in calendarTypes })
            assertEquals(deps.lastSeq(f.admin.householdUuid), last.cursor)
            client.pushOne(f.outsider.accessToken, deps.newEvent(f.outsider.me.memberId)).assertRejected(ErrorCode.FORBIDDEN)
            client
                .pushOne(f.outsider.accessToken, deps.newOccurrenceRow(event.id, "2026-11-04", mapOf("cancelled" to true)))
                .assertRejected(ErrorCode.FORBIDDEN)
        }
}
