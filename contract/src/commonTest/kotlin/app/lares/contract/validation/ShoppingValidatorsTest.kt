package app.lares.contract.validation

import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ShoppingValidatorsTest {
    private val at = Instant.parse("2026-10-24T10:00:00Z")
    private val list = ShoppingListData("Продукты", "m1")
    private val item = ShoppingItemData("l1", "Хлеб", "m1", at)
    private val tags = (1..21).map { "t$it" }.toSet()

    @Test
    fun `SHP-001 list rules`() {
        assertEquals(emptyMap(), Validators.shoppingList(list))
        assertEquals(mapOf("name" to FieldErrorCode.REQUIRED), Validators.shoppingList(list.copy(name = "  ")))
        assertEquals(mapOf("name" to FieldErrorCode.TOO_LONG), Validators.shoppingList(list.copy(name = "а".repeat(101))))
        assertEquals(emptyMap(), Validators.shoppingList(list.copy(name = "а".repeat(100), tagIds = tags.take(20).toSet())))
        assertEquals(mapOf("tagIds" to FieldErrorCode.OUT_OF_RANGE), Validators.shoppingList(list.copy(tagIds = tags)))
    }

    @Test
    fun `SHP-002 item rules`() {
        assertEquals(emptyMap(), Validators.shoppingItem(item))
        assertEquals(mapOf("name" to FieldErrorCode.REQUIRED), Validators.shoppingItem(item.copy(name = "")))
        assertEquals(mapOf("name" to FieldErrorCode.TOO_LONG), Validators.shoppingItem(item.copy(name = "а".repeat(201))))
        assertEquals(mapOf("quantity" to FieldErrorCode.TOO_LONG), Validators.shoppingItem(item.copy(quantity = "1".repeat(31))))
        assertEquals(mapOf("note" to FieldErrorCode.TOO_LONG), Validators.shoppingItem(item.copy(note = "а".repeat(501))))
        assertEquals(mapOf("tagIds" to FieldErrorCode.OUT_OF_RANGE), Validators.shoppingItem(item.copy(tagIds = tags)))
        assertEquals(mapOf("boughtCount" to FieldErrorCode.OUT_OF_RANGE), Validators.shoppingItem(item.copy(boughtCount = -1)))
        assertEquals(mapOf("boughtCount" to FieldErrorCode.OUT_OF_RANGE), Validators.shoppingItem(item.copy(boughtCount = 100_001)))
        assertEquals(
            emptyMap(),
            Validators.shoppingItem(item.copy(boughtCount = 100_000, quantity = "1".repeat(30), note = "а".repeat(500))),
        )
    }

    @Test
    fun `SHP-004 a bought item needs who and when`() {
        val bought = item.copy(status = ShoppingItemStatus.BOUGHT)
        assertEquals(
            mapOf("boughtBy" to FieldErrorCode.REQUIRED, "boughtAt" to FieldErrorCode.REQUIRED),
            Validators.shoppingItem(bought),
        )
        assertEquals(mapOf("boughtAt" to FieldErrorCode.REQUIRED), Validators.shoppingItem(bought.copy(boughtBy = "m1")))
        assertEquals(mapOf("boughtBy" to FieldErrorCode.REQUIRED), Validators.shoppingItem(bought.copy(boughtAt = at)))
        assertEquals(emptyMap(), Validators.shoppingItem(bought.copy(boughtBy = "m1", boughtAt = at)))
        assertEquals(emptyMap(), Validators.shoppingItem(item.copy(status = ShoppingItemStatus.CLEARED, boughtAt = at)))
    }

    @Test
    fun normalizers() {
        assertEquals(list.copy(name = "Продукты"), Validators.normalizeShoppingList(list.copy(name = "  Продукты \n")))
        val messy = item.copy(name = "  Молоко   2,5% ", quantity = "  ", note = " без лактозы ")
        val clean = Validators.normalizeShoppingItem(messy)
        assertEquals("Молоко 2,5%", clean.name)
        assertEquals(null, clean.quantity)
        assertEquals("без лактозы", clean.note)
        assertEquals("2 л", Validators.normalizeShoppingItem(item.copy(quantity = " 2 л ")).quantity)
        assertEquals(null, Validators.normalizeShoppingItem(item.copy(note = "")).note)
    }
}
