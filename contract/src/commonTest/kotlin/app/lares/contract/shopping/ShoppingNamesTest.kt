package app.lares.contract.shopping

import kotlin.test.Test
import kotlin.test.assertEquals

class ShoppingNamesTest {
    @Test
    fun `SHP-009 key ignores case, yo, punctuation and spacing`() {
        assertEquals("молоко 2 5", ShoppingNames.key("Молоко 2,5%"))
        assertEquals("зеленый лук", ShoppingNames.key("  Зелёный   ЛУК! "))
        assertEquals("зеленый лук", ShoppingNames.key("зеленый-лук"))
        assertEquals("ёж".let(ShoppingNames::key), "еж")
        assertEquals("батарейки aa 4", ShoppingNames.key("Батарейки «AA» (4)"))
        assertEquals("1984", ShoppingNames.key("1984"))
        assertEquals("", ShoppingNames.key("— !!! %"))
        assertEquals(ShoppingNames.key("Хлеб"), ShoppingNames.key("хлеб."))
    }

    @Test
    fun `normalize trims and collapses whitespace`() {
        assertEquals("Молоко 2,5%", ShoppingNames.normalize("  Молоко \t 2,5%\n"))
        assertEquals("", ShoppingNames.normalize("   "))
    }

    @Test
    fun `displayName capitalizes a lower-case first word only`() {
        assertEquals("Молоко", ShoppingNames.displayName("молоко"))
        assertEquals("Ёлка", ShoppingNames.displayName("ёлка"))
        assertEquals("iPhone чехол", ShoppingNames.displayName("iPhone чехол"))
        assertEquals("AA батарейки", ShoppingNames.displayName("AA батарейки"))
        assertEquals("2 кг картошки", ShoppingNames.displayName("2 кг картошки"))
        assertEquals("", ShoppingNames.displayName(""))
    }
}
