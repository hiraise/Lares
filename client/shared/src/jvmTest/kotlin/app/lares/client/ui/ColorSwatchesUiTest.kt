package app.lares.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.ui.components.ColorSwatches
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.entities.PaletteColor
import app.lares.contract.entities.PaletteColors
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ColorSwatchesUiTest {
    @Test
    fun `ADR-0031 tags pick from 16 colors, persons from 10`() =
        runComposeUiTest {
            setContent { LaresTheme(darkTheme = false) { ColorSwatches(selected = null, onSelect = {}) } }
            assertEquals(10, PaletteColors.personColors.count { onAllNodesWithTag("swatch_${it.key}").fetchSemanticsNodes().isNotEmpty() })
            assertEquals(0, onAllNodesWithTag("swatch_indigo").fetchSemanticsNodes().size, "tag-only colors are not offered to persons")
        }

    @Test
    fun `ADR-0031 the tag editor offers the tag-only colors`() =
        runComposeUiTest {
            var picked: PaletteColor? = null
            setContent {
                LaresTheme(darkTheme = false) {
                    Column { ColorSwatches(selected = null, onSelect = { picked = it }, colors = PaletteColors.tagColors) }
                }
            }
            assertEquals(16, PaletteColors.tagColors.count { onAllNodesWithTag("swatch_${it.key}").fetchSemanticsNodes().isNotEmpty() })
            onNodeWithTag("swatch_indigo").performClick()
            assertEquals(PaletteColor.INDIGO, picked)
        }
}
