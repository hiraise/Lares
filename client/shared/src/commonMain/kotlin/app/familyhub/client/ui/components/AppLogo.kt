package app.familyhub.client.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.familyhub.client.ui.theme.FamilyHubTheme

/** The app icon (visual-style.md § App icon): lake gradient square, white house with two figures. Drawn on an 88 grid. */
@Composable
fun AppLogo(
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
) {
    val colors = FamilyHubTheme.colors
    Canvas(modifier.size(size).semantics { this.contentDescription = contentDescription }) {
        scale(this.size.width / 88f, pivot = Offset.Zero) {
            drawRoundRect(
                brush = Brush.linearGradient(listOf(colors.gradientStart, colors.gradientEnd), Offset.Zero, Offset(88f, 88f)),
                size = Size(88f, 88f),
                cornerRadius = CornerRadius(26f),
            )
            val house =
                Path().apply {
                    moveTo(24f, 42f)
                    lineTo(44f, 25f)
                    lineTo(64f, 42f)
                    lineTo(64f, 62f)
                    quadraticTo(64f, 66f, 60f, 66f)
                    lineTo(28f, 66f)
                    quadraticTo(24f, 66f, 24f, 62f)
                    close()
                }
            drawPath(house, Color.White, style = Stroke(width = 3.5f, join = StrokeJoin.Round))
            val figure = Stroke(width = 3f, cap = StrokeCap.Round)
            drawCircle(Color.White, radius = 4f, center = Offset(38f, 50f), style = figure)
            drawCircle(Color.White, radius = 3f, center = Offset(51f, 52f), style = figure)
            drawArc(Color.White, 180f, 180f, false, Offset(31f, 59f), Size(14f, 14f), style = figure)
            drawArc(Color.White, 180f, 180f, false, Offset(45.5f, 60.5f), Size(11f, 11f), style = figure)
        }
    }
}
