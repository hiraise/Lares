package app.familyhub.client.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.familyhub.client.resources.Res
import app.familyhub.client.resources.manrope_400
import app.familyhub.client.resources.manrope_500
import app.familyhub.client.resources.manrope_600
import app.familyhub.client.resources.manrope_700
import app.familyhub.client.resources.manrope_800
import org.jetbrains.compose.resources.Font

@Composable
internal fun manrope(): FontFamily =
    FontFamily(
        Font(Res.font.manrope_400, FontWeight.Normal),
        Font(Res.font.manrope_500, FontWeight.Medium),
        Font(Res.font.manrope_600, FontWeight.SemiBold),
        Font(Res.font.manrope_700, FontWeight.Bold),
        Font(Res.font.manrope_800, FontWeight.ExtraBold),
    )

/** The type scale of visual-style.md § Typography. */
internal fun familyHubTypography(font: FontFamily): Typography {
    fun style(
        size: Int,
        line: Int,
        weight: FontWeight,
        spacing: Double = 0.0,
    ) = TextStyle(
        fontFamily = font,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = spacing.em,
    )
    return Typography(
        displayLarge = style(34, 40, FontWeight.ExtraBold, -0.02),
        displayMedium = style(34, 40, FontWeight.ExtraBold, -0.02),
        displaySmall = style(34, 40, FontWeight.ExtraBold, -0.02),
        headlineLarge = style(32, 38, FontWeight.ExtraBold, -0.02),
        headlineMedium = style(26, 32, FontWeight.ExtraBold, -0.01),
        headlineSmall = style(22, 28, FontWeight.ExtraBold, -0.01),
        titleLarge = style(18, 24, FontWeight.Bold),
        titleMedium = style(16, 22, FontWeight.Bold),
        titleSmall = style(15, 22, FontWeight.Bold),
        bodyLarge = style(16, 24, FontWeight.Medium),
        bodyMedium = style(15, 22, FontWeight.SemiBold),
        bodySmall = style(13, 18, FontWeight.Medium),
        labelLarge = style(14, 20, FontWeight.Bold),
        labelMedium = style(12, 16, FontWeight.SemiBold),
        labelSmall = style(11, 14, FontWeight.Bold, 0.04),
    )
}

/** Tabular figures for amounts, times and counters. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
