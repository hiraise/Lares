package app.familyhub.server.sync

import app.familyhub.contract.Ids
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.entities.PaletteColor
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.entities.TagData
import app.familyhub.contract.sync.EntityTypes
import app.familyhub.server.testing.ADMIN_PASSWORD
import app.familyhub.server.testing.KIDS
import app.familyhub.server.testing.assertApplied
import app.familyhub.server.testing.assertRejected
import app.familyhub.server.testing.createSecondHousehold
import app.familyhub.server.testing.dataAs
import app.familyhub.server.testing.delete
import app.familyhub.server.testing.fields
import app.familyhub.server.testing.groupId
import app.familyhub.server.testing.householdUuid
import app.familyhub.server.testing.jsonClient
import app.familyhub.server.testing.login
import app.familyhub.server.testing.memberSession
import app.familyhub.server.testing.newTag
import app.familyhub.server.testing.pull
import app.familyhub.server.testing.pullAll
import app.familyhub.server.testing.pushOne
import app.familyhub.server.testing.setup
import app.familyhub.server.testing.testApp
import app.familyhub.server.testing.upsert
import io.ktor.client.call.body
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Write rules (ADR-0014) and household isolation (ADR-0010, invariant 7) for sync mutations. */
class SyncPermissionsTest {
    @Test
    fun writeRulesAreEnforced() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val kid = client.memberSession(admin.accessToken, "kid", setOf(deps.groupId(admin.householdUuid, KIDS)))

            // Kids have neither tags.manage nor persons.manage.
            client.pushOne(kid.accessToken, deps.newTag("Toys")).assertRejected(ErrorCode.FORBIDDEN)
            client
                .pushOne(
                    kid.accessToken,
                    deps.upsert(EntityTypes.PERSON, Ids.newId(), fields("name" to "Friend")),
                ).assertRejected(ErrorCode.FORBIDDEN)
            // … but may edit their own profile.
            client
                .pushOne(
                    kid.accessToken,
                    deps.upsert(EntityTypes.PERSON, kid.me.personId, fields("name" to "Kiddo", "color" to "green")),
                ).assertApplied()
            // … and not someone else's.
            client
                .pushOne(
                    kid.accessToken,
                    deps.upsert(EntityTypes.PERSON, admin.me.personId, fields("name" to "Mom")),
                ).assertRejected(ErrorCode.FORBIDDEN)
            // Nor delete their own profile.
            client.pushOne(kid.accessToken, deps.delete(EntityTypes.PERSON, kid.me.personId)).assertRejected(ErrorCode.FORBIDDEN)

            // Administrators manage relatives; a member's person cannot be deleted.
            val relative = deps.upsert(EntityTypes.PERSON, Ids.newId(), fields("name" to " Grandma ", "birthMonth" to 5, "birthDay" to 9))
            client.pushOne(admin.accessToken, relative).assertApplied()
            client.pushOne(admin.accessToken, deps.delete(EntityTypes.PERSON, kid.me.personId)).assertRejected(ErrorCode.PERSON_IS_MEMBER)
            client
                .pushOne(admin.accessToken, deps.upsert(EntityTypes.PERSON, Ids.newId(), fields("name" to "X", "birthMonth" to 13)))
                .assertRejected(ErrorCode.VALIDATION_FAILED)

            val persons =
                client.pull(admin.accessToken).changes.filter { it.type == EntityTypes.PERSON }.associate {
                    it.id to
                        it.dataAs<PersonData>()
                }
            assertEquals(PersonData("Kiddo", color = PaletteColor.GREEN), persons[kid.me.personId])
            // Relatives created by clients keep the color the client chose (none here).
            assertEquals(PersonData("Grandma", birthMonth = 5, birthDay = 9), persons[relative.id])

            client.pushOne(admin.accessToken, deps.delete(EntityTypes.PERSON, relative.id)).assertApplied()
        }

    @Test
    fun householdsAreIsolated() =
        testApp { deps ->
            val client = jsonClient()
            val adminA = client.setup()
            deps.createSecondHousehold(login = "admin-b")
            val adminB = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()

            val tagA = deps.newTag("Car")
            client.pushOne(adminA.accessToken, tagA).assertApplied()

            // B uses A's ids: upsert is "not found", delete is a silent no-op; A's data is unchanged.
            client
                .pushOne(
                    adminB.accessToken,
                    deps.upsert(EntityTypes.TAG, tagA.id, fields("name" to "Stolen")),
                ).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            assertNull(client.pushOne(adminB.accessToken, deps.delete(EntityTypes.TAG, tagA.id)).assertApplied())
            client
                .pushOne(adminB.accessToken, deps.upsert(EntityTypes.PERSON, adminA.me.personId, fields("name" to "X")))
                .assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            assertNull(client.pushOne(adminB.accessToken, deps.delete(EntityTypes.PERSON, adminA.me.personId)).assertApplied())
            // The same mutation id in another household is a different mutation.
            client.pushOne(adminB.accessToken, deps.newTag("Car").copy(mutationId = tagA.mutationId)).assertApplied()

            val (_, changesA) = client.pullAll(adminA.accessToken)
            val (_, changesB) = client.pullAll(adminB.accessToken)
            assertEquals(TagData("Car", PaletteColor.BLUE), changesA.single { it.id == tagA.id }.dataAs<TagData>())
            assertEquals(adminA.me.personId, changesA.single { it.type == EntityTypes.PERSON }.id)
            assertTrue(changesA.map { it.id }.intersect(changesB.map { it.id }.toSet()).isEmpty(), "no shared rows")
            assertEquals(adminB.me.householdId, changesB.single { it.type == EntityTypes.HOUSEHOLD }.id)
        }
}
