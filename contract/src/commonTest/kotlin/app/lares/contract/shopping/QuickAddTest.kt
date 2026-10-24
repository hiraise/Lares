package app.lares.contract.shopping

import kotlin.test.Test
import kotlin.test.assertEquals

class QuickAddTest {
    private fun parse(text: String): List<Pair<String, String?>> = QuickAdd.parse(text).map { it.name to it.quantity }

    @Test
    fun `SHP-003 the design table`() {
        val table: List<Pair<String, List<Pair<String, String?>>>> =
            listOf(
                "Молоко 2 л, хлеб, яблоки 1 кг" to listOf("Молоко" to "2 л", "Хлеб" to null, "Яблоки" to "1 кг"),
                "Молоко 2,5%" to listOf("Молоко 2,5%" to null),
                "Яйца 10" to listOf("Яйца" to "10"),
                "Хлеб x2" to listOf("Хлеб" to "2"),
                "Хлеб х2" to listOf("Хлеб" to "2"),
                "Хлеб 2×" to listOf("Хлеб" to "2"),
                "Батарейки AA 4 шт." to listOf("Батарейки AA" to "4 шт"),
                "Сыр 0,5КГ" to listOf("Сыр" to "0,5 кг"),
                "Cheese 1.5 lb" to listOf("Cheese" to "1.5 lb"),
                "молоко,хлеб" to listOf("Молоко" to null, "Хлеб" to null),
                "Вода 5 л; сок" to listOf("Вода" to "5 л", "Сок" to null),
                "2 кг картошки" to listOf("2 кг картошки" to null),
                "1984" to listOf("1984" to null),
                "Кола 0,5" to listOf("Кола" to "0,5"),
                "Молоко\n\n  хлеб  " to listOf("Молоко" to null, "Хлеб" to null),
                "   " to emptyList(),
            )
        val misses =
            table
                .filter { (input, expected) ->
                    parse(input) != expected
                }.map { (input, expected) -> "'$input': expected $expected, got ${parse(input)}" }
        assertEquals(emptyList(), misses)
    }

    @Test
    fun `SHP-003 units in any case, with a dot, joined or separate, x forms`() {
        assertEquals(listOf("Сахар" to "1 кг"), parse("Сахар 1 КГ"))
        assertEquals(listOf("Сахар" to "1 кг"), parse("сахар 1кг."))
        assertEquals(listOf("Салфетки" to "3 уп"), parse("салфетки 3 уп."))
        assertEquals(listOf("Хлеб" to "2"), parse("Хлеб X2"))
        assertEquals(listOf("Хлеб" to "2"), parse("Хлеб 2x"))
        assertEquals(listOf("Хлеб" to "2"), parse("Хлеб Х2"))
        assertEquals(listOf("Eggs" to "1 dozen"), parse("Eggs 1 Dozen"))
        assertEquals(listOf("Сок" to "1,5 л"), parse("Сок 1,5 л"))
    }

    @Test
    fun `SHP-003 a number followed by something else is part of the name`() {
        assertEquals(listOf("Батарейки AA" to null), parse("Батарейки AA"))
        assertEquals(listOf("Лампочка 60 Вт" to null), parse("лампочка 60 Вт"))
        assertEquals(listOf("5 5" to null), parse("5 5"))
        assertEquals(listOf("2 л" to null), parse("2 л"))
    }

    @Test
    fun `SHP-003 commas split except inside numbers, semicolons and line breaks split`() {
        assertEquals(listOf("Сок" to "2,5", "Хлеб" to null), parse("сок 2,5, хлеб"))
        assertEquals(listOf("A" to null, "B" to null, "C" to null, "D" to null), parse("a,b;c\r\nd"))
        assertEquals(listOf("Молоко" to "2", "Хлеб" to null), parse("Молоко 2 ,хлеб"))
    }

    @Test
    fun `SHP-003 limits`() {
        val many = (1..60).joinToString("\n") { "товар $it" }
        assertEquals(QuickAdd.MAX_ENTRIES, QuickAdd.parse(many).size)
        val long = "а".repeat(250)
        assertEquals(
            200,
            QuickAdd
                .parse(long)
                .single()
                .name.length,
        )
        val quantity = QuickAdd.parse("Сок 1" + "0".repeat(40)).single()
        assertEquals(30, quantity.quantity?.length)
        assertEquals("Сок", quantity.name)
    }

    @Test
    fun `SHP-003 entries without letters or digits are dropped`() {
        assertEquals(listOf("Хлеб" to null), parse("—, хлеб, !!!, ;;"))
        assertEquals(emptyList(), parse("—"))
    }
}
