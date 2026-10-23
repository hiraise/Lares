package app.familyhub.server.household

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.api.admin.CreateGroupRequest
import app.familyhub.contract.api.admin.GroupCreated
import app.familyhub.contract.api.admin.SetGroupsRequest
import app.familyhub.contract.api.admin.UpdateGroupRequest
import app.familyhub.contract.api.auth.Me
import app.familyhub.contract.permissions.Permission
import app.familyhub.server.testing.KIDS
import app.familyhub.server.testing.accessVersion
import app.familyhub.server.testing.administratorsId
import app.familyhub.server.testing.assertError
import app.familyhub.server.testing.groupId
import app.familyhub.server.testing.groupSeq
import app.familyhub.server.testing.householdUuid
import app.familyhub.server.testing.jsonClient
import app.familyhub.server.testing.memberSession
import app.familyhub.server.testing.send
import app.familyhub.server.testing.setup
import app.familyhub.server.testing.testApp
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FND-021, FND-022: groups, permissions, memberships and the rules of "Administrators". */
class GroupAdminTest {
    @Test
    fun createdGroupStoresOnlyGivenPermissionsAndEvaluationAddsImpliedSections() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val member = client.memberSession(admin.accessToken, "helper", emptySet())
            assertEquals(emptySet(), member.me.permissions)
            val accessBefore = deps.accessVersion(member.me.memberId)

            val response =
                client.send(
                    HttpMethod.Post,
                    "/api/v1/groups",
                    admin.accessToken,
                    CreateGroupRequest("Helpers", setOf(Permission.TASKS_CREATE), setOf(member.me.memberId)),
                )
            assertEquals(HttpStatusCode.Created, response.status)
            val groupId = UUID.fromString(response.body<GroupCreated>().groupId)

