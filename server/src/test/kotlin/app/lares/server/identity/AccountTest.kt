package app.lares.server.identity

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.auth.ChangePasswordRequest
import app.lares.contract.api.auth.DeleteAccountRequest
import app.lares.server.household.GroupMembershipTable
import app.lares.server.household.MemberTable
import app.lares.server.household.PersonTable
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.testing.ADMIN_LOGIN
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.assertError
import app.lares.server.testing.bearer
import app.lares.server.testing.createUserDirectly
import app.lares.server.testing.json
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FND-010 (own password), FND-013 (delete account). */
class AccountTest {
    @Test
    fun changePasswordChecksCurrentPassword() =
        testApp {
            val client = jsonClient()
            val auth = client.setup()
            val wrong =
                client.post("/api/v1/me/password") {
                    bearer(auth.accessToken)
                    json(ChangePasswordRequest(currentPassword = "wrong password", newPassword = "new password 1"))
                }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            wrong.assertError(ErrorCode.INVALID_CREDENTIALS)
            val missing =
                client.post("/api/v1/me/password") {
                    bearer(auth.accessToken)
                    json(ChangePasswordRequest(currentPassword = null, newPassword = "new password 1"))
                }
            assertEquals(mapOf("currentPassword" to FieldErrorCode.REQUIRED), missing.assertError(ErrorCode.VALIDATION_FAILED).error.fields)
            val tooShort =
                client.post("/api/v1/me/password") {
                    bearer(auth.accessToken)
                    json(ChangePasswordRequest(currentPassword = ADMIN_PASSWORD, newPassword = "short"))
                }
            assertEquals(mapOf("newPassword" to FieldErrorCode.TOO_SHORT), tooShort.assertError(ErrorCode.VALIDATION_FAILED).error.fields)
        }

    @Test
    fun changedPasswordWorksAndOtherSessionsStay() =
        testApp {
            val client = jsonClient()
            val auth = client.setup()
            val response =
                client.post("/api/v1/me/password") {
                    bearer(auth.accessToken)
                    json(ChangePasswordRequest(currentPassword = ADMIN_PASSWORD, newPassword = "new password 1"))
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
            client.login(ADMIN_LOGIN, ADMIN_PASSWORD).assertError(ErrorCode.INVALID_CREDENTIALS)
            assertEquals(HttpStatusCode.OK, client.login(ADMIN_LOGIN, "new password 1").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearer(auth.accessToken) }.status)
        }

    @Test
    fun onlyAdministratorCannotDeleteAccount() =
        testApp {
            val client = jsonClient()
            val auth = client.setup()
            val response =
                client.delete("/api/v1/me") {
                    bearer(auth.accessToken)
                    json(DeleteAccountRequest(ADMIN_PASSWORD))
                }
            assertEquals(HttpStatusCode.Conflict, response.status)
            response.assertError(ErrorCode.LAST_ADMIN)
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearer(auth.accessToken) }.status)
        }

    @Test
    fun deleteAccountNeedsCorrectPassword() =
        testApp {
            val client = jsonClient()
            val auth = client.setup()
            client.delete("/api/v1/me") { bearer(auth.accessToken) }.assertError(ErrorCode.VALIDATION_FAILED)
            client
                .delete("/api/v1/me") {
                    bearer(auth.accessToken)
                    json(DeleteAccountRequest("wrong password"))
                }.assertError(ErrorCode.INVALID_CREDENTIALS)
        }

    @Test
    fun deleteAccountFreesLoginRevokesSessionsAndTombstonesMember() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val householdId = UUID.fromString(admin.me.householdId)
            deps.createUserDirectly(householdId, login = "bob", password = "bob password", admin = true)
            val bob = client.login("bob", "bob password").body<AuthResult>()
            val bobMemberId = UUID.fromString(bob.me.memberId)
            assertTrue(bob.me.isAdmin)

            val (seqBefore, accessVersionBefore) =
                deps.db.dbQuery {
                    val member = MemberTable.selectAll().where { MemberTable.id eq bobMemberId }.single()
                    member[MemberTable.seq] to member[MemberTable.accessVersion]
                }

            val response =
                client.delete("/api/v1/me") {
                    bearer(bob.accessToken)
                    json(DeleteAccountRequest("bob password"))
                }
            assertEquals(HttpStatusCode.NoContent, response.status)

            client.get("/api/v1/me") { bearer(bob.accessToken) }.assertError(ErrorCode.SESSION_REVOKED)
            client.login("bob", "bob password").assertError(ErrorCode.INVALID_CREDENTIALS)
            deps.db.dbQuery {
                val member = MemberTable.selectAll().where { MemberTable.id eq bobMemberId }.single()
                assertTrue(member[MemberTable.deleted])
                assertTrue(member[MemberTable.seq] > seqBefore)
                assertEquals(accessVersionBefore + 1, member[MemberTable.accessVersion])
                // The person stays: history keeps the name.
                val person = PersonTable.selectAll().where { PersonTable.id eq member[MemberTable.personId] }.single()
                assertEquals(false, person[PersonTable.deleted])
                assertEquals(0, GroupMembershipTable.selectAll().where { GroupMembershipTable.memberId eq bobMemberId }.count())
                val lastSeq = HouseholdSyncStateTable.selectAll().single()[HouseholdSyncStateTable.lastSeq]
                assertTrue(member[MemberTable.seq] < lastSeq, "the group change after the tombstone has the newest seq")
            }
            // Now Anna is the only administrator again.
            client
                .delete("/api/v1/me") {
                    bearer(admin.accessToken)
                    json(DeleteAccountRequest(ADMIN_PASSWORD))
                }.assertError(ErrorCode.LAST_ADMIN)
            // The freed login can be reused (M0-04 creates users through the API).
            deps.createUserDirectly(householdId, login = "bob", password = "new bob password", admin = false)
            assertEquals(HttpStatusCode.OK, client.login("bob", "new bob password").status)
        }

    @Test
    fun nonAdminCanDeleteAccount() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            deps.createUserDirectly(UUID.fromString(admin.me.householdId), login = "kid", password = "kid password", admin = false)
            val kid = client.login("kid", "kid password").body<AuthResult>()
            assertEquals(false, kid.me.isAdmin)
            val response =
                client.delete("/api/v1/me") {
                    bearer(kid.accessToken)
                    json(DeleteAccountRequest("kid password"))
                }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
}
