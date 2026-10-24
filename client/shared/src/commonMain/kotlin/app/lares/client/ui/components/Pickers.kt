package app.lares.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.ic_check
import app.lares.client.resources.ic_cloud_off
import app.lares.client.ui.common.label
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tones
import app.lares.contract.entities.PaletteColor
import org.jetbrains.compose.resources.DrawableResource

/** Extended FAB of list screens: 56 dp, radius 20, lake gradient, white icon and label (visual-style.md). */
@Composable
fun ExtendedGradientFab(
    icon: DrawableResource,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(20.dp)
    // Drawn by hand: a Material FAB with a transparent container lets its shadow show through the gradient.
    Row(
        modifier
            .height(56.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(LaresTheme.gradient)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 18.dp, end = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, null, tint = Color.White)
        Text(text, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

/** The 10 palette swatches (FND-034, tags); the selected one carries a check. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(
    selected: PaletteColor?,
    onSelect: (PaletteColor) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        PaletteColor.entries.forEach { color ->
            val isSelected = color == selected
            val name = color.label()
            Box(
                Modifier
                    .size(48.dp)
                    .selectable(isSelected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(color) })
                    .semantics { contentDescription = name }
                    .testTag("swatch_${color.key}"),
                contentAlignment = Alignment.Center,
            ) {
                val ring = if (isSelected) Modifier.border(2.dp, color.tones().base, CircleShape).padding(3.dp) else Modifier
                Box(
                    ring.size(if (isSelected) 34.dp else 36.dp).background(color.tones().base, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (isSelected) AppIcon(Res.drawable.ic_check, null, size = 20.dp, tint = Color.White) }
            }
        }
    }
}

/** Small label chip (8 dp radius, `labelMedium` bold), e.g. a group of a member. */
@Composable
fun LabelChip(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    icon: DrawableResource? = null,
) {
    Row(
        modifier.background(container, MaterialTheme.shapes.extraSmall).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) AppIcon(icon, null, size = 14.dp, tint = content)
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = content,
        )
    }
}

/** A row with a checkbox; the whole row toggles. */
@Composable
fun CheckRow(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = Spacing.l, vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.6f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** `warningContainer` banner, e.g. "Needs internet connection" over admin actions while offline. */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: DrawableResource = Res.drawable.ic_cloud_off,
) {
    val colors = LaresTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.warningContainer, MaterialTheme.shapes.medium)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(icon, null, size = 20.dp, tint = colors.warning)
        Text(text, style = MaterialTheme.typography.bodySmall, color = colors.warning)
    }
}

/** Segmented control (visual-style.md): track `surfaceContainerHigh`, selected segment `surfaceBright` with a hairline shadow. */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh, CircleShape)
            .padding(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val segment =
                if (isSelected) {
                    Modifier.shadow(1.dp, CircleShape).background(colors.surfaceBright, CircleShape)
                } else {
                    Modifier
                }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .then(segment)
                    .clip(CircleShape)
                    .selectable(isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = Spacing.s, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
