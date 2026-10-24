package app.lares.contract.shopping

import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ProductCategory.BABY
import app.lares.contract.entities.ProductCategory.BAKERY
import app.lares.contract.entities.ProductCategory.DAIRY
import app.lares.contract.entities.ProductCategory.DRINKS
import app.lares.contract.entities.ProductCategory.FROZEN
import app.lares.contract.entities.ProductCategory.GROCERY
import app.lares.contract.entities.ProductCategory.HOUSEHOLD
import app.lares.contract.entities.ProductCategory.MEAT_FISH
import app.lares.contract.entities.ProductCategory.OTHER
import app.lares.contract.entities.ProductCategory.PERSONAL_CARE
import app.lares.contract.entities.ProductCategory.PETS
import app.lares.contract.entities.ProductCategory.PHARMACY
import app.lares.contract.entities.ProductCategory.PRODUCE
import app.lares.contract.entities.ProductCategory.SWEETS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShoppingCategoriesTest {
    /** The tables of architecture/shopping.md § Departments; they may grow, never shrink. */
    private val table: Map<ProductCategory, List<String>> =
        mapOf(
            PRODUCE to
                listOf(
                    "Яблоки",
                    "Бананы",
                    "Картошка",
                    "Помидоры черри",
                    "Огурцы",
                    "Лук репчатый",
                    "Зелёный лук",
                    "Морковь",
                    "Укроп",
                    "Лимон",
                    "Apples",
                    "Bananas",
                    "Potatoes",
                    "Tomatoes",
                    "Onions",
                ),
            BAKERY to listOf("Хлеб", "Батон нарезной", "Лаваш", "Круассаны", "Bread", "Bagels"),
            DAIRY to
                listOf(
                    "Молоко 2,5%",
                    "Молоко без лактозы",
                    "Кефир",
                    "Сметана 20%",
                    "Творог",
                    "Сыр российский",
                    "Сливочное масло",
                    "Масло",
                    "Яйца",
                    "Йогурт питьевой",
                    "Milk",
                    "Eggs",
                    "Butter",
                    "Cheddar cheese",
                    "Greek yogurt",
                ),
            MEAT_FISH to
                listOf(
                    "Курица",
                    "Куриное филе",
                    "Фарш говяжий",
                    "Колбаса докторская",
                    "Сосиски",
                    "Лосось",
                    "Креветки",
                    "Chicken breast",
                    "Ground beef",
                    "Salmon",
                ),
            GROCERY to
                listOf(
                    "Гречка",
                    "Рис",
                    "Макароны",
                    "Мука",
                    "Сахар",
                    "Соль",
                    "Подсолнечное масло",
                    "Оливковое масло",
                    "Томатная паста",
                    "Горошек консервированный",
                    "Овсяные хлопья",
                    "Майонез",
                    "Rice",
                    "Pasta",
                    "Flour",
                    "Olive oil",
                    "Peanut butter",
                    "Tomato paste",
                    "Canned beans",
                ),
            SWEETS to listOf("Печенье овсяное", "Шоколад", "Конфеты", "Чипсы", "Cookies", "Chocolate"),
            DRINKS to
                listOf(
                    "Вода",
                    "Минеральная вода",
                    "Апельсиновый сок",
                    "Зелёный чай",
                    "Кофе молотый",
                    "Пиво",
                    "Orange juice",
                    "Green tea",
                    "Coffee",
                ),
            FROZEN to listOf("Мороженое", "Пельмени", "Замороженные овощи", "Ice cream", "Frozen peas"),
            HOUSEHOLD to
                listOf(
                    "Туалетная бумага",
                    "Средство для мытья посуды",
                    "Стиральный порошок",
                    "Губки для посуды",
                    "Мешки для мусора",
                    "Батарейки AA",
                    "Toilet paper",
                    "Dish soap",
                    "Laundry detergent",
                ),
            PERSONAL_CARE to listOf("Зубная паста", "Шампунь", "Дезодорант", "Мыло", "Toothpaste", "Shampoo"),
            BABY to listOf("Подгузники", "Детское питание", "Diapers"),
            PETS to listOf("Корм для кошек", "Наполнитель для лотка", "Cat food"),
            PHARMACY to listOf("Парацетамол", "Пластырь", "Таблетки от головы", "Ibuprofen"),
            OTHER to listOf("Тетради", "Подарок маме", "Birthday card"),
        )

    @Test
    fun `SHP-006 every name of the design tables gets its department`() {
        val misses =
            table.flatMap { (expected, names) ->
                names.mapNotNull { name ->
                    val actual = ShoppingCategories.guess(name)
                    if (actual == expected) null else "$name: expected $expected, got $actual"
                }
            }
        assertEquals(emptyList(), misses, misses.joinToString("\n"))
    }

    @Test
    fun `SHP-006 longer stems resolve clashes, prepositions split head and tail`() {
        assertEquals(SWEETS, ShoppingCategories.guess("Печенье"))
        assertEquals(MEAT_FISH, ShoppingCategories.guess("Печень говяжья"))
        assertEquals(DRINKS, ShoppingCategories.guess("Лимонад"))
        assertEquals(GROCERY, ShoppingCategories.guess("Маслины"))
        assertEquals(PETS, ShoppingCategories.guess("Лакомство для собак"))
        assertEquals(PETS, ShoppingCategories.guess("для кошек"))
        assertEquals(FROZEN, ShoppingCategories.guess("Овощи замороженные"))
        assertEquals(GROCERY, ShoppingCategories.guess("Тунец консервированный"))
        assertEquals(OTHER, ShoppingCategories.guess(""))
        assertEquals(OTHER, ShoppingCategories.guess("!!!"))
    }

    @Test
    fun `dictionary entries are keys, unique and long enough`() {
        val problems = mutableListOf<String>()
        val owner = mutableMapOf<String, ProductCategory>()
        for ((category, stems) in CategoryDictionary.stems) {
            for (stem in stems) {
                if (stem != ShoppingNames.key(stem) || ' ' in stem || stem.any { !it.isLetter() }) problems += "stem '$stem' is not a key"
                if (stem.length < 3) problems += "stem '$stem' is shorter than 3"
                val previous = owner.put(stem, category)
                if (previous != null) problems += "stem '$stem' in $previous and $category"
            }
        }
        val phraseOwner = mutableMapOf<String, ProductCategory>()
        for ((category, phrases) in CategoryDictionary.phrases) {
            for (phrase in phrases) {
                val parts = phrase.split(' ')
                if (phrase != ShoppingNames.key(phrase) || parts.size < 2 || parts.any { it.length < 3 || it.any { c -> !c.isLetter() } }) {
                    problems += "phrase '$phrase' is not a key of stems"
                }
                phraseOwner.put(phrase, category)?.let { problems += "phrase '$phrase' in $it and $category" }
            }
        }
        for ((category, modifiers) in CategoryDictionary.modifiers) {
            for (modifier in modifiers) {
                if (modifier != ShoppingNames.key(modifier) || modifier.length < 3) problems += "modifier '$modifier' in $category"
            }
        }
        for (category in ProductCategory.entries - OTHER) {
            val stems = CategoryDictionary.stems[category].orEmpty()
            val cyrillic = stems.count { stem -> stem.all { it in 'а'..'я' } }
            val latin = stems.count { stem -> stem.all { it in 'a'..'z' } }
            if (cyrillic < 20) problems += "$category has $cyrillic Russian stems"
            if (latin < 15) problems += "$category has $latin English stems"
        }
        assertTrue(OTHER !in CategoryDictionary.stems, "no stems for OTHER")
        assertEquals(emptyList(), problems, problems.joinToString("\n"))
    }
}
