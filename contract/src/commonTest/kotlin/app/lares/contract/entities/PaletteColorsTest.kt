package app.lares.contract.entities

import app.lares.contract.entities.PaletteColor.BLUE
import app.lares.contract.entities.PaletteColor.BROWN
import app.lares.contract.entities.PaletteColor.GRAY
import app.lares.contract.entities.PaletteColor.GREEN
import app.lares.contract.entities.PaletteColor.ORANGE
import app.lares.contract.entities.PaletteColor.PINK
import app.lares.contract.entities.PaletteColor.PURPLE
import app.lares.contract.entities.PaletteColor.RED
import app.lares.contract.entities.PaletteColor.TEAL
import app.lares.contract.entities.PaletteColor.YELLOW
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PaletteColorsTest {
    private val allButGray = listOf(RED, ORANGE, YELLOW, GREEN, TEAL, BLUE, PURPLE, PINK, BROWN)

    @Test
    fun firstPersonGetsTheFirstColor() {
        assertEquals(RED, PaletteColors.pickLeastUsed(emptyList()))
    }

    @Test
    fun picksTheLeastUsedWithTiesByPaletteOrder() {
        assertEquals(ORANGE, PaletteColors.pickLeastUsed(listOf(RED)))
        assertEquals(YELLOW, PaletteColors.pickLeastUsed(listOf(RED, ORANGE)))
        assertEquals(RED, PaletteColors.pickLeastUsed(listOf(ORANGE, YELLOW, GREEN, TEAL, BLUE, PURPLE, PINK, BROWN)))
    }

    @Test
    fun grayOnlyWhenEveryOtherColorIsUsedMore() {
        assertEquals(GRAY, PaletteColors.pickLeastUsed(allButGray))
        // Gray used once as well: back to the start of the palette.
        assertEquals(RED, PaletteColors.pickLeastUsed(allButGray + GRAY))
        assertEquals(ORANGE, PaletteColors.pickLeastUsed(allButGray + GRAY + RED))
    }

    @Test
    fun fallbackIsStableAndNeverGray() {
        val id = "01928c4f-0000-7000-8000-000000000000"
        assertEquals(PaletteColors.fallbackFor(id), PaletteColors.fallbackFor(id))
        assertEquals(PaletteColors.fallbackFor(id), PaletteColors.fallbackFor(id.uppercase()))
        val colors = (0 until 200).map { PaletteColors.fallbackFor("01928c4f-0000-7000-8000-${it.toString().padStart(12, '0')}") }
        assertNotEquals(true, GRAY in colors)
        assertEquals(allButGray.toSet(), colors.toSet())
    }

    @Test
    fun keysAreLowercaseNames() {
        assertEquals("blue", BLUE.key)
        assertEquals(PURPLE, PaletteColor.fromKey("purple"))
        assertEquals(null, PaletteColor.fromKey("BLUE"))
    }
}
