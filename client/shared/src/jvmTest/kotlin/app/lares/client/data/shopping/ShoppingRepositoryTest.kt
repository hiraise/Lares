package app.lares.client.data.shopping

import app.lares.client.data.tasks.DIMA
import app.lares.client.data.tasks.OLGA
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ShoppingRepositoryTest {
    private val f = ShoppingFixture()
    private val start = Instant.parse("2026-10-21T10:00:00Z")

    @Test
    fun `SHP-012 who is shopping disappears when the ticker passes 15 minutes`() =
        runTest {
            f.family()
            f.loginAs(OLGA, *SHOPPER)
            f.putList("l1", ShoppingListData("Продукты", OLGA))
            f.putItem("i1", ShoppingItemData("l1", "Сыр", OLGA, start - 30.minutes))
            f.putItem(
                "i2",
                ShoppingItemData(
                    "l1",
                    "Хлеб",
                    OLGA,
                    start - 30.minutes,
                    ShoppingItemStatus.BOUGHT,
                    boughtBy = DIMA,
                    boughtAt = start,
                    boughtCount = 1,
                ),
            )
            assertEquals(
                "Дмитрий",
                f.repository
                    .observeActive()
                    .first()
                    ?.list
                    ?.shopper
                    ?.name,
            )
            val lists = f.repository.observeLists()
            f.setNow(start + 15.minutes)
            assertEquals(
                DIMA,
                lists
                    .first()
                    .single()
                    .shopper
                    ?.id,
            )
            f.setNow(start + 16.minutes)
            assertNull(lists.first { it.single().shopper == null }.single().shopper)
            assertNull(
                f.repository
                    .observeDetail("l1")
                    .first()!!
                    .list.shopper,
            )
        }

    @Test
    fun `SHP-013 views follow local writes`() =
        runTest {
            f.family()
            f.loginAs(OLGA, *SHOPPER)
            val id = f.shoppingWriter.createList("Продукты")
            assertNull(f.repository.observeActive().first())
            f.shoppingWriter.add(id, "Молоко, хлеб", tagId = null)
            val active = f.repository.observeActive().first()!!
            assertEquals(id, active.list.id)
            assertEquals(listOf("Хлеб", "Молоко"), active.list.remaining)
            assertEquals(
                2,
                f.repository
                    .observeDetail(id)
                    .first()!!
                    .sections.size,
            )
            assertEquals(
                0,
                f.repository
                    .observeForTag("t")
                    .first()
                    .items.size,
            )
        }
}
