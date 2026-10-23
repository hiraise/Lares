package app.familyhub.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.familyhub.client.ui.theme.tones
import app.familyhub.contract.entities.PaletteColor

/**
 * A person's avatar: initials in white on the personal color (FND-034, visual-style.md § People).
 * [initials] is usually one letter; [initialsFor] builds it. With [ring], a 2 dp ring in the personal color
 * separated by a gap (the Today header).
 */
@Composable
fun Avatar(
    initials: String,
    color: PaletteColor,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    ring: Boolean = false,
) {
    val base = color.tones().base
    val ringModifier =
        if (ring) {
            Modifier
                .border(2.dp, base, CircleShape)
                .padding(4.dp)
        } else {
            Modifier
        }
    Box(modifier.then(ringModifier), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).background(base, CircleShape), contentAlignment = Alignment.Center) {
            Text(
                initials,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.4f).sp,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** First letter of the name; two letters when another name in [others] starts with the same letter. */
fun initialsFor(
    name: String,
    others: Collection<String> = emptyList(),
): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val first = words.firstOrNull()?.first()?.uppercaseChar() ?: return "?"
    val clash = others.count { it.trim().firstOrNull()?.uppercaseChar() == first } > 1
    if (!clash) return first.toString()
    val second = words.getOrNull(1)?.first() ?: words.first().getOrNull(1) ?: return first.toString()
    return "$first${second.uppercaseChar()}"
}