            val stored =
                deps.db.dbQuery {
                    GroupPermissionTable
                        .selectAll()
                        .where { GroupPermissionTable.groupId eq groupId }
                        .map { it[GroupPermissionTable.permission] }
                }
            assertEquals(listOf("tasks.create"), stored)
            val me = client.send(HttpMethod.Get, "/api/v1/me", member.accessToken).body<Me>()
            assertEquals(setOf(Permission.TASKS_CREATE, Permission.TASKS_VIEW), me.permissions)
            assertEquals(accessBefore + 1, deps.accessVersion(member.me.memberId))
        }

    @Test
    fun administratorsCannotBeRenamedDeletedOrGivenPermissions() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val admins = deps.administratorsId(admin.householdUuid)
            val path = "/api/v1/groups/$admins"
            client.send(HttpMethod.Patch, path, admin.accessToken, UpdateGroupRequest(name = "Bosses")).assertError(ErrorCode.BUILTIN_GROUP)
            client
                .send(HttpMethod.Patch, path, admin.accessToken, UpdateGroupRequest(permissions = setOf(Permission.TASKS_VIEW)))
                .assertError(ErrorCode.BUILTIN_GROUP)
            val delete = client.send(HttpMethod.Delete, path, admin.accessToken)
            assertEquals(HttpStatusCode.Conflict, delete.status)
            delete.assertError(ErrorCode.BUILTIN_GROUP)
        }

    @Test
    fun administratorsKeepAtLeastOneMember() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val admins = deps.administratorsId(admin.householdUuid)
            client
                .send(HttpMethod.Patch, "/api/v1/groups/$admins", admin.accessToken, UpdateGroupRequest(memberIds = emptySet()))
                .assertError(ErrorCode.LAST_ADMIN)
            client
                .send(HttpMethod.Put, "/api/v1/members/${admin.me.memberId}/groups", admin.accessToken, SetGroupsRequest(emptySet()))
                .assertError(ErrorCode.LAST_ADMIN)

            // Membership of Administrators can change: hand over to Bob, then Anna may leave.
            val bob = client.memberSession(admin.accessToken, "bob", emptySet())
            val handOver =
                client.send(
                    HttpMethod.Patch,
                    "/api/v1/groups/$admins",
                    admin.accessToken,
                    UpdateGroupRequest(memberIds = setOf(bob.me.memberId)),
                )
            assertEquals(HttpStatusCode.NoContent, handOver.status)
            assertTrue(client.send(HttpMethod.Get, "/api/v1/me", bob.accessToken).body<Me>().isAdmin)
            val anna = client.send(HttpMethod.Get, "/api/v1/me", admin.accessToken).body<Me>()
            assertFalse(anna.isAdmin)
        }

    @Test
    fun deletingGroupRemovesMembershipsAndBumpsAccessVersion() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val kids = deps.groupId(admin.householdUuid, KIDS)
            val kid = client.memberSession(admin.accessToken, "kid", setOf(kids))
            val accessBefore = deps.accessVersion(kid.me.memberId)
            val seqBefore = deps.groupSeq(kids)

            assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/api/v1/groups/$kids", admin.accessToken).status)

            assertEquals(accessBefore + 1, deps.accessVersion(kid.me.memberId))
            assertEquals(emptySet(), client.send(HttpMethod.Get, "/api/v1/me", kid.accessToken).body<Me>().permissions)
            deps.db.dbQuery {
                val group = GroupTable.selectAll().where { GroupTable.id eq kids }.single()
                assertTrue(group[GroupTable.deleted])
                assertTrue(group[GroupTable.seq] > seqBefore)
                assertEquals(0, GroupMembershipTable.selectAll().where { GroupMembershipTable.groupId eq kids }.count())
            }
            // A deleted group is gone for every further operation.
            client.send(HttpMethod.Delete, "/api/v1/groups/$kids", admin.accessToken).assertError(ErrorCode.NOT_FOUND)
            client
                .send(
                    HttpMethod.Put,
                    "/api/v1/members/${kid.me.memberId}/groups",
                    admin.accessToken,
                    SetGroupsRequest(setOf(kids.toString())),
                ).assertError(ErrorCode.NOT_FOUND)
        }

    @Test
    fun changingPermissionsBumpsMembersAndGroupSeq() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val kids = deps.groupId(admin.householdUuid, KIDS)
            val kid = client.memberSession(admin.accessToken, "kid", setOf(kids))
            val outsider = client.memberSession(admin.accessToken, "outsider", emptySet())
            val accessBefore = deps.accessVersion(kid.me.memberId)
            val outsiderBefore = deps.accessVersion(outsider.me.memberId)
            val seqBefore = deps.groupSeq(kids)

            val response =
                client.send(
                    HttpMethod.Patch,
                    "/api/v1/groups/$kids",
                    admin.accessToken,
                    UpdateGroupRequest(name = "Children", permissions = setOf(Permission.BUDGET_VIEW)),
                )
            assertEquals(HttpStatusCode.NoContent, response.status)

            assertEquals(accessBefore + 1, deps.accessVersion(kid.me.memberId))
            assertEquals(outsiderBefore, deps.accessVersion(outsider.me.memberId))
            assertTrue(deps.groupSeq(kids) > seqBefore)
            assertEquals(setOf(Permission.BUDGET_VIEW), client.send(HttpMethod.Get, "/api/v1/me", kid.accessToken).body<Me>().permissions)
            deps.db.dbQuery { assertEquals("Children", GroupTable.selectAll().where { GroupTable.id eq kids }.single()[GroupTable.name]) }

            // Setting the same permissions again changes nothing.
            val seqAfter = deps.groupSeq(kids)
            client.send(
                HttpMethod.Patch,
                "/api/v1/groups/$kids",
                admin.accessToken,
                UpdateGroupRequest(permissions = setOf(Permission.BUDGET_VIEW)),
            )
            assertEquals(seqAfter, deps.groupSeq(kids))
            assertEquals(accessBefore + 1, deps.accessVersion(kid.me.memberId))
        }

    @Test
    fun membershipChangesThroughGroupBumpOnlyAffectedMembers() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val kids = deps.groupId(admin.householdUuid, KIDS)
            val stays = client.memberSession(admin.accessToken, "stays", setOf(kids))
            val joins = client.memberSession(admin.accessToken, "joins", emptySet())
            val staysBefore = deps.accessVersion(stays.me.memberId)
            val joinsBefore = deps.accessVersion(joins.me.memberId)

            client.send(
                HttpMethod.Patch,
                "/api/v1/groups/$kids",
                admin.accessToken,
                UpdateGroupRequest(memberIds = setOf(stays.me.memberId, joins.me.memberId)),
            )
            assertEquals(staysBefore, deps.accessVersion(stays.me.memberId))
            assertEquals(joinsBefore + 1, deps.accessVersion(joins.me.memberId))
            assertTrue(Permission.TASKS_VIEW in client.send(HttpMethod.Get, "/api/v1/me", joins.accessToken).body<Me>().permissions)
        }

    @Test
    fun invalidGroupInput() =
        testApp {
            val client = jsonClient()
            val admin = client.setup()
            val blank = client.send(HttpMethod.Post, "/api/v1/groups", admin.accessToken, CreateGroupRequest("  "))
            assertEquals(mapOf("name" to FieldErrorCode.REQUIRED), blank.assertError(ErrorCode.VALIDATION_FAILED).error.fields)
            val tooLong = client.send(HttpMethod.Post, "/api/v1/groups", admin.accessToken, CreateGroupRequest("x".repeat(41)))
            assertEquals(mapOf("name" to FieldErrorCode.TOO_LONG), tooLong.assertError(ErrorCode.VALIDATION_FAILED).error.fields)
            client
                .send(
                    HttpMethod.Post,
                    "/api/v1/groups",
                    admin.accessToken,
                    CreateGroupRequest("G", memberIds = setOf(UUID.randomUUID().toString())),
                ).assertError(ErrorCode.NOT_FOUND)
            client
                .send(HttpMethod.Patch, "/api/v1/groups/not-a-uuid", admin.accessToken, UpdateGroupRequest(name = "G"))
                .assertError(ErrorCode.NOT_FOUND)
        }
}
