package app.familyhub.server.identity

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.admin.CreateGroupRequest
import app.familyhub.contract.api.admin.CreateInviteRequest
import app.familyhub.contract.api.admin.CreateLoginCodeRequest
import app.familyhub.contract.api.admin.CreateMemberRequest
import app.familyhub.contract.api.admin.InviteCreated
import app.familyhub.contract.api.admin.InviteInfo
import app.familyhub.contract.api.admin.ResetPasswordRequest
import app.familyhub.contract.api.admin.SetGroupsRequest
import app.familyhub.contract.api.admin.UpdateGroupRequest
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.server.testing.ADMIN_PASSWORD
import app.familyhub.server.testing.KIDS
import app.familyhub.server.testing.assertError
import app.familyhub.server.testing.createMember
import app.familyhub.server.testing.createRelative
import app.familyhub.server.testing.createSecondHousehold
import app.familyhub.server.testing.groupId
import app.familyhub.server.testing.householdUuid
import app.familyhub.server.testing.jsonClient
import app.familyhub.server.testing.lastSeq
import app.familyhub.server.testing.login
import app.familyhub.server.testing.send
import app.familyhub.server.testing.setup
import app.familyhub.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FND-002, ADR-0010: an administrator of household A can neither reach nor reference anything of household B.
 * Every attempt answers 404 (never 403, which would reveal that the id exists) and writes nothing.
 */
class HouseholdIsolationTest {
    @Test
    fun adminOfOneHouseholdCannotTouchAnother() =
        testApp { deps ->
            val client = jsonClient()
            val adminA = client.setup()
            val householdA = adminA.householdUuid
            val householdB = deps.createSecondHousehold(login = "admin-b")
            val adminB = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            assertEquals(householdB, adminB.householdUuid)

            val memberA = client.createMember(adminA.accessToken, CreateMemberRequest("Alice")).memberId
            val memberB = client.createMember(adminB.accessToken, CreateMemberRequest("Bert")).memberId
            val groupB = deps.groupId(householdB, KIDS).toString()
            val personB = deps.createRelative(householdB, "Uncle B").toString()
            val inviteB =
                client
                    .send(
                        HttpMethod.Post,
                        "/api/v1/invites",
                        adminB.accessToken,
                        CreateInviteRequest(emptySet()),
                    ).body<InviteCreated>()
                    .id

            val token = adminA.accessToken
            val attempts =
                listOf(
                    "create group with B's member" to
                        suspend {
                            client.send(
                                HttpMethod.Post,
                                "/api/v1/groups",
                                token,
                                CreateGroupRequest("X", memberIds = setOf(memberB)),
                            )
                        },
                    "rename B's group" to
                        suspend { client.send(HttpMethod.Patch, "/api/v1/groups/$groupB", token, UpdateGroupRequest(name = "X")) },
                    "delete B's group" to suspend { client.send(HttpMethod.Delete, "/api/v1/groups/$groupB", token) },
                    "set A's member into B's group" to
                        suspend { client.send(HttpMethod.Put, "/api/v1/members/$memberA/groups", token, SetGroupsRequest(setOf(groupB))) },
                    "set groups of B's member" to
                        suspend { client.send(HttpMethod.Put, "/api/v1/members/$memberB/groups", token, SetGroupsRequest(emptySet())) },
                    "remove B's member" to suspend { client.send(HttpMethod.Delete, "/api/v1/members/$memberB", token) },
                    "reset password of B's member" to
                        suspend {
                            client.send(
                                HttpMethod.Post,
                                "/api/v1/members/$memberB/password",
                                token,
                                ResetPasswordRequest("new password 1"),
                            )
                        },
                    "create member linked to B's person" to
                        suspend { client.send(HttpMethod.Post, "/api/v1/members", token, CreateMemberRequest("X", personId = personB)) },
                    "create member in B's group" to
                        suspend {
                            client.send(
                                HttpMethod.Post,
                                "/api/v1/members",
                                token,
                                CreateMemberRequest("X", groupIds = setOf(groupB)),
                            )
                        },
                    "invite into B's group" to
                        suspend { client.send(HttpMethod.Post, "/api/v1/invites", token, CreateInviteRequest(setOf(groupB))) },
                    "invite for B's person" to
                        suspend {
                            client.send(
                                HttpMethod.Post,
                                "/api/v1/invites",
                                token,
                                CreateInviteRequest(emptySet(), personId = personB),
                            )
                        },
                    "revoke B's invite" to suspend { client.send(HttpMethod.Delete, "/api/v1/invites/$inviteB", token) },
                    "login code for B's member" to
                        suspend { client.send(HttpMethod.Post, "/api/v1/login-codes", token, CreateLoginCodeRequest(memberB)) },
                )

            val seqA = deps.lastSeq(householdA)
            val seqB = deps.lastSeq(householdB)
            for ((name, attempt) in attempts) {
                val response = attempt()
                assertEquals(HttpStatusCode.NotFound, response.status, name)
                response.assertError(ErrorCode.NOT_FOUND)
            }
            assertEquals(seqA, deps.lastSeq(householdA), "nothing written in A")
            assertEquals(seqB, deps.lastSeq(householdB), "nothing written in B")

            // B's invite is still active and not listed for A.
            assertEquals(emptyList(), client.send(HttpMethod.Get, "/api/v1/invites", token).body<List<InviteInfo>>())
            assertEquals(
                listOf(inviteB),
                client.send(HttpMethod.Get, "/api/v1/invites", adminB.accessToken).body<List<InviteInfo>>().map { it.id },
            )
            assertTrue(client.send(HttpMethod.Get, "/api/v1/me", adminB.accessToken).status == HttpStatusCode.OK)
        }
}
