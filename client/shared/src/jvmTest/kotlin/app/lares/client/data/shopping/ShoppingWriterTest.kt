package app.lares.client.data.shopping

import app.lares.client.data.tasks.ANYA
import app.lares.client.data.tasks.DIMA
import app.lares.client.data.tasks.OLGA
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalValidationException
import app.lares.client.sync.LocalWriteException
import app.lares.contract.api.ErrorCode
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class ShoppingWriterTest {
    private val f = ShoppingFixture()
    private val start = Instant.parse("2026-10-21T10:00:00Z")
    private val list = "01928c4e-0000-7000-8000-0000000000l1"
    private val other = "01928c4e-0000-7000-8000-0000000000l2"

    private suspend fun setup(
        member: String = OLGA,
        vararg permissions: app.lares.contract.permissions.Permission = SHOPPER,
    ) {
        f.family()
        f.putList(list, ShoppingListData("Продукты", OLGA))
        f.putList(other, ShoppingListData("Дача", OLGA))
        f.loginAs(member, *permissions)
    }

    private fun row(
        name: String,
        status: ShoppingItemStatus = ShoppingItemStatus.NEEDED,
        listId: String = list,
        category: ProductCategory = ProductCategory.OTHER,
        quantity: String? = null,
        note: String? = null,
        count: Int = 0,
        tagIds: Set<String> = emptySet(),
        addedAt: Instant = start - 2.days,
    ) = ShoppingItemData(
        listId = listId,
        name = name,
        addedBy = DIMA,
        addedAt = addedAt,
        status = status,
        category = category,
        quantity = quantity,
        note = note,
        tagIds = tagIds,
        boughtBy = if (status == ShoppingItemStatus.NEEDED && count == 0) null else DIMA,
        boughtAt = if (status == ShoppingItemStatus.NEEDED && count == 0) null else start - 1.days,
        boughtCount = count,
    )

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun `SHP-001 lists are created, changed and deleted`() =
        runTest {
            setup()
            val id = f.shoppingWriter.createList("  Хозтовары ")
            f.shoppingWriter.updateList(id, groupByCategory = false)
            f.shoppingWriter.updateList(id, tagIds = setOf("t2", "t1"))
            f.shoppingWriter.updateList(id)
            f.shoppingWriter.deleteList(id)
            val outbox = f.outbox()
            assertEquals(listOf("upsert", "upsert", "upsert", "delete"), outbox.map { it.op })
            val create = f.fields(0)
            assertEquals("Хозтовары", create.str("name"))
            assertEquals(OLGA, create.str("createdBy"))
            assertEquals(setOf("groupByCategory"), f.fields(1).keys)
            assertEquals("[\"t1\",\"t2\"]", f.fields(2)["tagIds"].toString())
        }

    @Test
    fun `SHP-003 quick add creates items with guessed departments`() =
        runTest {
            setup()
            val result = f.shoppingWriter.add(list, "Молоко 2 л, хлеб")
            assertEquals(AddResult(2, emptyList()), result)
            val created = f.outbox().map { f.item(it.entity_id) }
            assertEquals(listOf("Молоко", "Хлеб"), created.map { it.name })
            assertEquals(listOf("2 л", null), created.map { it.quantity })
            assertEquals(listOf(ProductCategory.DAIRY, ProductCategory.BAKERY), created.map { it.category })
            assertTrue(created.all { it.addedBy == OLGA && it.addedAt == start && it.status == ShoppingItemStatus.NEEDED })
            assertEquals(
                listOf("молоко", "хлеб"),
                f
                    .items(list)
                    .sortedBy { it.added_at }
                    .map { it.name_key }
                    .sorted(),
            )
        }

    @Test
    fun `SHP-009 adding a name to buy keeps the row and updates the quantity`() =
        runTest {
            setup()
            f.putItem("i1", row("Молоко", quantity = "1 л"))
            val result = f.shoppingWriter.add(list, "молоко 2 л")
            assertEquals(AddResult(0, listOf("Молоко")), result)
            assertEquals(1, f.items(list).size)
            assertEquals(JsonObject(mapOf("quantity" to JsonPrimitive("2 л"))), f.fields(0))
            // Same quantity or none: nothing to write.
            f.shoppingWriter.add(list, "Молоко 2 л; молоко")
            assertEquals(1, f.outbox().size)
        }

    @Test
    fun `SHP-009 adding a cleared name brings it back with its note and department`() =
        runTest {
            setup()
            f.putItem(
                "i1",
                row(
                    "Молоко 2,5%",
                    ShoppingItemStatus.CLEARED,
                    category = ProductCategory.PRODUCE,
                    quantity = "1 л",
                    note = "Без лактозы",
                    count = 4,
                ),
            )
            val result = f.shoppingWriter.add(list, "молоко 2,5%")
            assertEquals(AddResult(1, emptyList()), result)
            val item = f.item("i1")
            assertEquals(ShoppingItemStatus.NEEDED, item.status)
            assertEquals(OLGA, item.addedBy)
            assertEquals(start, item.addedAt)
            assertNull(item.quantity)
            assertEquals("Без лактозы", item.note)
            assertEquals(ProductCategory.PRODUCE, item.category)
            assertEquals(4, item.boughtCount)
            assertEquals(setOf("status", "addedBy", "addedAt", "quantity"), f.fields(0).keys)
            assertEquals(JsonNull, f.fields(0)["quantity"])
        }

    @Test
    fun `SHP-009 a bought name comes back with the new quantity and duplicates in one text are merged`() =
        runTest {
            setup()
            f.putItem("i1", row("Хлеб", ShoppingItemStatus.BOUGHT, count = 1))
            val result = f.shoppingWriter.add(list, "хлеб 2\nХлеб\nсыр 100 г, Сыр 200 г")
            assertEquals(AddResult(2, emptyList()), result)
            assertEquals("2", f.item("i1").quantity)
            assertEquals(ShoppingItemStatus.NEEDED, f.item("i1").status)
            val cheese = f.items(list).single { it.name_key == "сыр" }
            assertEquals("сыр", cheese.name.lowercase())
            assertEquals("200 г", cheese.quantity)
            assertEquals(2, f.outbox().size)
        }

    @Test
    fun `SHP-009 a needed row wins over a cleared row with the same name`() =
        runTest {
            setup()
            f.putItem("i1", row("Хлеб", ShoppingItemStatus.CLEARED, addedAt = start - 1.days))
            f.putItem("i2", row("хлеб", ShoppingItemStatus.NEEDED, addedAt = start - 3.days))
            assertEquals(AddResult(0, listOf("хлеб")), f.shoppingWriter.add(list, "Хлеб"))
            assertEquals(emptyList(), f.outbox())
        }

    @Test
    fun `SHP-006 the department of another list's row wins over the dictionary`() =
        runTest {
            setup()
            f.putItem("old", row("Молоко", listId = other, category = ProductCategory.OTHER, addedAt = start - 3.days))
            f.putItem("new", row("молоко", listId = other, category = ProductCategory.GROCERY, addedAt = start - 1.days))
            // A row of a list deleted locally is not the family's memory.
            f.putItem("orphan", row("Молоко", listId = "deleted", category = ProductCategory.PETS, addedAt = start))
            f.shoppingWriter.add(list, "Молоко")
            assertEquals(ProductCategory.GROCERY, f.item(f.outbox().single().entity_id).category)
        }

    @Test
    fun `SHP-014 add with a tag tags new and brought back rows`() =
        runTest {
            setup()
            f.putItem("cleared", row("Уголь", ShoppingItemStatus.CLEARED, tagIds = setOf("t-other")))
            f.putItem("needed", row("Перчатки"))
            val result = f.shoppingWriter.add(list, "уголь, перчатки, розжиг", tagId = "t-dacha")
            assertEquals(AddResult(2, listOf("Перчатки")), result)
            assertEquals(setOf("t-dacha", "t-other"), f.item("cleared").tagIds)
            assertEquals(setOf("t-dacha"), f.item("needed").tagIds)
            val created = f.items(list).single { it.name_key == "розжиг" }
            assertEquals("[\"t-dacha\"]", created.tag_ids)
        }

    @Test
    fun `SHP-008 suggestions bring back their row or add a new one`() =
        runTest {
            setup()
            f.putItem("i1", row("Сыр", ShoppingItemStatus.CLEARED, quantity = "200 г", count = 3))
            f.shoppingWriter.addSuggestion(list, Suggestion("Сыр", ProductCategory.DAIRY, null, "i1"))
            assertEquals(ShoppingItemStatus.NEEDED, f.item("i1").status)
            assertNull(f.item("i1").quantity)
            f.shoppingWriter.addSuggestion(list, Suggestion("Уголь", ProductCategory.GROCERY, null, null), tagId = "t-dacha")
            val coal = f.items(list).single { it.name_key == "уголь" }
            assertEquals(ProductCategory.GROCERY.key, coal.category)
            assertEquals("[\"t-dacha\"]", coal.tag_ids)
        }

    @Test
    fun `SHP-004 ticking and unticking count purchases`() =
        runTest {
            setup()
            f.putItem("i1", row("Хлеб", count = 2))
            f.shoppingWriter.setBought("i1", true)
            var item = f.item("i1")
            assertEquals(ShoppingItemStatus.BOUGHT, item.status)
            assertEquals(OLGA, item.boughtBy)
            assertEquals(start, item.boughtAt)
            assertEquals(3, item.boughtCount)
            // Already bought: no second count.
            f.shoppingWriter.setBought("i1", true)
            assertEquals(1, f.outbox().size)
            f.shoppingWriter.setBought("i1", false)
            item = f.item("i1")
            assertEquals(ShoppingItemStatus.NEEDED, item.status)
            assertEquals(2, item.boughtCount)
            assertEquals(OLGA, item.boughtBy)
            assertEquals(setOf("status", "boughtCount"), f.fields(1).keys)
            f.shoppingWriter.setBought("i1", false)
            assertEquals(2, f.outbox().size)
            f.putItem("i2", row("Соль", ShoppingItemStatus.BOUGHT, count = 0))
            f.shoppingWriter.setBought("i2", false)
            assertEquals(0, f.item("i2").boughtCount)
        }

    @Test
    fun `SHP-004 clear bought and undo`() =
        runTest {
            setup()
            f.putItem("a", row("Хлеб", ShoppingItemStatus.BOUGHT, count = 1))
            f.putItem("b", row("Уголь", ShoppingItemStatus.BOUGHT, count = 1, tagIds = setOf("t-dacha")))
            f.putItem("c", row("Сыр"))
            f.putItem("d", row("Соль", ShoppingItemStatus.BOUGHT, count = 1, listId = other))
            assertEquals(listOf("b"), f.shoppingWriter.clearBought(list, "t-dacha"))
            assertEquals(ShoppingItemStatus.BOUGHT, f.item("a").status)
            val cleared = f.shoppingWriter.clearBought(list)
            assertEquals(listOf("a"), cleared)
            assertEquals(ShoppingItemStatus.CLEARED, f.item("a").status)
            assertEquals(ShoppingItemStatus.NEEDED, f.item("c").status)
            assertEquals(ShoppingItemStatus.BOUGHT, f.item("d").status)
            f.shoppingWriter.restore(listOf("a", "b", "gone"), ShoppingItemStatus.BOUGHT)
            assertEquals(ShoppingItemStatus.BOUGHT, f.item("a").status)
            assertEquals(ShoppingItemStatus.BOUGHT, f.item("b").status)
            assertTrue(f.outbox().all { f.fieldsOf(it.fields).keys == setOf("status") })
        }

    @Test
    fun `SHP-002 remove, undo and forget`() =
        runTest {
            setup()
            f.putItem("a", row("Хлеб"))
            assertEquals(ShoppingItemStatus.NEEDED, f.shoppingWriter.remove("a"))
            assertEquals(ShoppingItemStatus.CLEARED, f.item("a").status)
            f.shoppingWriter.restore(listOf("a"), ShoppingItemStatus.NEEDED)
            assertEquals(ShoppingItemStatus.NEEDED, f.item("a").status)
            f.shoppingWriter.forget("a")
            assertTrue(f.items(list).isEmpty())
            assertEquals(listOf("upsert", "upsert", "delete"), f.outbox().map { it.op })
        }

    @Test
    fun `SHP-010 update item sends changed fields only`() =
        runTest {
            setup()
            f.putItem("a", row("Хлеб", quantity = "1", note = "Бородинский"))
            f.shoppingWriter.updateItem("a", "Хлеб", null, "Бородинский", setOf("t1"))
            assertEquals(setOf("quantity", "tagIds"), f.fields(0).keys)
            f.shoppingWriter.updateItem("a", "Хлеб", null, "Бородинский", setOf("t1"))
            assertEquals(1, f.outbox().size)
        }

    @Test
    fun `SHP-006 a department correction rewrites the same name in other lists`() =
        runTest {
            setup()
            f.putItem("a", row("Молоко", category = ProductCategory.OTHER))
            f.putItem("b", row("молоко", listId = other, category = ProductCategory.OTHER))
            f.putItem("c", row("МОЛОКО", listId = other, category = ProductCategory.DAIRY, status = ShoppingItemStatus.CLEARED))
            f.putItem("d", row("Молоко", listId = "deleted", category = ProductCategory.OTHER))
            f.putItem("e", row("Кефир", category = ProductCategory.OTHER))
            f.shoppingWriter.setCategory("a", ProductCategory.DAIRY)
            assertEquals(listOf("a", "b"), f.outbox().map { it.entity_id })
            assertEquals(ProductCategory.DAIRY, f.item("b").category)
            assertEquals(ProductCategory.OTHER, f.item("d").category)
            assertEquals(ProductCategory.OTHER, f.item("e").category)
        }

    @Test
    fun `SHP-006 a refused extra row never fails the user's own correction`() =
        runTest {
            setup()
            f.putItem("a", row("Молоко"))
            f.putItem("b", row("Молоко", listId = other))
            // The other list is deleted locally (row gone, deletion pending): its items stay until the cascade arrives.
            f.shoppingWriter.deleteList(other)
            f.shoppingWriter.setCategory("a", ProductCategory.DAIRY)
            assertEquals(ProductCategory.DAIRY, f.item("a").category)
            assertEquals(ProductCategory.OTHER, f.item("b").category)
            assertEquals(listOf(other, "a"), f.outbox().map { it.entity_id })
        }

    @Test
    fun `SHP-001 a kid cannot create a list and a viewer cannot add`() =
        runTest {
            setup(ANYA, *SHOPPING_KID)
            assertFailsWith<LocalForbiddenException> { f.shoppingWriter.createList("Своё") }
            assertFailsWith<LocalForbiddenException> { f.shoppingWriter.updateList(list, name = "Моё") }
            assertFailsWith<LocalForbiddenException> { f.shoppingWriter.deleteList(list) }
            assertEquals(emptyList(), f.outbox())
            // A kid may add and tick.
            f.shoppingWriter.add(list, "Сок")
            assertEquals(1, f.outbox().size)

            f.loginAs(DIMA, *SHOPPING_VIEWER)
            assertFailsWith<LocalForbiddenException> { f.shoppingWriter.add(list, "Чипсы") }
            assertEquals(1, f.outbox().size)
        }

    @Test
    fun `SHP-002 adapters refuse foreign authors, a changed list and a missing list`() =
        runTest {
            setup()
            f.putItem("a", row("Хлеб"))
            assertFailsWith<LocalForbiddenException> {
                f.writer.upsert(EntityTypes.SHOPPING_ITEM, "a", JsonObject(mapOf("addedBy" to JsonPrimitive(DIMA + "x"))))
            }
            assertFailsWith<LocalForbiddenException> {
                f.writer.upsert(EntityTypes.SHOPPING_ITEM, "a", JsonObject(mapOf("boughtBy" to JsonPrimitive(ANYA))))
            }
            val moved =
                assertFailsWith<LocalValidationException> {
                    f.writer.upsert(EntityTypes.SHOPPING_ITEM, "a", JsonObject(mapOf("listId" to JsonPrimitive(other))))
                }
            assertEquals(setOf("listId"), moved.fields.keys)
            val creator =
                assertFailsWith<LocalValidationException> {
                    f.writer.upsert(EntityTypes.SHOPPING_LIST, list, JsonObject(mapOf("createdBy" to JsonPrimitive(DIMA))))
                }
            assertEquals(setOf("createdBy"), creator.fields.keys)
            val missing = assertFailsWith<LocalWriteException> { f.shoppingWriter.add("no-such-list", "Хлеб") }
            assertEquals(ErrorCode.ENTITY_NOT_FOUND, missing.code)
            assertEquals(emptyList(), f.outbox())
        }
}
