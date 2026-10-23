package app.lares.server.identity

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.admin.CreateMemberRequest
import app.lares.contract.api.admin.ResetPasswordRequest
import app.lares.contract.api.admin.SetGroupsRequest
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.auth.Me
import app.lares.contract.permissions.Permission
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.MEMBER_PASSWORD
import app.lares.server.testing.accessVersion
import app.lares.server.testing.assertError
import app.lares.server.testing.createMember
import app.lares.server.testing.createRelative
import app.lares.server.testing.exchangeLoginCode
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.loginCodeFor
import app.lares.server.testing.memberSession
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FND-010 (no password), FND-012, FND-022, FND-025, FND-032. */
class MemberAdminTest {
    @Test
    fun memberWithoutLoginLogsInByCode() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val kids = deps.groupId(admin.householdUuid, KIDS)
            val created = client.createMember(admin.accessToken, CreateMemberRequest("Tim", groupIds = setOf(kids.toString())))

            val code = client.loginCodeFor(admin.accessToken, created.memberId)
            val response = client.exchangeLoginCode(code.code)
            assertEquals(HttpStatusCode.OK, response.status)
            val tim = response.body<AuthResult>()
            val me = client.send(HttpMethod.Get, "/api/v1/me", tim.accessToken).body<Me>()
            assertEquals(created.memberId, me.memberId)
            assertEquals(created.personId, me.personId)
            assertEquals("Tim", me.displayName)
            assertNull(me.login)
            assertFalse(me.hasPassword)
            assertTrue(Permission.TASKS_VIEW in me.permissions)
        }

    @Test
    fun loginAndPasswordRules() =
        testApp {
            val client = jsonClient()
            val admin = client.setup()
            val noLogin =
                client.send(
                    HttpMethod.Post,
                    "/api/v1/members",
                    admin.accessToken,
                    CreateMemberRequest("A", password = MEMBER_PASSWORD),
                )
            assertEquals(mapOf("login" to FieldErrorCode.REQUIRED), noLogin.assertError(ErrorCode.VALIDATION_FAILED).error.fields)
            val taken = client.send(HttpMethod.Post, "/api/v1/members", admin.accessToken, CreateMemberRequest("A", login = "ADMIN"))
            assertEquals(HttpStatusCode.Conflict, taken.status)
            assertEquals(mapOf("login" to FieldErrorCode.TAKEN), taken.assertError(ErrorCode.LOGIN_TAKEN).error.fields)
            val blank = client.send(HttpMethod.Post, "/api/v1/members", admin.accessToken, CreateMemberRequest(" "))
            assertEquals(mapOf("displayName" to FieldErrorCode.REQUIRED), blank.assertError(ErrorCode.VALIDATION_FAILED).error.fields)

            // A login without a password is allowed: password can be set later by an administrator.
            val created = client.createMember(admin.accessToken, CreateMemberRequest("B", login = "b-user"))
            client.login("b-user", MEMBER_PASSWORD).assertError(ErrorCode.INVALID_CREDENTIALS)
            client.send(
                HttpMethod.Post,
                "/api/v1/members/${created.memberId}/password",
                admin.accessToken,
                ResetPasswordRequest(MEMBER_PASSWORD),
            )
            assertEquals(HttpStatusCode.OK, client.login("b-user", MEMBER_PASSWORD).status)
        }

    @Test
    fun resetPasswordRevokesSessions() =
        testApp {
            val client = jsonClient()
            val admin = client.setup()
            val bob = client.memberSession(admin.accessToken, "bob", emptySet())
            val short =
                client.send(
                    HttpMethod.Post,
                    "/api/v1/members/${bob.me.memberId}/password",
                    admin.accessToken,
                    ResetPasswordRequest("short"),
                )
            assertEquals(mapOf("newPassword" to FieldErrorCode.TOO_SHORT), short.assertError(ErrorCode.VALIDATION_FAILED).error.fields)

            val response =
                client.send(
                    HttpMethod.Post,
                    "/api/v1/members/${bob.me.memberId}/password",
                    admin.accessToken,
                    ResetPasswordRequest("brand new pass"),
                )
            assertEquals(HttpStatusCode.NoContent, response.status)
            client.send(HttpMethod.Get, "/api/v1/me", bob.accessToken).assertError(ErrorCode.SESSION_REVOKED)
            client.login("bob", MEMBER_PASSWORD).assertError(ErrorCode.INVALID_CREDENTIALS)
            assertEquals(HttpStatusCode.OK, client.login("bob", "brand new pass").status)
            // The administrator's own session is untouched.
            assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "/api/v1/me", admin.accessToken).status)
        }

    @Test
    fun setGroupsChangesPermissions() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val kids = deps.groupId(admin.householdUuid, KIDS)
            val adults = deps.groupId(admin.householdUuid, ADULTS)
            val kid = client.memberSession(admin.accessToken, "kid", setOf(kids))
            assertFalse(Permission.BUDGET_VIEW in kid.me.permissions)
            val before = deps.accessVersion(kid.me.memberId)

            val response =
                client.send(
                    HttpMethod.Put,
                    "/api/v1/members/${kid.me.memberId}/groups",
                    admin.accessToken,
                    SetGroupsRequest(setOf(adults.toString())),
                )
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertTrue(Permission.BUDGET_VIEW in client.send(HttpMethod.Get, "/api/v1/me", kid.accessToken).body<Me>().permissions)
            assertEquals(before + 1, deps.accessVersion(kid.me.memberId))

            // The same set again is a no-op.
            client.send(
                HttpMethod.Put,
                "/api/v1/members/${kid.me.memberId}/groups",
                admin.accessToken,
                SetGroupsRequest(setOf(adults.toString())),
            )
            assertEquals(before + 1, deps.accessVersion(kid.me.memberId))
        }

    @Test
    fun removedMemberLosesSessionsAndLastAdminStays() =
        testApp {
            val client = jsonClient()
            val admin = client.setup()
            val bob = client.memberSession(admin.accessToken, "bob", emptySet())

            val response = client.send(HttpMethod.Delete, "/api/v1/members/${bob.me.memberId}", admin.accessToken)
            assertEquals(HttpStatusCode.NoContent, response.status)
            client.send(HttpMethod.Get, "/api/v1/me", bob.accessToken).assertError(ErrorCode.SESSION_REVOKED)
            client.login("bob", MEMBER_PASSWORD).assertError(ErrorCode.INVALID_CREDENTIALS)
            client.send(HttpMethod.Delete, "/api/v1/members/${bob.me.memberId}", admin.accessToken).assertError(ErrorCode.NOT_FOUND)

            client.send(HttpMethod.Delete, "/api/v1/members/${admin.me.memberId}", admin.accessToken).assertError(ErrorCode.LAST_ADMIN)
            assertEquals(HttpStatusCode.OK, client.login("admin", ADMIN_PASSWORD).status)
        }

    @Test
    fun relativeCanBeLinkedOnce() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val grandma = deps.createRelative(admin.householdUuid, "Grandma Val")

            val created =
                client.createMember(
                    admin.accessToken,
                    CreateMemberRequest("Grandma Val", login = "val", password = MEMBER_PASSWORD, personId = "$grandma"),
                )
            assertEquals(grandma.toString(), created.personId)
            val me = client.login("val", MEMBER_PASSWORD).body<AuthResult>().me
            assertEquals(grandma.toString(), me.personId)
            assertEquals("Grandma Val", me.displayName)

            val again =
                client.send(
                    HttpMethod.Post,
                    "/api/v1/members",
                    admin.accessToken,
                    CreateMemberRequest("Val 2", personId = "$grandma"),
                )
            assertEquals(mapOf("personId" to FieldErrorCode.TAKEN), again.assertError(ErrorCode.VALIDATION_FAILED).error.fields)
            // Nor can a member's own profile be linked again.
            val annaPerson = admin.me.personId
            val anna =
                client.send(
                    HttpMethod.Post,
                    "/api/v1/members",
                    admin.accessToken,
                    CreateMemberRequest("Anna 2", personId = annaPerson),
                )
            assertEquals(mapOf("personId" to FieldErrorCode.TAKEN), anna.assertError(ErrorCode.VALIDATION_FAILED).error.fields)
        }
}
