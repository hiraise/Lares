package app.lares.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.lares.client.ui.theme.LaresTheme
import org.jetbrains.compose.resources.DrawableResource

private val FormButtonHeight = 52.dp

/** Filled primary pill button (52 dp in forms). While [loading], it shows a spinner and ignores taps. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: DrawableResource? = null,
) {
    Button(
        onClick = { if (!loading) onClick() },
        enabled = enabled,
        shape = CircleShape,
        modifier = modifier.fillMaxWidth().height(FormButtonHeight),
    ) { ButtonContent(text, loading, icon) }
}

/** Tonal pill button in `primaryContainer`. */
@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: DrawableResource? = null,
) {
    FilledTonalButton(
        onClick = { if (!loading) onClick() },
        enabled = enabled,
        shape = CircleShape,
        colors =
            ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        modifier = modifier.fillMaxWidth().height(FormButtonHeight),
    ) { ButtonContent(text, loading, icon) }
}

/** Text button in `onPrimaryContainer` (links). Destructive actions pass [color] = error. */
@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun ButtonContent(
    text: String,
    loading: Boolean,
    icon: DrawableResource?,
) {
    Box(contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.alpha(if (loading) 0f else 1f),
        ) {
            if (icon != null) AppIcon(icon, null)
            Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize))
        }
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = LocalContentColor.current)
    }
}

/** The main action of a screen: 60 dp, radius 20, lake gradient, white icon. */
@Composable
fun GradientFab(
    icon: DrawableResource,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.Transparent,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(6.dp, 6.dp, 6.dp, 8.dp),
        modifier = modifier.size(60.dp),
    ) {
        Box(
            Modifier.size(60.dp).background(LaresTheme.gradient, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) { AppIcon(icon, contentDescription) }
    }
}

/** Pill filter chip, 36 dp: selected = `inverseSurface`, unselected = `outlineVariant` border. */
@Composable
fun AppFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: DrawableResource? = null,
    trailingIcon: DrawableResource? = null,
) {
    val iconColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = leadingIcon?.let { { AppIcon(it, null, size = 18.dp, tint = iconColor) } },
        trailingIcon = trailingIcon?.let { { AppIcon(it, null, size = 18.dp, tint = iconColor) } },
        shape = CircleShape,
        colors =
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                selectedLabelColor = MaterialTheme.colorScheme.inverseOnSurface,
                labelColor = MaterialTheme.colorScheme.onSurface,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                selectedBorderColor = Color.Transparent,
            ),
        modifier = modifier.height(36.dp),
    )
}

/** Padding for screen content below a header. */
val ScreenContentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
