package app.lares.client.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.lares.contract.entities.PaletteColor

/** How one palette color (tags, personal colors) is drawn in the current theme. */
@Immutable
data class PaletteTones(
    /** Avatars, dots, map pins; white text on it in both themes. */
    val base: Color,
    val container: Color,
    val onContainer: Color,
)

private data class PaletteEntry(
    val base: Long,
    val lightContainer: Long,
    val darkText: Long,
    val darkContainer: Long,
)

// visual-style.md § Palette.
private val entries: Map<PaletteColor, PaletteEntry> =
    mapOf(
        PaletteColor.RED to PaletteEntry(0xFFC14B41, 0xFFFFE2DE, 0xFFFF9F94, 0xFF67332D),
        PaletteColor.ORANGE to PaletteEntry(0xFFB75600, 0xFFFFE3D4, 0xFFFFA268, 0xFF643618),
        PaletteColor.YELLOW to PaletteEntry(0xFF926C00, 0xFFFFE5B6, 0xFFEBAF00, 0xFF563E03),
        PaletteColor.GREEN to PaletteEntry(0xFF4B7E39, 0xFFCFF3BC, 0xFF90C778, 0xFF2D4922),
        PaletteColor.TEAL to PaletteEntry(0xFF008074, 0xFFB4F5E9, 0xFF67C9BA, 0xFF004B43),
        PaletteColor.BLUE to PaletteEntry(0xFF2775BD, 0xFFDCE9FF, 0xFF85BCFF, 0xFF244467),
        PaletteColor.PURPLE to PaletteEntry(0xFF8360BA, 0xFFF1E3FF, 0xFFCBA9FF, 0xFF4B3A65),
        PaletteColor.PINK to PaletteEntry(0xFFAE5385, 0xFFFFE0EC, 0xFFFF98CE, 0xFF61334C),
        PaletteColor.BROWN to PaletteEntry(0xFF92694D, 0xFFFFE3D1, 0xFFDFAF8E, 0xFF5C3B21),
        PaletteColor.GRAY to PaletteEntry(0xFF6F7277, 0xFFE6E8EE, 0xFFB6B9BE, 0xFF3F4247),
    )

fun PaletteColor.tones(dark: Boolean): PaletteTones {
    val entry = entries.getValue(this)
    return if (dark) {
        PaletteTones(Color(entry.base), Color(entry.darkContainer), Color(entry.darkText))
    } else {
        PaletteTones(Color(entry.base), Color(entry.lightContainer), Color(entry.base))
    }
}

/** Tones of this palette color in the current theme. */
@Composable
fun PaletteColor.tones(): PaletteTones = tones(LaresTheme.colors.isDark)
