package app.familyhub.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/** Spacing tokens (visual-style.md § Shape, spacing, elevation). */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Side padding of every screen. */
    val screen = 16.dp

    /** Horizontal padding of the large title block. */
    val titleBlock = 20.dp
}

internal val FamilyHubShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/** The hero card radius (one per screen at most). */
val HeroShape = RoundedCornerShape(24.dp)

/** Fixed brand theme; dynamic color is off (ADR-0016). */
@Composable
fun FamilyHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extra = if (darkTheme) DarkExtraColors else LightExtraColors
    val font = manrope()
    val typography = remember(font) { familyHubTypography(font) }
    CompositionLocalProvider(LocalFamilyHubColors provides extra) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = typography,
            shapes = FamilyHubShapes,
            content = content,
        )
    }
}

object FamilyHubTheme {
    val colors: FamilyHubColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFamilyHubColors.current

    /** The lake gradient, 135°. */
    val gradient: Brush
        @Composable
        @ReadOnlyComposable
        get() {
            val colors = LocalFamilyHubColors.current
            return Brush.linearGradient(listOf(colors.gradientStart, colors.gradientEnd), start = Offset.Zero, end = Offset.Infinite)
        }
}
