package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.GroupCreated
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.entities.PlaceData
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationResult
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertRejected
import app.lares.server.testing.createSecondHousehold
import app.lares.server.testing.dataAs
import app.lares.server.testing.delete
import app.lares.server.testing.fields
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.memberSession
import app.lares.server.testing.newPlace
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.upsert
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** LOC-020: family places as sync data; written with `location.manage_places`, seen with `location.view`. */
class PlaceSyncTest {
    @Test
    fun loc020AdultsManagePlacesKidsOnlySeeThem() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val householdId = admin.householdUuid
            val adult = client.memberSession(admin.accessToken, "adult", setOf(deps.groupId(householdId, ADULTS)))
            val kid = client.memberSession(admin.accessToken, "kid", setOf(deps.groupId(householdId, KIDS)))
            val shoppersGroup =
                client
                    .send(
                        HttpMethod.Post,
                        "/api/v1/groups",
                        admin.accessToken,
                        CreateGroupRequest("Shoppers", setOf(Permission.SHOPPING_VIEW)),
                    ).body<GroupCreated>()
                    .groupId
            val shopper = client.memberSession(admin.accessToken, "shopper", setOf(UUID.fromString(shoppersGroup)))

            val school = deps.newPlace(adult.me.memberId, extra = mapOf("icon" to "school", "address" to "  Lenina 1 "))
            client.pushOne(adult.accessToken, school).assertApplied()
            client.pushOne(kid.accessToken, deps.newPlace(kid.me.memberId, name = "Park")).assertRejected(ErrorCode.FORBIDDEN)
            client
                .pushOne(kid.accessToken, deps.upsert(EntityTypes.PLACE, school.id, fields("name" to "Mine")))
                .assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(kid.accessToken, deps.delete(EntityTypes.PLACE, school.id)).assertRejected(ErrorCode.FORBIDDEN)
            // Nobody creates a place on someone else's behalf.
            client.pushOne(adult.accessToken, deps.newPlace(admin.me.memberId, name = "Gym")).assertRejected(ErrorCode.FORBIDDEN)

            // Any manager edits any place; the admin edits the adult's place.
            client
                .pushOne(admin.accessToken, deps.upsert(EntityTypes.PLACE, school.id, fields("name" to " School 57 ", "radiusM" to 200)))
                .assertApplied()

            val kidView = client.pullAll(kid.accessToken).second.filter { it.type == EntityTypes.PLACE }
            val place = kidView.single().dataAs<PlaceData>()
            assertEquals("School 57", place.name)
            assertEquals(200, place.radiusM)
            assertEquals(PlaceIcon.SCHOOL, place.icon)
            assertEquals("Lenina 1", place.address)
            assertEquals(adult.me.memberId, place.createdBy)
            assertTrue(client.pullAll(shopper.accessToken).second.none { it.type == EntityTypes.PLACE }, "no location.view, no places")

            val (kidCursor, _) = client.pullAll(kid.accessToken)
            client.pushOne(adult.accessToken, deps.delete(EntityTypes.PLACE, school.id)).assertApplied()
            val tombstone = client.pullAll(kid.accessToken, kidCursor).second.single { it.type == EntityTypes.PLACE }
            assertTrue(tombstone.deleted)
            client
                .pushOne(adult.accessToken, deps.upsert(EntityTypes.PLACE, school.id, fields("name" to "Back")))
                .assertRejected(ErrorCode.ENTITY_DELETED)
        }

    @Test
    fun loc020FieldsAreValidatedAndCreatorIsFixed() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val adult = client.memberSession(admin.accessToken, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val token = adult.accessToken
            val me = adult.me.memberId

            fun expectField(
                result: MutationResult,
                field: String,
                code: FieldErrorCode,
            ) {
                result.assertRejected(ErrorCode.VALIDATION_FAILED)
                assertEquals(code, result.error?.fields?.get(field), "$field: $result")
            }
            expectField(client.pushOne(token, deps.newPlace(me, name = "  ")), "name", FieldErrorCode.REQUIRED)
            expectField(client.pushOne(token, deps.newPlace(me, name = "x".repeat(61))), "name", FieldErrorCode.TOO_LONG)
            expectField(client.pushOne(token, deps.newPlace(me, latitude = 91.0)), "latitude", FieldErrorCode.OUT_OF_RANGE)
            expectField(client.pushOne(token, deps.newPlace(me, longitude = -181.0)), "longitude", FieldErrorCode.OUT_OF_RANGE)
            expectField(client.pushOne(token, deps.newPlace(me, radiusM = 49)), "radiusM", FieldErrorCode.OUT_OF_RANGE)
            expectField(client.pushOne(token, deps.newPlace(me, radiusM = 1001)), "radiusM", FieldErrorCode.OUT_OF_RANGE)
            expectField(
                client.pushOne(token, deps.newPlace(me, extra = mapOf("address" to "a".repeat(201)))),
                "address",
                FieldErrorCode.TOO_LONG,
            )
            expectField(client.pushOne(token, deps.newPlace(me, extra = mapOf("icon" to "castle"))), "icon", FieldErrorCode.INVALID_FORMAT)

            val home = deps.newPlace(me, name = "Home")
            client.pushOne(token, home).assertApplied()
            expectField(
                client.pushOne(token, deps.upsert(EntityTypes.PLACE, home.id, fields("createdBy" to admin.me.memberId))),
                "createdBy",
                FieldErrorCode.INVALID_FORMAT,
            )
        }

    @Test
    fun loc020PlaceOfAnotherHouseholdIsNotFound() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val place = deps.newPlace(admin.me.memberId)
            client.pushOne(admin.accessToken, place).assertApplied()

            deps.createSecondHousehold(login = "admin-b")
            val adminB = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            client
                .pushOne(adminB.accessToken, deps.upsert(EntityTypes.PLACE, place.id, fields("name" to "Ours")))
                .assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            assertTrue(client.pullAll(adminB.accessToken).second.none { it.type == EntityTypes.PLACE })
        }
}
