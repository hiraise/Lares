package app.lares.server.location

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.sync.EntityTypes
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertRejected
import app.lares.server.testing.dataAs
import app.lares.server.testing.delete
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.memberSession
import app.lares.server.testing.newPlace
import app.lares.server.testing.newPlaceAlert
import app.lares.server.testing.pull
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** LOC-021: a subscriber's own arrive / leave alerts about a member, a row only the subscriber writes and pulls. */
class PlaceAlertSyncTest {
    @Test
    fun loc021AlertRowsAreTheSubscribersOwn() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val householdId = admin.householdUuid
            val adult = client.memberSession(admin.accessToken, "adult", setOf(deps.groupId(householdId, ADULTS)))
            val kid = client.memberSession(admin.accessToken, "kid", setOf(deps.groupId(householdId, KIDS)))
            val school = deps.newPlace(adult.me.memberId)
            client.pushOne(adult.accessToken, school).assertApplied()
            val kidId = kid.me.memberId
            val adultId = adult.me.memberId

            // The id must be the derived one.
            val wrongId = client.pushOne(adult.accessToken, deps.newPlaceAlert(kidId, adultId, setOf(school.id), id = Ids.newId()))
            wrongId.assertRejected(ErrorCode.VALIDATION_FAILED)
            assertEquals(FieldErrorCode.INVALID_FORMAT, wrongId.error?.fields?.get("id"))
            // Only the subscriber writes; nobody subscribes to themselves.
            client.pushOne(adult.accessToken, deps.newPlaceAlert(adultId, kidId, setOf(school.id))).assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(adult.accessToken, deps.newPlaceAlert(adultId, adultId, setOf(school.id))).assertRejected(ErrorCode.FORBIDDEN)
            // The member and the added places must exist.
            client
                .pushOne(adult.accessToken, deps.newPlaceAlert(UUID.randomUUID().toString(), adultId, setOf(school.id)))
                .assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            client
                .pushOne(adult.accessToken, deps.newPlaceAlert(kidId, adultId, setOf(Ids.newId())))
                .assertRejected(ErrorCode.ENTITY_NOT_FOUND)

            val alert = deps.newPlaceAlert(kidId, adultId, setOf(school.id))
            val alertSeq = client.pushOne(adult.accessToken, alert).assertApplied()!!
            // A kid with location.view sets their own alerts too.
            client.pushOne(kid.accessToken, deps.newPlaceAlert(adultId, kidId, setOf(school.id))).assertApplied()

            val adultRows = client.pullAll(adult.accessToken).second.filter { it.type == EntityTypes.PLACE_ALERT }
            assertEquals(PlaceAlertData(kidId, adultId, setOf(school.id)), adultRows.single().dataAs<PlaceAlertData>())
            // The admin sees nobody's alerts, but the cursor moves past them.
            val adminPull = client.pull(admin.accessToken)
            assertTrue(adminPull.changes.none { it.type == EntityTypes.PLACE_ALERT })
            assertTrue(adminPull.cursor >= alertSeq)

            // Clients never delete alert rows; "no alerts" is an empty set.
            client.pushOne(adult.accessToken, deps.delete(EntityTypes.PLACE_ALERT, alert.id)).assertRejected(ErrorCode.FORBIDDEN)

            // Deleting the place leaves the alert row as it is; readers ignore the stale id.
            client.pushOne(adult.accessToken, deps.delete(EntityTypes.PLACE, school.id)).assertApplied()
            deps.db.dbQuery {
                val row = PlaceAlertTable.selectAll().where { PlaceAlertTable.id eq UUID.fromString(alert.id) }.single()
                assertEquals(alertSeq, row[PlaceAlertTable.seq])
                assertFalse(row[PlaceAlertTable.deleted])
                assertEquals(setOf(school.id), PlaceRepository.toAlertData(row).placeIds)
            }
            client.pushOne(adult.accessToken, deps.newPlaceAlert(kidId, adultId, emptySet())).assertApplied()
        }
}
