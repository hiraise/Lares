package app.lares.client.ui.shopping

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lares.client.data.shopping.ShoppingItemView
import app.lares.client.data.shopping.ShoppingListView
import app.lares.client.resources.Res
import app.lares.client.resources.action_add
import app.lares.client.resources.ic_add
import app.lares.client.resources.ic_check
import app.lares.client.resources.ic_expand_less
import app.lares.client.resources.ic_expand_more
import app.lares.client.resources.ic_person
import app.lares.client.resources.ic_shopping_cart
import app.lares.client.resources.shopping_add_hint
import app.lares.client.resources.shopping_buy
import app.lares.client.resources.shopping_clear_bought
import app.lares.client.resources.shopping_unbuy
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.ListDivider
import app.lares.client.ui.tasks.DISABLED_ALPHA
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Divider inset to the item name: 16 dp padding + 26 dp checkbox + 14 dp gap. */
private val ITEM_TEXT_START = 56.dp

/** Shopping checkbox (visual-style.md): rounded square 26 dp, radius 8 — "buy", unlike the round task checkbox. */
@Composable
fun ShoppingCheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val box = modifier.size(26.dp).alpha(if (enabled) 1f else DISABLED_ALPHA)
    if (checked) {
        Box(box.background(colors.primary, shape), contentAlignment = Alignment.Center) {
            AppIcon(Res.drawable.ic_check, null, size = 18.dp, tint = colors.onPrimary)
        }
    } else {
        Box(box.border(1.5.dp, colors.onSurfaceVariant, shape))
    }
}

/**
 * An item row (plan M2-04): checkbox, name, meta line ([meta], then the adder with a person icon), quantity on the
 * right. A tap anywhere toggles it; a long press is for the item sheet (M2-05). Without [enabled] the checkbox fades,
 * but the row stays tappable so the tap can say why nothing happens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShoppingItemRow(
    item: ShoppingItemView,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    adder: String? = null,
    enabled: Boolean = true,
    onLongClick: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(if (checked) Res.string.shopping_unbuy else Res.string.shopping_buy, item.data.name)
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClickLabel = label, role = Role.Checkbox, onLongClick = onLongClick, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.l, vertical = Spacing.m)
            .testTag("shopping_item_${item.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ShoppingCheckbox(checked, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.data.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (checked) colors.onSurfaceVariant else colors.onSurface,
                textDecoration = if (checked) TextDecoration.LineThrough else null,
            )
            if (meta != null || adder != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    val style = MaterialTheme.typography.bodySmall
                    if (meta != null) {
                        Text(
                            meta,
                            style = style,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (adder != null) {
                        AppIcon(
                            Res.drawable.ic_person,
                            null,
                            size = 14.dp,
                            tint = colors.onSurfaceVariant,
                            modifier = if (meta != null) Modifier.padding(start = Spacing.xs) else Modifier,
                        )
                        Text(adder, style = style, color = colors.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
        val quantity = item.data.quantity
        if (quantity != null) {
            Text(
                quantity,
                style =
                    MaterialTheme.typography.bodyMedium
                        .copy(fontWeight = FontWeight.SemiBold)
                        .tabular(),
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** Divider between item rows, inset to the names. */
@Composable
fun ItemDivider() = ListDivider(ITEM_TEXT_START)

/** 6 dp progress bar: `primary` on `divider`. */
@Composable
fun ShoppingProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(LaresTheme.colors.divider),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * A list on the Shopping tab: name with «6 из 9», progress, «Осталось: …» / «Всё куплено» / «Пусто», who is
 * shopping.
 */
@Composable
fun ShoppingListCard(
    list: ShoppingListView,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceContainerLowest)
            .clickable(onClick = onClick)
            .padding(Spacing.l)
            .testTag("shopping_list_${list.id}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(
                list.data.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ShoppingLogic.countOf(list)?.let {
                Text(it.text(), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant)
            }
        }
        if (ShoppingLogic.total(list) > 0) ShoppingProgressBar(ShoppingLogic.fraction(list))
        val line = ShoppingLogic.cardLine(list)
        Text(
            line.text.text(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (line.done) LaresTheme.colors.success else colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ShoppingLogic.shopperNow(list)?.let { ShopperLine(it.text(), icon = true) }
    }
}

