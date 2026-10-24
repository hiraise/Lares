package app.lares.server.shopping

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.sync.Change
import app.lares.contract.sync.EntityTypes
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.tasks.assertField
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertRejected
import app.lares.server.testing.createSecondHousehold
import app.lares.server.testing.dataAs
import app.lares.server.testing.delete
import app.lares.server.testing.fields
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.jsonStrings
import app.lares.server.testing.lastSeq
import app.lares.server.testing.login
import app.lares.server.testing.newShoppingItem
import app.lares.server.testing.newShoppingList
import app.lares.server.testing.newTag
import app.lares.server.testing.pull
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upsert
import io.ktor.client.call.body
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Shopping on the server (M2-02): SHP-001, SHP-002, SHP-004, SHP-005, SHP-006, SHP-014, TAG-002 — storage, sync, validation, cascade. */
class ShoppingSyncTest {
    /** SHP-001, SHP-002, SHP-006, SHP-014: lists and items round trip with every field and wire names. */
    @Test
    fun shp001ListsAndItemsRoundTrip() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val tag = deps.newTag("Party")
            client.pushOne(token, tag).assertApplied()
            val list = deps.newShoppingList(me, "  Groceries  ", mapOf("tagIds" to jsonStrings(tag.id), "groupByCategory" to false))
            client.pushOne(token, list).assertApplied()
            val at = deps.clock.now()
            val item =
                deps.newShoppingItem(
                    list.id,
                    me,
                    "  Whole   milk ",
                    at,
                    mapOf(
                        "category" to "dairy",
                        "quantity" to " 2 ",
                        "note" to "  ",
                        "tagIds" to jsonStrings(tag.id),
                        "boughtCount" to 3,
                    ),
                )
            client.pushOne(token, item).assertApplied()

