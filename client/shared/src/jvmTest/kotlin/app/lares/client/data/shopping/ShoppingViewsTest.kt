package app.lares.client.data.shopping

import app.lares.client.data.MemberView
import app.lares.client.data.Tag
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ProductCategory.BAKERY
import app.lares.contract.entities.ProductCategory.DAIRY
import app.lares.contract.entities.ProductCategory.GROCERY
import app.lares.contract.entities.ProductCategory.OTHER
import app.lares.contract.entities.ProductCategory.PRODUCE
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingItemStatus.BOUGHT
import app.lares.contract.entities.ShoppingItemStatus.CLEARED
import app.lares.contract.entities.ShoppingItemStatus.NEEDED
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.entities.TagData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ShoppingViewsTest {
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private val members = listOf(member("olga", "Ольга"), member("dima", "Дмитрий"))
    private val tags = listOf(tag("t-dacha", "Дача"), tag("t-old", "Старая", archived = true))
    private val lists = mutableListOf<Pair<String, ShoppingListData>>()
    private val items = mutableListOf<Pair<String, ShoppingItemData>>()
    private var counter = 0

    private fun member(
        id: String,
        name: String,
    ) = MemberView(id, MemberData("p-$id", null, null, false), name, PaletteColor.BLUE)

    private fun tag(
        id: String,
        name: String,
        archived: Boolean = false,
    ) = Tag(id, TagData(name, PaletteColor.GREEN, null, archived))

    private fun id(prefix: String) = "$prefix-%04d".format(++counter)

    private fun list(
        name: String,
        group: Boolean = true,
        tagIds: Set<String> = emptySet(),
    ): String = id("l").also { lists += it to ShoppingListData(name, "olga", tagIds, group) }

    private fun item(
        listId: String,
        name: String,
        status: ShoppingItemStatus = NEEDED,
        category: ProductCategory = OTHER,
        addedAgo: Duration = 60.minutes,
        boughtAgo: Duration? = null,
        boughtBy: String? = null,
        count: Int = 0,
        tagIds: Set<String> = emptySet(),
        addedBy: String = "olga",
        note: String? = null,
    ): String =
        id("i").also {
            items += it to
                ShoppingItemData(
                    listId = listId,
                    name = name,
                    addedBy = addedBy,
                    addedAt = now - addedAgo,
                    status = status,
                    category = category,
                    note = note,
                    tagIds = tagIds,
                    boughtBy = boughtBy ?: boughtAgo?.let { "olga" },
                    boughtAt = boughtAgo?.let { now - it },
                    boughtCount = count,
                )
        }

    private fun inputs(
        me: String? = "olga",
        at: Instant = now,
        vararg permissions: Permission = arrayOf(Permission.SHOPPING_VIEW, Permission.SHOPPING_EDIT),
    ) = ShoppingInputs(
        lists.toList(),
        items.toList(),
        members,
        tags,
        me?.let { Viewer(it, "p-$it", EffectivePermissions(false, permissions.toSet())) },
        at,
    )

    private fun names(section: DepartmentSection) = section.items.map { it.data.name }

    @Test
    fun `SHP-001 lists in creation order with counts and names to buy`() {
        val b = list("Аптека")
        val a = list("Продукты")
        item(a, "Хлеб", category = BAKERY, addedAgo = 3.minutes)
        item(a, "Молоко", category = DAIRY, addedAgo = 5.minutes)
        item(a, "Яблоки", category = PRODUCE, addedAgo = 1.minutes)
        item(a, "Сыр", category = DAIRY, addedAgo = 2.minutes)
        item(a, "Кефир", BOUGHT, DAIRY, boughtAgo = 30.minutes)
        item(a, "Соль", CLEARED, GROCERY, boughtAgo = 300.minutes)
        val views = ShoppingViews.lists(inputs())
        assertEquals(listOf(b, a), views.map { it.id })
        val p = views[1]
        assertEquals(4, p.needed)
        assertEquals(1, p.bought)
        // Display order: departments in store order, then addedAt.
        assertEquals(listOf("Яблоки", "Хлеб", "Молоко"), p.remaining)
        assertEquals(now - 1.minutes, p.lastActivity)
        assertNull(views[0].lastActivity)
        assertEquals(0, views[0].needed)
    }

    @Test
    fun `SHP-006 grouped by department in enum order only with two departments or more`() {
        val l = list("Продукты")
        item(l, "Молоко", category = DAIRY, addedAgo = 5.minutes)
        item(l, "Хлеб", category = BAKERY, addedAgo = 4.minutes)
        item(l, "Сыр", category = DAIRY, addedAgo = 6.minutes)
        val d = ShoppingViews.detail(inputs(), l)!!
        assertEquals(listOf(BAKERY, DAIRY), d.sections.map { it.category })
        assertEquals(listOf("Сыр", "Молоко"), names(d.sections[1]))

        // One department → one ungrouped section.
        val one = list("Молочка")
        item(one, "Молоко", category = DAIRY)
        item(one, "Кефир", category = DAIRY)
        val single = ShoppingViews.detail(inputs(), one)!!.sections.single()
        assertNull(single.category)

        // Grouping off.
        val off = list("Без отделов", group = false)
        item(off, "Хлеб", category = BAKERY, addedAgo = 1.minutes)
        item(off, "Молоко", category = DAIRY, addedAgo = 2.minutes)
        val flat = ShoppingViews.detail(inputs(), off)!!.sections.single()
        assertNull(flat.category)
        assertEquals(listOf("Молоко", "Хлеб"), names(flat))
    }

    @Test
    fun `SHP-006 same addedAt ordered by id`() {
        val l = list("Продукты")
        val first = item(l, "Б", addedAgo = 1.minutes)
        val second = item(l, "А", addedAgo = 1.minutes)
        assertEquals(
            listOf(first, second),
            ShoppingViews
                .detail(inputs(), l)!!
                .sections
                .single()
                .items
                .map { it.id },
        )
    }

    @Test
    fun `SHP-004 bought newest tick first and cleared items hidden`() {
        val l = list("Продукты")
        item(l, "Старое", BOUGHT, boughtAgo = 10.minutes)
        item(l, "Новое", BOUGHT, boughtAgo = 1.minutes)
        item(l, "Убрано", CLEARED, boughtAgo = 0.minutes)
        val d = ShoppingViews.detail(inputs(), l)!!
        assertEquals(listOf("Новое", "Старое"), d.bought.map { it.data.name })
        assertTrue(d.sections.isEmpty())
        assertEquals(0, d.list.needed)
        assertEquals(2, d.list.bought)
    }

    @Test
    fun `SHP-008 suggestions ranked by count then last bought then name and limited`() {
        val l = list("Продукты")
        item(l, "Редко", CLEARED, count = 1, boughtAgo = 1.minutes)
        item(l, "Часто", CLEARED, count = 9, boughtAgo = 900.minutes)
        item(l, "Вчера", CLEARED, count = 3, boughtAgo = 1440.minutes)
        item(l, "Сегодня", CLEARED, count = 3, boughtAgo = 60.minutes)
        item(l, "Никогда", CLEARED, count = 3)
        item(l, "Банан", CLEARED, count = 2)
        item(l, "Апельсин", CLEARED, count = 2)
        item(l, "Купить", NEEDED, count = 50)
        repeat(5) { item(l, "Хвост $it", CLEARED) }
        val suggestions = ShoppingViews.detail(inputs(), l)!!.suggestions.map { it.data.name }
        assertEquals(ShoppingViews.SUGGESTIONS_MAX, suggestions.size)
        assertEquals(listOf("Часто", "Сегодня", "Вчера", "Никогда", "Апельсин", "Банан", "Редко", "Хвост 0"), suggestions)
    }

    @Test
    fun `SHP-014 tag filter applies to items and counts but not to suggestions and tags in use`() {
        val l = list("Продукты")
        item(l, "Уголь", category = OTHER, tagIds = setOf("t-dacha"))
        item(l, "Хлеб", category = BAKERY)
        item(l, "Молоко", category = DAIRY, tagIds = setOf("t-dacha"))
        item(l, "Перчатки", BOUGHT, boughtAgo = 1.minutes, tagIds = setOf("t-dacha", "t-unknown"))
        item(l, "Сок", BOUGHT, boughtAgo = 2.minutes)
        item(l, "Старое", CLEARED, tagIds = setOf("t-old"))
        val all = ShoppingViews.detail(inputs(), l)!!
        assertEquals(listOf("t-dacha"), all.tagsInUse.map { it.id })
        val filtered = ShoppingViews.detail(inputs(), l, "t-dacha")!!
        assertEquals(listOf(DAIRY, OTHER), filtered.sections.map { it.category })
        assertEquals(listOf("Перчатки"), filtered.bought.map { it.data.name })
        assertEquals(2, filtered.list.needed)
        assertEquals(1, filtered.list.bought)
        assertEquals(listOf("Старое"), filtered.suggestions.map { it.data.name })
        assertEquals(listOf("t-dacha"), filtered.tagsInUse.map { it.id })
        // Unknown tag ids are dropped, archived ones kept.
        assertEquals(
            listOf("t-dacha"),
            filtered.bought
                .single()
                .tags
                .map { it.id },
        )
        assertEquals(
            listOf("t-old"),
            filtered.suggestions
                .single()
                .tags
                .map { it.id },
        )

        // With the filter only one department is left → ungrouped.
        val dairy = ShoppingViews.detail(inputs(), l, "t-dacha")!!.copy()
        assertEquals(2, dairy.sections.size)
        val onlyDacha = list("Дача")
        item(onlyDacha, "Уголь", category = OTHER, tagIds = setOf("t-dacha"))
        item(onlyDacha, "Хлеб", category = BAKERY)
        assertNull(
            ShoppingViews
                .detail(inputs(), onlyDacha, "t-dacha")!!
                .sections
                .single()
                .category,
        )
    }

    @Test
    fun `SHP-010 item views name authors and mine`() {
        val l = list("Продукты")
        item(l, "Хлеб", addedBy = "dima")
        item(l, "Молоко", addedBy = "olga")
        item(l, "Сыр", addedBy = "gone", boughtBy = "gone", status = BOUGHT, boughtAgo = 1.minutes)
        val d = ShoppingViews.detail(inputs(), l)!!
        val rows = d.sections.single().items
        assertEquals(listOf("Дмитрий", "Ольга"), rows.map { it.addedBy?.name })
        assertEquals(listOf(false, true), rows.map { it.mine })
        assertNull(d.bought.single().addedBy)
        assertNull(d.bought.single().boughtBy)
    }

    @Test
    fun `SHP-001 rights of the viewer`() {
        val l = list("Продукты")
        val edit = ShoppingViews.detail(inputs(), l)!!
        assertTrue(edit.canEdit)
        assertFalse(edit.canManage)
        val manage = ShoppingViews.detail(inputs("olga", now, Permission.SHOPPING_VIEW, Permission.SHOPPING_MANAGE_LISTS), l)!!
        assertFalse(manage.canEdit)
        assertTrue(manage.canManage)
        val nobody = ShoppingViews.detail(inputs(null), l)!!
        assertFalse(nobody.canEdit || nobody.canManage)
        assertNull(ShoppingViews.detail(inputs(), "missing"))
    }

    @Test
    fun `SHP-003 autocomplete needs two characters and prefers this list and whole-name prefixes`() {
        val l = list("Продукты")
        val other = list("Дача")
        val mineCleared = item(l, "Молоко 2,5%", CLEARED, DAIRY, count = 1, note = "Без лактозы")
        item(l, "Шоколад молочный", BOUGHT, count = 7, boughtAgo = 1.minutes)
        item(l, "Молотый кофе", NEEDED)
        item(other, "Молоток", NEEDED, OTHER, note = "Большой")
        item(other, "Мол. сыр", CLEARED, DAIRY, count = 9)
        item(other, "Молоко 2,5%", CLEARED, DAIRY, count = 20)
        item(other, "Хлеб", CLEARED, BAKERY)
        item(other, "молотый  кофе", CLEARED, count = 30)
        assertEquals(emptyList(), ShoppingViews.autocomplete(inputs(), l, "м"))
        assertEquals(emptyList(), ShoppingViews.autocomplete(inputs(), l, " %м "))
        val result = ShoppingViews.autocomplete(inputs(), l, "Мол")
        assertEquals(listOf("Молоко 2,5%", "Шоколад молочный", "Мол. сыр", "Молоток"), result.map { it.name })
        assertEquals(Suggestion("Молоко 2,5%", DAIRY, "Без лактозы", mineCleared), result[0])
        // Other lists: no row to bring back, no note.
        assertEquals(Suggestion("Молоток", OTHER, null, null), result[3])
        // A name to buy in this list is never offered, not even from another list.
        assertTrue(ShoppingViews.autocomplete(inputs(), l, "молоты").isEmpty())
        // ё and case do not matter.
        assertEquals(listOf("Хлеб"), ShoppingViews.autocomplete(inputs(), l, "ХЛЁ").map { it.name })
    }

    @Test
    fun `SHP-003 autocomplete is distinct by name and limited`() {
        val l = list("Продукты")
        val a = list("Дача")
        val b = list("Аптека")
        item(a, "Сыр", CLEARED, count = 1)
        item(b, "сыр", CLEARED, count = 5)
        repeat(6) { item(a, "Сырок $it", CLEARED) }
        val result = ShoppingViews.autocomplete(inputs(), l, "сыр")
        assertEquals(ShoppingViews.AUTOCOMPLETE_MAX, result.size)
        assertEquals("сыр", result[0].name)
        assertEquals(1, result.count { it.name.lowercase() == "сыр" })
    }

    @Test
    fun `SHP-013 active list is the one with the latest activity`() {
        assertNull(ShoppingViews.active(inputs()))
        val a = list("Аптека")
        val b = list("Продукты")
        val c = list("Пустой")
        item(a, "Пластырь", addedAgo = 30.minutes)
        item(b, "Хлеб", addedAgo = 60.minutes)
        item(b, "Молоко", BOUGHT, boughtAgo = 5.minutes)
        item(c, "Соль", CLEARED, boughtAgo = 1.minutes)
        val active = ShoppingViews.active(inputs())!!
        assertEquals(b, active.list.id)
        assertEquals(1, active.othersToBuy)
    }

    @Test
    fun `SHP-013 active list ties go to the newer list`() {
        val a = list("А")
        val b = list("Б")
        item(a, "Хлеб", addedAgo = 5.minutes)
        item(b, "Соль", addedAgo = 5.minutes)
        assertEquals(b, ShoppingViews.active(inputs())!!.list.id)
    }

    @Test
    fun `SHP-012 shopper window edges`() {
        fun bought(
            by: String,
            ago: Duration,
        ) = ShoppingItemData("l", "x", "olga", now - 1.minutes - ago, BOUGHT, boughtBy = by, boughtAt = now - ago, boughtCount = 1)
        assertEquals("dima", ShoppingViews.shopper(listOf(bought("dima", 15.minutes)), "olga", now))
        assertNull(ShoppingViews.shopper(listOf(bought("dima", 15.minutes + 1.seconds)), "olga", now))
        // A clock up to a minute ahead still counts; more does not.
        assertEquals("dima", ShoppingViews.shopper(listOf(bought("dima", (-1).minutes)), "olga", now))
        assertNull(ShoppingViews.shopper(listOf(bought("dima", (-61).seconds)), "olga", now))
        // Not me; latest wins; cleared ticks do not count.
        assertNull(ShoppingViews.shopper(listOf(bought("olga", 1.minutes)), "olga", now))
        assertEquals(
            "anya",
            ShoppingViews.shopper(listOf(bought("dima", 5.minutes), bought("anya", 2.minutes), bought("olga", 0.minutes)), "olga", now),
        )
        assertNull(ShoppingViews.shopper(listOf(bought("dima", 1.minutes).copy(status = CLEARED)), "olga", now))
    }

    @Test
    fun `SHP-012 shopper on the list view and unknown members dropped`() {
        val l = list("Продукты")
        item(l, "Хлеб", BOUGHT, boughtAgo = 2.minutes, boughtBy = "dima")
        assertEquals("Дмитрий", ShoppingViews.lists(inputs())[0].shopper?.name)
        assertNull(ShoppingViews.lists(inputs("dima"))[0].shopper)
        assertNull(ShoppingViews.lists(inputs(at = now + 16.minutes))[0].shopper)
        item(l, "Сыр", BOUGHT, boughtAgo = 1.minutes, boughtBy = "gone")
        assertNull(ShoppingViews.lists(inputs())[0].shopper)
    }

    @Test
    fun `TAG-003 tag page lists items to buy with the tag and tagged lists`() {
        val a = list("Продукты")
        val b = list("Дача", tagIds = setOf("t-dacha", "t-unknown"))
        item(b, "Перчатки", category = OTHER, tagIds = setOf("t-dacha"), addedAgo = 1.minutes)
        item(b, "Уголь", category = GROCERY, tagIds = setOf("t-dacha"), addedAgo = 2.minutes)
        item(a, "Сосиски", tagIds = setOf("t-dacha"))
        item(a, "Хлеб")
        item(a, "Куплено", BOUGHT, boughtAgo = 1.minutes, tagIds = setOf("t-dacha"))
        val view = ShoppingViews.forTag(inputs(), "t-dacha")
        assertEquals(
            listOf("Сосиски" to "Продукты", "Уголь" to "Дача", "Перчатки" to "Дача"),
            view.items.map {
                it.first.data.name to
                    it.second
            },
        )
        assertEquals(listOf(b), view.lists.map { it.id })
        assertEquals(
            listOf("t-dacha"),
            view.lists
                .single()
                .tags
                .map { it.id },
        )
    }

    @Test
    fun `FND-040 items of a missing list are ignored everywhere`() {
        val l = list("Продукты")
        item("deleted", "Хлеб", BOUGHT, boughtAgo = 1.minutes, boughtBy = "dima", tagIds = setOf("t-dacha"))
        item("deleted", "Сыр", CLEARED, count = 3)
        assertNull(ShoppingViews.active(inputs()))
        assertTrue(ShoppingViews.forTag(inputs(), "t-dacha").items.isEmpty())
        assertTrue(ShoppingViews.autocomplete(inputs(), l, "сыр").isEmpty())
        assertNull(ShoppingViews.detail(inputs(), "deleted"))
        assertEquals(listOf(l), ShoppingViews.lists(inputs()).map { it.id })
    }
}
