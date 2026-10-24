package app.lares.server.shopping

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.GroupCreated
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import app.lares.server.AppDeps
import app.lares.server.tasks.assertField
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertRejected
import app.lares.server.testing.delete
import app.lares.server.testing.fields
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.lastSeq
import app.lares.server.testing.memberSession
import app.lares.server.testing.newShoppingItem
import app.lares.server.testing.newShoppingList
import app.lares.server.testing.pull
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.upsert
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shopping spec § Permissions and privacy (SHP-001, SHP-002, SHP-004, SHP-005): one test per row, enforced by `ShoppingRules`. */
class ShoppingPermissionsTest {
    private class Family(
        val admin: AuthResult,
        val adult: AuthResult,
        val kid: AuthResult,
    )

    private suspend fun HttpClient.family(deps: AppDeps): Family {
        val admin = setup()
        return Family(
            admin,
            memberSession(admin.accessToken, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS))),
            memberSession(admin.accessToken, "kid", setOf(deps.groupId(admin.householdUuid, KIDS))),
        )
    }

    private suspend fun HttpClient.groupMember(
        f: Family,
        login: String,
        permissions: Set<Permission>,
    ): AuthResult {
        val response = send(HttpMethod.Post, "/api/v1/groups", f.admin.accessToken, CreateGroupRequest("Group $login", permissions))
        assertEquals(HttpStatusCode.Created, response.status)
        val group = UUID.fromString(response.body<GroupCreated>().groupId)
        return memberSession(f.admin.accessToken, login, setOf(group))
    }

    /** SHP-002, SHP-004, SHP-005: Kids add, tick, clear and forget items, but cannot create, rename or delete lists. */
    @Test
    fun shp002KidsEditItemsButNotLists() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val kid = f.kid.me.memberId
            client.pushOne(f.kid.accessToken, deps.newShoppingList(kid)).assertRejected(ErrorCode.FORBIDDEN)
            val list = deps.newShoppingList(f.adult.me.memberId)
            client.pushOne(f.adult.accessToken, list).assertApplied()
            client
                .pushOne(f.kid.accessToken, deps.upsert(EntityTypes.SHOPPING_LIST, list.id, fields("name" to "Mine")))
                .assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(f.kid.accessToken, deps.delete(EntityTypes.SHOPPING_LIST, list.id)).assertRejected(ErrorCode.FORBIDDEN)

            val item = deps.newShoppingItem(list.id, kid)
            client.pushOne(f.kid.accessToken, item).assertApplied()
            val tick = fields("status" to "bought", "boughtBy" to kid, "boughtAt" to deps.clock.now().toString(), "boughtCount" to 1)
            client.pushOne(f.kid.accessToken, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, tick)).assertApplied()
            client
                .pushOne(
                    f.kid.accessToken,
                    deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, fields("status" to "cleared")),
                ).assertApplied()
            client.pushOne(f.kid.accessToken, deps.delete(EntityTypes.SHOPPING_ITEM, item.id)).assertApplied()
        }

    /** SHP-001: Adults create, rename, tag, regroup and delete lists and edit items. */
    @Test
    fun shp001AdultsDoEverything() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val token = f.adult.accessToken
            val me = f.adult.me.memberId
            val list = deps.newShoppingList(me)
            client.pushOne(token, list).assertApplied()
            client
                .pushOne(token, deps.upsert(EntityTypes.SHOPPING_LIST, list.id, fields("name" to "Weekly", "groupByCategory" to false)))
                .assertApplied()
            val item = deps.newShoppingItem(list.id, me)
            client.pushOne(token, item).assertApplied()
            client.pushOne(token, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, fields("quantity" to "2 l"))).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.SHOPPING_LIST, list.id)).assertApplied()
            // A list another adult created is managed too.
            val adminsList = deps.newShoppingList(f.admin.me.memberId, "Hardware")
            client.pushOne(f.admin.accessToken, adminsList).assertApplied()
            client.pushOne(token, deps.upsert(EntityTypes.SHOPPING_LIST, adminsList.id, fields("name" to "DIY"))).assertApplied()
        }

    /** SHP-001: `shopping.view` alone reads lists and items but writes nothing. */
    @Test
    fun shp001ViewOnlyReadsButCannotWrite() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val viewer = client.groupMember(f, "viewer", setOf(Permission.SHOPPING_VIEW))
            val list = deps.newShoppingList(f.adult.me.memberId)
            client.pushOne(f.adult.accessToken, list).assertApplied()
            val item = deps.newShoppingItem(list.id, f.adult.me.memberId)
            client.pushOne(f.adult.accessToken, item).assertApplied()

            val ids = client.pullAll(viewer.accessToken).second.map { it.id }
            assertTrue(list.id in ids && item.id in ids)
            val me = viewer.me.memberId
            client.pushOne(viewer.accessToken, deps.newShoppingItem(list.id, me)).assertRejected(ErrorCode.FORBIDDEN)
            client
                .pushOne(viewer.accessToken, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, fields("status" to "cleared")))
                .assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(viewer.accessToken, deps.delete(EntityTypes.SHOPPING_ITEM, item.id)).assertRejected(ErrorCode.FORBIDDEN)
        }

    /** SHP-001: without `shopping.view` no shopping rows are pulled (the cursor still advances). */
    @Test
    fun shp001WithoutViewNothingIsVisible() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val outsider = client.groupMember(f, "outsider", setOf(Permission.TASKS_VIEW))
            val list = deps.newShoppingList(f.adult.me.memberId)
            client.pushOne(f.adult.accessToken, list).assertApplied()
            client.pushOne(f.adult.accessToken, deps.newShoppingItem(list.id, f.adult.me.memberId)).assertApplied()

            val (last, changes) = client.pullAll(outsider.accessToken)
            assertTrue(changes.none { it.type == EntityTypes.SHOPPING_LIST || it.type == EntityTypes.SHOPPING_ITEM })
            assertEquals(deps.lastSeq(f.admin.householdUuid), last.cursor)
        }

    /** SHP-002, SHP-004: the author fields a write sets must be the writer; other members' values may stay. */
    @Test
    fun shp004AuthorFieldsAreTheWriter() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val adult = f.adult.me.memberId
            val kid = f.kid.me.memberId
            val list = deps.newShoppingList(adult)
            client.pushOne(f.adult.accessToken, list).assertApplied()
            client.pushOne(f.kid.accessToken, deps.newShoppingItem(list.id, adult)).assertRejected(ErrorCode.FORBIDDEN)

            val item = deps.newShoppingItem(list.id, adult)
            client.pushOne(f.adult.accessToken, item).assertApplied()
            val now = deps.clock.now().toString()
            client
                .pushOne(
                    f.kid.accessToken,
                    deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, fields("status" to "bought", "boughtBy" to adult, "boughtAt" to now)),
                ).assertRejected(ErrorCode.FORBIDDEN)
            client
                .pushOne(
                    f.adult.accessToken,
                    deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, fields("status" to "bought", "boughtBy" to adult, "boughtAt" to now)),
                ).assertApplied()
            // The item another member added and bought: editing its note keeps their names and is allowed.
            client.pushOne(f.kid.accessToken, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, fields("note" to "Low fat"))).assertApplied()
            val stored = itemsOf(client.pull(f.kid.accessToken).changes)[item.id]
            assertEquals(adult, stored?.addedBy)
            assertEquals(adult, stored?.boughtBy)
            assertEquals("Low fat", stored?.note)
        }

    /** SHP-001: a list is created in the writer's name, and its creator never changes. */
    @Test
    fun shp001ListCreatorIsTheWriter() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            client.pushOne(f.adult.accessToken, deps.newShoppingList(f.admin.me.memberId)).assertRejected(ErrorCode.FORBIDDEN)
            val list = deps.newShoppingList(f.adult.me.memberId)
            client.pushOne(f.adult.accessToken, list).assertApplied()
            client
                .pushOne(f.adult.accessToken, deps.upsert(EntityTypes.SHOPPING_LIST, list.id, fields("createdBy" to f.admin.me.memberId)))
                .assertField("createdBy", FieldErrorCode.INVALID_FORMAT)
        }
}