            val changes = client.pull(token).changes
            assertEquals(ShoppingListData("Groceries", me, setOf(tag.id), groupByCategory = false), listsOf(changes)[list.id])
            assertEquals(
                ShoppingItemData(
                    listId = list.id,
                    name = "Whole milk",
                    addedBy = me,
                    addedAt = at,
                    category = ProductCategory.DAIRY,
                    quantity = "2",
                    tagIds = setOf(tag.id),
                    boughtCount = 3,
                ),
                itemsOf(changes)[item.id],
            )
            val wire = assertNotNull(changes.single { it.id == item.id }.data)
            assertEquals("needed", wire["status"]?.jsonPrimitive?.content)
            assertEquals("dairy", wire["category"]?.jsonPrimitive?.content)
        }

    /** SHP-004, SHP-005, SHP-014: tick, clear and re-add; an unchanged upsert is a no-op. */
    @Test
    fun shp004TickClearReAdd() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val list = deps.newShoppingList(me)
            client.pushOne(token, list).assertApplied()
            val item = deps.newShoppingItem(list.id, me)
            client.pushOne(token, item).assertApplied()
            assertNull(client.pushOne(token, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, item.fields!!)).assertApplied())

            deps.testClock.advance(10.minutes)
            val boughtAt = deps.clock.now()
            val tick = fields("status" to "bought", "boughtBy" to me, "boughtAt" to boughtAt.toString(), "boughtCount" to 1)
            client.pushOne(token, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, tick)).assertApplied()
            var stored = assertNotNull(itemsOf(client.pull(token).changes)[item.id])
            assertEquals(ShoppingItemStatus.BOUGHT, stored.status)
            assertEquals(me, stored.boughtBy)
            assertEquals(boughtAt, stored.boughtAt)
            assertEquals(1, stored.boughtCount)

            client.pushOne(token, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, fields("status" to "cleared"))).assertApplied()
            assertEquals(ShoppingItemStatus.CLEARED, itemsOf(client.pull(token).changes)[item.id]?.status)

            deps.testClock.advance(5.minutes)
            val readdedAt = deps.clock.now()
            val readd = fields("status" to "needed", "addedBy" to me, "addedAt" to readdedAt.toString())
            client.pushOne(token, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, readd)).assertApplied()
            stored = assertNotNull(itemsOf(client.pull(token).changes)[item.id])
            assertEquals(ShoppingItemStatus.NEEDED, stored.status)
            assertEquals(readdedAt, stored.addedAt)
            // Kept for suggestions (ADR-0021).
            assertEquals(boughtAt, stored.boughtAt)
            assertEquals(1, stored.boughtCount)
        }

    /** SHP-002, SHP-006: field checks of items and lists. */
    @Test
    fun shp002InvalidItemsAreRejected() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val list = deps.newShoppingList(me)
            client.pushOne(token, list).assertApplied()

            suspend fun push(vararg extra: Pair<String, Any?>) =
                client.pushOne(token, deps.newShoppingItem(list.id, me, extra = mapOf(*extra)))
            push("status" to "lost").assertField("status", FieldErrorCode.INVALID_FORMAT)
            push("category" to "toys").assertField("category", FieldErrorCode.INVALID_FORMAT)
            push("status" to "bought", "boughtAt" to deps.clock.now().toString()).assertField("boughtBy", FieldErrorCode.REQUIRED)
            push("name" to "   ").assertField("name", FieldErrorCode.REQUIRED)
            push("tagIds" to jsonStrings(Ids.newId())).assertField("tagIds", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(token, deps.newShoppingItem(list.id, me, at = deps.clock.now() + 2.days))
                .assertField("addedAt", FieldErrorCode.OUT_OF_RANGE)
            push(
                "status" to "bought",
                "boughtBy" to me,
                "boughtAt" to (deps.clock.now() + 2.days).toString(),
            ).assertField("boughtAt", FieldErrorCode.OUT_OF_RANGE)
            client
                .pushOne(token, deps.newShoppingList(me, extra = mapOf("tagIds" to jsonStrings(Ids.newId()))))
                .assertField("tagIds", FieldErrorCode.INVALID_FORMAT)

            val item = deps.newShoppingItem(list.id, me)
            client.pushOne(token, item).assertApplied()
            val other = deps.newShoppingList(me, "Hardware")
            client.pushOne(token, other).assertApplied()
            client
                .pushOne(token, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, fields("listId" to other.id)))
                .assertField("listId", FieldErrorCode.INVALID_FORMAT)
        }

    /** SHP-002: an item needs a live list of the household. */
    @Test
    fun shp002ItemNeedsLiveParentList() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            client.pushOne(token, deps.newShoppingItem(Ids.newId(), me)).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            client.pushOne(token, deps.newShoppingItem("not-a-uuid", me)).assertRejected(ErrorCode.ENTITY_NOT_FOUND)

            val list = deps.newShoppingList(me)
            client.pushOne(token, list).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.SHOPPING_LIST, list.id)).assertApplied()
            client.pushOne(token, deps.newShoppingItem(list.id, me)).assertRejected(ErrorCode.ENTITY_DELETED)

            deps.createSecondHousehold()
            val other = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            val foreign = deps.newShoppingList(other.me.memberId)
            client.pushOne(other.accessToken, foreign).assertApplied()
            client.pushOne(token, deps.newShoppingItem(foreign.id, me)).assertRejected(ErrorCode.ENTITY_NOT_FOUND)
            assertTrue(client.pullAll(token).second.none { it.id == foreign.id })
        }

    /** TAG-002: a stored tag that was deleted since stays on the item and the list through unrelated edits. */
    @Test
    fun tag002DeletedTagStaysOnUnrelatedEdit() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val tag = deps.newTag("Party")
            client.pushOne(token, tag).assertApplied()
            val list = deps.newShoppingList(me, extra = mapOf("tagIds" to jsonStrings(tag.id)))
            client.pushOne(token, list).assertApplied()
            val item = deps.newShoppingItem(list.id, me, extra = mapOf("tagIds" to jsonStrings(tag.id)))
            client.pushOne(token, item).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.TAG, tag.id)).assertApplied()

            client.pushOne(token, deps.upsert(EntityTypes.SHOPPING_ITEM, item.id, fields("note" to "Low fat"))).assertApplied()
            client.pushOne(token, deps.upsert(EntityTypes.SHOPPING_LIST, list.id, fields("name" to "Party food"))).assertApplied()
            val changes = client.pull(token).changes
            assertEquals(setOf(tag.id), itemsOf(changes)[item.id]?.tagIds)
            assertEquals(setOf(tag.id), listsOf(changes)[list.id]?.tagIds)
        }

    /** SHP-001: deleting a list tombstones its items, each with its own seq after the list's; paged pulls get them all. */
    @Test
    fun shp001DeleteCascadesToItems() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val list = deps.newShoppingList(me)
            client.pushOne(token, list).assertApplied()
            val items = listOf("Milk", "Bread", "Eggs", "Tea").map { deps.newShoppingItem(list.id, me, it) }
            items.forEach { client.pushOne(token, it).assertApplied() }
            val other = deps.newShoppingList(me, "Hardware")
            client.pushOne(token, other).assertApplied()
            val otherItem = deps.newShoppingItem(other.id, me, "Nails")
            client.pushOne(token, otherItem).assertApplied()
            val (before, _) = client.pullAll(token)

            val listSeq = assertNotNull(client.pushOne(token, deps.delete(EntityTypes.SHOPPING_LIST, list.id)).assertApplied())
            val (_, tombstones) = client.pullAll(token, before)
            assertEquals((items.map { it.id } + list.id).toSet(), tombstones.map { it.id }.toSet())
            assertTrue(tombstones.all { it.deleted && it.data == null })
            val itemSeqs = tombstones.filter { it.type == EntityTypes.SHOPPING_ITEM }.map { it.seq }
            assertEquals(4, itemSeqs.toSet().size, "distinct seqs")
            assertTrue(itemSeqs.all { it > listSeq })
            val (_, paged) = client.pullAll(token, before, limit = 2)
            assertEquals(tombstones.map { it.id }, paged.map { it.id })
            assertNull(client.pushOne(token, deps.delete(EntityTypes.SHOPPING_LIST, list.id)).assertApplied())
            assertNotNull(itemsOf(client.pull(token).changes)[otherItem.id])
        }

    /** Housekeeping purges item and list tombstones after 90 days and raises the purge horizon. */
    @Test
    fun tombstonesArePurged() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val list = deps.newShoppingList(me)
            client.pushOne(token, list).assertApplied()
            client.pushOne(token, deps.newShoppingItem(list.id, me, "Milk")).assertApplied()
            client.pushOne(token, deps.newShoppingItem(list.id, me, "Bread")).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.SHOPPING_LIST, list.id)).assertApplied()
            val lastSeq = deps.lastSeq(admin.householdUuid)

            assertEquals(0, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            deps.testClock.advance(91.days)
            assertEquals(3, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            val horizon =
                deps.db.dbQuery {
                    HouseholdSyncStateTable
                        .selectAll()
                        .where { HouseholdSyncStateTable.householdId eq admin.householdUuid }
                        .single()[HouseholdSyncStateTable.purgeHorizonSeq]
                }
            assertEquals(lastSeq, horizon)
            assertNull(deps.db.dbQuery { ShoppingRepository.loadList(UUID.fromString(list.id)) })
        }
}

internal fun listsOf(changes: List<Change>): Map<String, ShoppingListData> =
    changes.filter { it.type == EntityTypes.SHOPPING_LIST && !it.deleted }.associate { it.id to it.dataAs<ShoppingListData>() }

internal fun itemsOf(changes: List<Change>): Map<String, ShoppingItemData> =
    changes.filter { it.type == EntityTypes.SHOPPING_ITEM && !it.deleted }.associate { it.id to it.dataAs<ShoppingItemData>() }
