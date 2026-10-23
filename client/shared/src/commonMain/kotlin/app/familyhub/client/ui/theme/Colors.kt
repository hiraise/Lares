package app.familyhub.client.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Color tokens of docs/design/visual-style.md (ADR-0016). Roles the table does not list are derived from the nearest
// listed one, never left at Material defaults.

internal val LightColors: ColorScheme =
    lightColorScheme(
        primary = Color(0xFF2F5BEA),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE4EBFF),
        onPrimaryContainer = Color(0xFF1B45C4),
        inversePrimary = Color(0xFF8FB4FF),
        secondary = Color(0xFF0D6F93),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDDF3FA),
        onSecondaryContainer = Color(0xFF0D6F93),
        tertiary = Color(0xFF0D6F93),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFDDF3FA),
        onTertiaryContainer = Color(0xFF0D6F93),
        background = Color(0xFFF3F4F7),
        onBackground = Color(0xFF15171C),
        surface = Color(0xFFF3F4F7),
        onSurface = Color(0xFF15171C),
        surfaceVariant = Color(0xFFE7E9EE),
        onSurfaceVariant = Color(0xFF5B606B),
        surfaceTint = Color(0xFF2F5BEA),
        inverseSurface = Color(0xFF15171C),
        inverseOnSurface = Color(0xFFFFFFFF),
        error = Color(0xFFC8372B),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFDE7E4),
        onErrorContainer = Color(0xFF8C1D14),
        outline = Color(0xFF8B909C),
        outlineVariant = Color(0xFFD5D9E2),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFE0E3EA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF8F9FB),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFE7E9EE),
        surfaceContainerHighest = Color(0xFFE0E3EA),
    )

internal val DarkColors: ColorScheme =
    darkColorScheme(
        primary = Color(0xFF8FB4FF),
        onPrimary = Color(0xFF0B1D5C),
        primaryContainer = Color(0xFF24345F),
        onPrimaryContainer = Color(0xFFC9D8FF),
        inversePrimary = Color(0xFF2F5BEA),
        secondary = Color(0xFF9FE0F2),
        onSecondary = Color(0xFF003545),
        secondaryContainer = Color(0xFF123A4A),
        onSecondaryContainer = Color(0xFF9FE0F2),
        tertiary = Color(0xFF9FE0F2),
        onTertiary = Color(0xFF003545),
        tertiaryContainer = Color(0xFF123A4A),
        onTertiaryContainer = Color(0xFF9FE0F2),
        background = Color(0xFF0E0F13),
        onBackground = Color(0xFFE8E9EE),
        surface = Color(0xFF0E0F13),
        onSurface = Color(0xFFE8E9EE),
        surfaceVariant = Color(0xFF24262D),
        onSurfaceVariant = Color(0xFF9A9FAB),
        surfaceTint = Color(0xFF8FB4FF),
        inverseSurface = Color(0xFFE8E9EE),
        inverseOnSurface = Color(0xFF15171C),
        error = Color(0xFFFF8A7E),
        onError = Color(0xFF4A0D04),
        errorContainer = Color(0xFF4A1F1A),
        onErrorContainer = Color(0xFFFFB4AB),
        outline = Color(0xFF6E7380),
        outlineVariant = Color(0xFF353841),
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFF2A2D35),
        surfaceDim = Color(0xFF0E0F13),
        surfaceContainerLowest = Color(0xFF1A1C22),
        surfaceContainerLow = Color(0xFF15171C),
        surfaceContainer = Color(0xFF15171C),
        surfaceContainerHigh = Color(0xFF24262D),
        surfaceContainerHighest = Color(0xFF2A2D35),
    )

/** Roles that Material 3 does not have (visual-style.md § Color tokens, "extra"). */
@Immutable
data class FamilyHubColors(
    val divider: Color,
    val warning: Color,
    val warningContainer: Color,
    val success: Color,
    val successContainer: Color,
    /** Lake gradient ends: only for the hero card, the main FAB, the app icon and the active shopping list progress. */
    val gradientStart: Color,
    val gradientEnd: Color,
    val isDark: Boolean,
)

internal val LightExtraColors =
    FamilyHubColors(
        divider = Color(0xFFECEEF3),
        warning = Color(0xFF8A5300),
        warningContainer = Color(0xFFFFF0D6),
        success = Color(0xFF1F7A4A),
        successContainer = Color(0xFFDCF3E5),
        gradientStart = Color(0xFF2F5BEA),
        gradientEnd = Color(0xFF0A7BAD),
        isDark = false,
    )

internal val DarkExtraColors =
    FamilyHubColors(
        divider = Color(0xFF26282F),
        warning = Color(0xFFF2B45A),
        warningContainer = Color(0xFF3D2A0A),
        success = Color(0xFF7AD3A0),
        successContainer = Color(0xFF173826),
        gradientStart = Color(0xFF2A4FCF),
        gradientEnd = Color(0xFF0F7FAE),
        isDark = true,
    )

internal val LocalFamilyHubColors = staticCompositionLocalOf { LightExtraColors }
