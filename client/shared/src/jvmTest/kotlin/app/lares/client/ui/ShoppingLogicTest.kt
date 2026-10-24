package app.lares.client.ui

import app.lares.client.data.MemberView
import app.lares.client.data.shopping.ShoppingItemView
import app.lares.client.data.shopping.ShoppingListView
import app.lares.client.sync.SyncState
import app.lares.client.sync.SyncStatus
import app.lares.client.ui.common.UiText
import app.lares.client.ui.common.load
import app.lares.client.ui.shopping.ShoppingLogic
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ShoppingLogicTest {
    private val dima = MemberView("m2", MemberData("p2", "u2", "dima", true), "Дмитрий Петров", PaletteColor.GREEN)
    private val at = Instant.parse("2026-10-21T15:40:00Z")

    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private fun both(text: UiText): Pair<String, String> =
        runBlocking {
            Locale.setDefault(Locale.ENGLISH)
            val en = text.load()
            Locale.setDefault(Locale.forLanguageTag("ru"))
            val ru = text.load()
            Locale.setDefault(Locale.ENGLISH)
            en to ru
        }

    private fun assertTexts(
        en: String,
        ru: String,
        text: UiText?,
    ) = assertEquals(en to ru, both(text!!))

    private fun list(
        needed: Int,
        bought: Int,
        remaining: List<String> = emptyList(),
        shopper: MemberView? = null,
    ) = ShoppingListView("l1", ShoppingListData("Продукты", "m1"), needed, bought, remaining, shopper, null, emptyList())

    private fun item(
        name: String,
        mine: Boolean = true,
        boughtBy: MemberView? = null,
        boughtAt: Instant? = null,
    ) = ShoppingItemView(
        "i-$name",
        ShoppingItemData("l1", name, "m1", at, ShoppingItemStatus.BOUGHT, boughtAt = boughtAt),
        emptyList(),
        addedBy = dima,
        boughtBy = boughtBy,
        mine = mine,
    )

    @Test
    fun `card texts`() {
        val full = list(5, 4, listOf("Молоко", "Хлеб", "Яблоки"), shopper = dima)
        assertTexts("4 of 9", "4 из 9", ShoppingLogic.countOf(full))
        assertTexts("Bought 4 of 9", "Куплено 4 из 9", ShoppingLogic.progress(full))
        assertEquals(4f / 9, ShoppingLogic.fraction(full))
        val line = ShoppingLogic.cardLine(full)
        assertFalse(line.done)
        assertTexts("Left: Молоко, Хлеб, Яблоки and 2 more", "Осталось: Молоко, Хлеб, Яблоки и ещё 2", line.text)
        assertTexts("Left: Молоко, Хлеб", "Осталось: Молоко, Хлеб", ShoppingLogic.cardLine(list(2, 0, listOf("Молоко", "Хлеб"))).text)
        assertTexts("Дмитрий is shopping now", "Дмитрий сейчас в магазине", ShoppingLogic.shopperNow(full))
        assertTexts("Дмитрий is shopping", "Дмитрий в магазине", ShoppingLogic.shopperShort(full))

        val done = ShoppingLogic.cardLine(list(0, 3))
        assertTrue(done.done)
        assertTexts("All bought", "Всё куплено", done.text)

        val empty = list(0, 0)
        assertTexts("Empty", "Пусто", ShoppingLogic.cardLine(empty).text)
        assertNull(ShoppingLogic.countOf(empty))
        assertNull(ShoppingLogic.progress(empty))
        assertEquals(0f, ShoppingLogic.fraction(empty))
        assertNull(ShoppingLogic.shopperNow(empty))
    }

    @Test
    fun `plural forms`() {
        for (n in listOf(1, 2, 5, 21)) {
            val names = List(n + 3) { "N$it" }
            val shown = names.take(3).joinToString(", ")
            assertTexts(
                "Left: $shown and $n more",
                "Осталось: $shown и ещё $n",
                ShoppingLogic.cardLine(list(n + 3, 0, names.take(3))).text,
            )
        }
        assertTexts("1 item cleared", "Убрано: 1", ShoppingLogic.cleared(1))
        assertTexts("2 items cleared", "Убрано: 2", ShoppingLogic.cleared(2))
        assertTexts("5 items cleared", "Убрано: 5", ShoppingLogic.cleared(5))
        assertTexts("21 items cleared", "Убрано: 21", ShoppingLogic.cleared(21))
    }

    @Test
    fun `bought section texts`() {
        val bought = listOf("Творог", "Яйца", "Сыр", "Бананы", "Кефир", "Масло").map { item(it) }
        assertTexts("Bought · 6", "Куплено · 6", ShoppingLogic.boughtTitle(6))
        assertTexts("Творог, Яйца, Сыр and 3 more", "Творог, Яйца, Сыр и ещё 3", ShoppingLogic.boughtPreview(bought))
        assertTexts("Творог, Яйца", "Творог, Яйца", ShoppingLogic.boughtPreview(bought.take(2)))
        val meta = ShoppingLogic.boughtMeta(item("Сыр", boughtBy = dima, boughtAt = at)) { "18:40" }
        assertTexts("Дмитрий · 18:40", "Дмитрий · 18:40", meta)
        assertNull(ShoppingLogic.boughtMeta(item("Сыр")) { "18:40" })
    }

    @Test
    fun `adder, already there and first name`() {
        assertEquals("Дмитрий", ShoppingLogic.firstName(dima))
        assertEquals("Аня", ShoppingLogic.firstName(dima.copy(name = "  Аня ")))
        assertEquals("Дмитрий", ShoppingLogic.adder(item("Хлеб", mine = false)))
        assertNull(ShoppingLogic.adder(item("Хлеб", mine = true)))
        assertTexts("Already in the list: Молоко, Хлеб", "Уже в списке: Молоко, Хлеб", ShoppingLogic.alreadyThere(listOf("Молоко", "Хлеб")))
    }

    @Test
    fun `offline banner`() {
        fun status(
            state: SyncState,
            pending: Long,
        ) = SyncStatus(state, pending, null, null)
        assertNull(ShoppingLogic.banner(null))
        assertNull(ShoppingLogic.banner(status(SyncState.SYNCED, 0)))
        assertNull(ShoppingLogic.banner(status(SyncState.PENDING, 2)))
        assertTexts(
            "No network. Your ticks are saved and will be sent when the connection is back.",
            "Нет сети. Отметки сохранены и отправятся, когда связь появится.",
            ShoppingLogic.banner(status(SyncState.OFFLINE, 2)),
        )
        assertTexts(
            "No network. The list may be out of date.",
            "Нет сети. Список может быть не самым свежим.",
            ShoppingLogic.banner(status(SyncState.OFFLINE, 0)),
        )
    }

    @Test
    fun `department labels`() {
        val labels =
            runBlocking {
                Locale.setDefault(Locale.forLanguageTag("ru"))
                ProductCategory.entries.map { getString(ShoppingLogic.categoryLabel(it)) }.also { Locale.setDefault(Locale.ENGLISH) }
            }
        assertEquals(
            listOf(
                "Овощи и фрукты",
                "Хлеб и выпечка",
                "Молочное и яйца",
                "Мясо и рыба",
                "Бакалея",
                "Сладкое и снеки",
                "Напитки",
                "Замороженное",
                "Хозтовары и химия",
                "Гигиена и косметика",
                "Детское",
                "Для животных",
                "Аптека",
                "Другое",
            ),
            labels,
        )
        val en = runBlocking { ProductCategory.entries.map { getString(ShoppingLogic.categoryLabel(it)) } }
        assertEquals("Fruit and vegetables", en.first())
        assertEquals("Pantry", en[ProductCategory.GROCERY.ordinal])
        assertEquals(ProductCategory.entries.size, en.toSet().size)
    }
}
