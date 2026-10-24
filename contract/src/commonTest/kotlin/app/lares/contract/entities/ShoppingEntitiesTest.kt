package app.lares.contract.entities

import app.lares.contract.api.LaresJson
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ShoppingEntitiesTest {
    private fun <T> roundTrip(
        serializer: KSerializer<T>,
        value: T,
    ): String {
        val text = LaresJson.encodeToString(serializer, value)
        assertEquals(value, LaresJson.decodeFromString(serializer, text), text)
        return text
    }

    private val bought =
        ShoppingItemData(
            listId = "l1",
            name = "Молоко",
            addedBy = "m1",
            addedAt = Instant.parse("2026-10-24T09:00:00Z"),
            status = ShoppingItemStatus.BOUGHT,
            category = ProductCategory.MEAT_FISH,
            quantity = "1,5 л",
            note = "2,5%",
            tagIds = setOf("t1", "t2"),
            boughtBy = "m2",
            boughtAt = Instant.parse("2026-10-24T10:15:30Z"),
            boughtCount = 3,
        )

    @Test
    fun `SHP-001 TAG-002 list round trip with tags and grouping`() {
        val text = roundTrip(ShoppingListData.serializer(), ShoppingListData("Продукты", "m1", setOf("t1"), groupByCategory = false))
        assertTrue("\"groupByCategory\":false" in text, text)
        val minimal = LaresJson.decodeFromString(ShoppingListData.serializer(), """{"name":"Аптека","createdBy":"m1"}""")
        assertEquals(ShoppingListData("Аптека", "m1"), minimal)
        assertTrue(minimal.groupByCategory)
    }

    @Test
    fun `SHP-002 item round trip with ISO instants, wire names and tags`() {
        val text = roundTrip(ShoppingItemData.serializer(), bought)
        assertTrue("\"status\":\"bought\"" in text, text)
        assertTrue("\"category\":\"meat_fish\"" in text, text)
        assertTrue("\"boughtAt\":\"2026-10-24T10:15:30Z\"" in text, text)
        assertTrue("\"tagIds\":[\"t1\",\"t2\"]" in text, text)
    }

    @Test
    fun `SHP-002 missing optional fields decode to defaults and nulls are omitted`() {
        val json = """{"listId":"l1","name":"Хлеб","addedBy":"m1","addedAt":"2026-10-24T09:00:00Z"}"""
        val item = LaresJson.decodeFromString(ShoppingItemData.serializer(), json)
        assertEquals(ShoppingItemStatus.NEEDED, item.status)
        assertEquals(ProductCategory.OTHER, item.category)
        assertNull(item.quantity)
        assertNull(item.boughtAt)
        assertEquals(0, item.boughtCount)
        assertEquals(emptySet(), item.tagIds)
        val text = roundTrip(ShoppingItemData.serializer(), item)
        assertFalse("quantity" in text || "note" in text || "boughtBy" in text || "boughtAt" in text, text)
        assertTrue("\"status\":\"needed\"" in text && "\"boughtCount\":0" in text, text)
    }

    @Test
    fun `unknown status decodes to cleared and unknown category to other`() {
        val json =
            """{"listId":"l1","name":"Хлеб","addedBy":"m1","addedAt":"2026-10-24T09:00:00Z","status":"reserved","category":"garden"}"""
        val item = LaresJson.decodeFromString(ShoppingItemData.serializer(), json)
        assertEquals(ShoppingItemStatus.CLEARED, item.status)
        assertEquals(ProductCategory.OTHER, item.category)
    }

    @Test
    fun `SHP-006 keys and fromKey, categories in store order`() {
        for (status in ShoppingItemStatus.entries) assertEquals(status, ShoppingItemStatus.fromKey(status.key))
        for (category in ProductCategory.entries) assertEquals(category, ProductCategory.fromKey(category.key))
        assertEquals("personal_care", ProductCategory.PERSONAL_CARE.key)
        assertEquals("cleared", ShoppingItemStatus.CLEARED.key)
        assertNull(ProductCategory.fromKey("garden"))
        assertNull(ShoppingItemStatus.fromKey("NEEDED"))
        assertEquals(
            listOf(
                "produce",
                "bakery",
                "dairy",
                "meat_fish",
                "grocery",
                "sweets",
                "drinks",
                "frozen",
                "household",
                "personal_care",
                "baby",
                "pets",
                "pharmacy",
                "other",
            ),
            ProductCategory.entries.map { it.key },
        )
    }
}
