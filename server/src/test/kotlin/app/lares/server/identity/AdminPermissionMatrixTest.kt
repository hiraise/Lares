package app.lares.server.identity

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.CreateInviteRequest
import app.lares.contract.api.admin.CreateMemberRequest
import app.lares.contract.api.admin.GroupCreated
import app.lares.contract.api.admin.InviteCreated
import app.lares.contract.api.admin.ResetPasswordRequest
import app.lares.contract.api.admin.SetGroupsRequest
import app.lares.contract.api.admin.UpdateGroupRequest
import app.lares.contract.api.admin.UpdateHouseholdRequest
import app.lares.contract.api.location.PutLocationRulesRequest
import app.lares.contract.location.LocationVisibility
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertError
import app.lares.server.testing.createMember
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.lastSeq
import app.lares.server.testing.memberSession
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every admin endpoint: Administrators succeed, members of "Adults" and "Kids" get 403 and nothing is written (FND-027). */
class AdminPermissionMatrixTest {
    private class Endpoint(
        val name: String,
        val expected: HttpStatusCode,
        val call: suspend (token: String) -> HttpResponse,
    )

    @Test
    fun onlyHouseholdManagersReachAdminEndpoints() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val householdId = admin.householdUuid
            val adults = deps.groupId(householdId, ADULTS)
            val kids = deps.groupId(householdId, KIDS)
            val adult = client.memberSession(admin.accessToken, "adult", setOf(adults))
            val kid = client.memberSession(admin.accessToken, "kid", setOf(kids))
            val target = client.createMember(admin.accessToken, CreateMemberRequest("Target")).memberId
            val group =
                client
                    .send(HttpMethod.Post, "/api/v1/groups", admin.accessToken, CreateGroupRequest("Temp"))
                    .body<GroupCreated>()
                    .groupId
            val invite =
                client
                    .send(HttpMethod.Post, "/api/v1/invites", admin.accessToken, CreateInviteRequest(emptySet()))
                    .body<InviteCreated>()
                    .id

            val endpoints =
                listOf(
                    Endpoint("PATCH household", HttpStatusCode.NoContent) {
                        client.send(HttpMethod.Patch, "/api/v1/household", it, UpdateHouseholdRequest(name = "Renamed"))
                    },
                    Endpoint("POST members", HttpStatusCode.Created) {
                        client.send(HttpMethod.Post, "/api/v1/members", it, CreateMemberRequest("New"))
                    },
                    Endpoint("POST members/password", HttpStatusCode.NoContent) {
                        client.send(HttpMethod.Post, "/api/v1/members/$target/password", it, ResetPasswordRequest("new password 1"))
                    },
                    Endpoint("PUT members/groups", HttpStatusCode.NoContent) {
                        client.send(HttpMethod.Put, "/api/v1/members/$target/groups", it, SetGroupsRequest(setOf(kids.toString())))
                    },
                    Endpoint("DELETE members", HttpStatusCode.NoContent) {
                        client.send(HttpMethod.Delete, "/api/v1/members/$target", it)
                    },
                    Endpoint("POST groups", HttpStatusCode.Created) {
                        client.send(HttpMethod.Post, "/api/v1/groups", it, CreateGroupRequest("Another"))
                    },
                    Endpoint("PATCH groups", HttpStatusCode.NoContent) {
                        client.send(HttpMethod.Patch, "/api/v1/groups/$group", it, UpdateGroupRequest(name = "Temp 2"))
                    },
                    Endpoint("DELETE groups", HttpStatusCode.NoContent) {
                        client.send(HttpMethod.Delete, "/api/v1/groups/$group", it)
                    },
                    Endpoint("POST invites", HttpStatusCode.Created) {
                        client.send(HttpMethod.Post, "/api/v1/invites", it, CreateInviteRequest(emptySet()))
                    },
                    Endpoint("GET invites", HttpStatusCode.OK) {
                        client.send(HttpMethod.Get, "/api/v1/invites", it)
                    },
                    Endpoint("DELETE invites", HttpStatusCode.NoContent) {
                        client.send(HttpMethod.Delete, "/api/v1/invites/$invite", it)
                    },
                    Endpoint("GET location/rules", HttpStatusCode.OK) {
                        client.send(HttpMethod.Get, "/api/v1/location/rules", it)
                    },
                    Endpoint("PUT location/rules", HttpStatusCode.NoContent) {
                        val rules = LocationVisibility.defaultRules(adults.toString(), kids.toString())
                        client.send(HttpMethod.Put, "/api/v1/location/rules", it, PutLocationRulesRequest(rules))
                    },
                )

            for (endpoint in endpoints) {
                val seqBefore = deps.lastSeq(householdId)
                for ((who, session) in listOf("adult" to adult, "kid" to kid)) {
                    val response = endpoint.call(session.accessToken)
                    assertEquals(HttpStatusCode.Forbidden, response.status, "${endpoint.name} as $who")
                    response.assertError(ErrorCode.FORBIDDEN)
                }
                assertEquals(seqBefore, deps.lastSeq(householdId), "${endpoint.name}: nothing written by non-admins")
                assertEquals(endpoint.expected, endpoint.call(admin.accessToken).status, "${endpoint.name} as admin")
            }
        }
}