@Composable
private fun ShopperLine(
    text: String,
    icon: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon) AppIcon(Res.drawable.ic_shopping_cart, null, size = 16.dp, tint = MaterialTheme.colorScheme.primary)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** «Куплено 6 из 9» with the short "who is shopping" on the right, and the progress bar under it. */
@Composable
fun ShoppingProgress(
    list: ShoppingListView,
    modifier: Modifier = Modifier,
) {
    val progress = ShoppingLogic.progress(list) ?: return
    Column(modifier.fillMaxWidth().padding(horizontal = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Text(
                progress.text(),
                style =
                    MaterialTheme.typography.labelLarge
                        .copy(fontWeight = FontWeight.Bold)
                        .tabular(),
                modifier = Modifier.weight(1f).testTag("shopping_progress"),
            )
            ShoppingLogic.shopperShort(list)?.let { ShopperLine(it.text(), icon = false) }
        }
        ShoppingProgressBar(ShoppingLogic.fraction(list))
    }
}

/**
 * The quick-add field (SHP-003): rounded field with a round `add` button. Done or the button adds the text; the
 * field clears and keeps focus. A pasted line break adds at once.
 */
@Composable
fun QuickAddField(
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) onAdd(text)
        text = ""
    }
    val hint = stringResource(Res.string.shopping_add_hint)
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(colors.surfaceContainerHigh)
            .padding(start = Spacing.l, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            BasicTextField(
                value = text,
                onValueChange = {
                    if ('\n' in it) {
                        if (it.isNotBlank()) onAdd(it)
                        text = ""
                    } else {
                        text = it
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = hint }
                        .testTag("shopping_add_field"),
            )
        }
        IconButton(onClick = submit, modifier = Modifier.size(48.dp).testTag("shopping_add_button")) {
            Box(Modifier.size(40.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                AppIcon(Res.drawable.ic_add, stringResource(Res.string.action_add), tint = colors.onPrimary)
            }
        }
    }
}

/**
 * «Куплено · N» (SHP-004): a card with the count and a preview, collapsed by default. Expanded, it lists the bought
 * items (a tap returns one to the list) and «Убрать купленные».
 */
@Composable
fun BoughtSection(
    bought: List<ShoppingItemView>,
    expanded: Boolean,
    canEdit: Boolean,
    time: (Instant) -> String,
    onToggleExpanded: () -> Unit,
    onUntick: (ShoppingItemView) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceContainerLowest),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = Spacing.l, vertical = 14.dp)
                .testTag("shopping_bought_header"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Box(Modifier.size(32.dp).background(LaresTheme.colors.successContainer, CircleShape), contentAlignment = Alignment.Center) {
                AppIcon(Res.drawable.ic_check, null, size = 20.dp, tint = LaresTheme.colors.success)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    ShoppingLogic.boughtTitle(bought.size).text(),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    ShoppingLogic.boughtPreview(bought).text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppIcon(if (expanded) Res.drawable.ic_expand_less else Res.drawable.ic_expand_more, null, tint = colors.onSurfaceVariant)
        }
        if (expanded) {
            bought.forEach { item ->
                ItemDivider()
                ShoppingItemRow(
                    item,
                    checked = true,
                    onClick = { onUntick(item) },
                    meta = ShoppingLogic.boughtMeta(item, time)?.text(),
                    enabled = canEdit,
                )
            }
            if (canEdit) {
                ItemDivider()
                LinkButton(
                    stringResource(Res.string.shopping_clear_bought),
                    onClear,
                    Modifier.padding(start = Spacing.s, top = Spacing.xs, bottom = Spacing.xs).testTag("shopping_clear_bought"),
                )
            }
        }
    }
}
