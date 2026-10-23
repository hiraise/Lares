package app.lares.server.identity

import app.lares.contract.Codes
import app.lares.contract.DeepLink
import app.lares.contract.DeepLinks
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.admin.AcceptInviteRequest
import app.lares.contract.api.admin.CreateInviteRequest
import app.lares.contract.api.admin.InviteCreated
import app.lares.contract.api.admin.InviteInfo
import app.lares.contract.api.admin.InvitePreview
import app.lares.contract.api.admin.InvitePreviewRequest
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.permissions.Permission
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertError
import app.lares.server.testing.createRelative
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.testDevice
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** FND-024 invites; FND-032 linking a relative on accept. */
class InviteTest {
    private suspend fun HttpClient.createInvite(
        token: String,
        request: CreateInviteRequest,
    ): InviteCreated {
        val response = send(HttpMethod.Post, "/api/v1/invites", token, request)
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.preview(code: String): HttpResponse =
        send(HttpMethod.Post, "/api/v1/invites/preview", null, InvitePreviewRequest(code))

    private suspend fun HttpClient.accept(
        code: String,
        login: String = "newbie",
        displayName: String = "Newbie",
    ): HttpResponse =
        send(HttpMethod.Post, "/api/v1/invites/accept", null, AcceptInviteRequest(code, displayName, login, "newbie password", testDevice))

    @Test
    fun inviteFlow() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val kids = deps.groupId(admin.householdUuid, KIDS)
            val invite = client.createInvite(admin.accessToken, CreateInviteRequest(setOf(kids.toString())))
            assertEquals(12, invite.code.length)
            assertEquals(DeepLink.Invite(deps.config.publicUrl, invite.code), DeepLinks.parse(invite.payload))
            assertEquals(deps.testClock.now() + 72.hours, invite.expiresAt)

            val listed = client.send(HttpMethod.Get, "/api/v1/invites", admin.accessToken).body<List<InviteInfo>>()
            assertEquals(listOf(invite.id), listed.map { it.id })
            assertEquals(setOf(kids.toString()), listed.single().groupIds)
            assertEquals(admin.me.memberId, listed.single().createdBy)

            // Codes are normalized: the displayed form in lowercase works too.
            val typed = Codes.format(invite.code).lowercase()
            assertEquals(InvitePreview("The Smiths", "Anna"), client.preview(typed).body<InvitePreview>())

            val accepted = client.accept(typed)
            assertEquals(HttpStatusCode.OK, accepted.status)
            val newbie = accepted.body<AuthResult>()
            assertEquals("Newbie", newbie.me.displayName)
            assertEquals("newbie", newbie.me.login)
            assertTrue(Permission.TASKS_VIEW in newbie.me.permissions)
            assertFalse(Permission.BUDGET_VIEW in newbie.me.permissions)
            assertEquals(HttpStatusCode.OK, client.login("newbie", "newbie password").status)

            client.accept(invite.code, login = "second").assertError(ErrorCode.INVITE_INVALID)
            client.preview(invite.code).assertError(ErrorCode.INVITE_INVALID)
            assertEquals(emptyList(), client.send(HttpMethod.Get, "/api/v1/invites", admin.accessToken).body<List<InviteInfo>>())
        }

    @Test
    fun expiredInviteIsInvalid() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val invite = client.createInvite(admin.accessToken, CreateInviteRequest(emptySet(), expiresInHours = 1))
            deps.testClock.advance(1.hours)
            client.preview(invite.code).assertError(ErrorCode.INVITE_INVALID)
            val response = client.accept(invite.code)
            assertEquals(HttpStatusCode.NotFound, response.status)
            response.assertError(ErrorCode.INVITE_INVALID)
        }

    @Test
    fun revokedInviteIsInvalid() =
        testApp {
            val client = jsonClient()
            val admin = client.setup()
            val invite = client.createInvite(admin.accessToken, CreateInviteRequest(emptySet()))
            assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/api/v1/invites/${invite.id}", admin.accessToken).status)
            client.accept(invite.code).assertError(ErrorCode.INVITE_INVALID)
            assertEquals(emptyList(), client.send(HttpMethod.Get, "/api/v1/invites", admin.accessToken).body<List<InviteInfo>>())
        }

    @Test
    fun inviteInputRules() =
        testApp {
            val client = jsonClient()
            val admin = client.setup()
            for (hours in listOf(0, 30 * 24 + 1)) {
                val response = client.send(HttpMethod.Post, "/api/v1/invites", admin.accessToken, CreateInviteRequest(emptySet(), hours))
                assertEquals(
                    mapOf("expiresInHours" to FieldErrorCode.OUT_OF_RANGE),
                    response.assertError(ErrorCode.VALIDATION_FAILED).error.fields,
                )
            }
            val invite = client.createInvite(admin.accessToken, CreateInviteRequest(emptySet()))
            val taken = client.accept(invite.code, login = "admin")
            assertEquals(HttpStatusCode.Conflict, taken.status)
            taken.assertError(ErrorCode.LOGIN_TAKEN)
            // A failed accept does not use up the invite.
            assertEquals(HttpStatusCode.OK, client.accept(invite.code).status)
        }

    @Test
    fun acceptingLinksTheInvitedRelative() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val grandma = deps.createRelative(admin.householdUuid, "Grandma")
            val invite = client.createInvite(admin.accessToken, CreateInviteRequest(emptySet(), personId = grandma.toString()))
            val me = client.accept(invite.code, login = "val", displayName = "Valentina").body<AuthResult>().me
            assertEquals(grandma.toString(), me.personId)
            assertEquals("Valentina", me.displayName)

            // The relative is linked now: a new invite for her is refused.
            client
                .send(HttpMethod.Post, "/api/v1/invites", admin.accessToken, CreateInviteRequest(emptySet(), personId = grandma.toString()))
                .assertError(ErrorCode.VALIDATION_FAILED)
        }

    @Test
    fun wrongCodesAreRateLimitedPerIp() =
        testApp {
            val client = jsonClient()
            client.setup()
            repeat(10) { client.preview("WRONGCODE123").assertError(ErrorCode.INVITE_INVALID) }
            val limited = client.preview("WRONGCODE123")
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            limited.assertError(ErrorCode.RATE_LIMITED)
        }
}
