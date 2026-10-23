package app.familyhub.integration

import app.familyhub.contract.api.admin.CreateInviteRequest
import app.familyhub.contract.entities.PersonData
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Family management through the client API layer and sync, as the Family screens use them (M0-08). */
class FamilyManagementTest {
    @Test
    fun `an invite created by the admin is accepted on a new phone and both see each other`() =
        familyTest {
            val grandmaId = anna.persons.create(PersonData("Grandma", birthMonth = 3, birthDay = 8))
            anna.sync()
            val invite = anna.api.createInvite(CreateInviteRequest(setOf(adultsId), expiresInHours = 24, personId = grandmaId))
            assertTrue(anna.api.invites().any { it.id == invite.id })

            TestClient(server.url, "Grandma's phone").use { grandma ->
                grandma.connect()
                val me = grandma.session.acceptInvite(invite.code, "Grandma", "grandma", "grandma-password")
                assertEquals(grandmaId, me.personId, "the invite links the existing relative (FND-032)")
                grandma.sync()
                anna.sync()

                val seenByAnna = anna.membersRepository.observeAll().await { members -> members.any { it.id == me.memberId } }
                assertEquals("Grandma", seenByAnna.single { it.id == me.memberId }.name)
                val seenByGrandma = grandma.membersRepository.observeAll().first()
                assertTrue(seenByGrandma.any { it.name == "Anna" }, "the new member sees the admin")
                val adults =
                    grandma.groupsRepository
                        .observeAll()
                        .first()
                        .single { it.id == adultsId }
                assertTrue(me.memberId in adults.data.memberIds, "the invite's groups are applied")
                assertTrue(anna.api.invites().none { it.id == invite.id }, "an accepted invite is no longer active")
            }
        }
}
