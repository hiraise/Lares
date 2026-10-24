package app.lares.client.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lares.client.resources.Res
import app.lares.client.resources.ic_shopping_cart
import app.lares.client.resources.today_shopping_open
import app.lares.client.resources.today_shopping_title
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.components.Avatar
import app.lares.client.ui.components.LinkButton
import app.lares.client.ui.components.initialsFor
import app.lares.client.ui.shopping.ShoppingLogic
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import org.jetbrains.compose.resources.stringResource

/** Text on the lake gradient: always 100 % white (lower opacities fall below 4.5:1 at the light end). */
private val OnGradient = Color.White

/** «Открыть» text on the white pill (mockup `Main.dc.html`). */
private val OpenText = Color(0xFF1B45C4)

/**
 * The active shopping list on Today (SHP-013, mockup `Main.dc.html`): the lake gradient hero card, then «Ещё N списка»
 * when other lists have items to buy.
 */
@Composable
fun TodayShoppingBlock(
    card: TodayShoppingUi,
    onOpen: () -> Unit,
    onOthers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.Start) {
        TodayShoppingCard(card, onOpen)
        if (card.othersToBuy > 0) {
            LinkButton(
                ShoppingLogic.others(card.othersToBuy).text(),
                onOthers,
                Modifier.padding(top = Spacing.xs).testTag("today_shopping_others"),
            )
        }
    }
}

@Composable
fun TodayShoppingCard(
    card: TodayShoppingUi,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val small = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LaresTheme.gradient, shape)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(18.dp)
            .testTag("today_shopping"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            AppIcon(Res.drawable.ic_shopping_cart, null, size = 20.dp, tint = OnGradient)
            Text(
                stringResource(Res.string.today_shopping_title),
                style = small,
                color = OnGradient,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                ShoppingLogic.countOf(card).text(),
                style = small.copy(fontWeight = FontWeight.Bold).tabular(),
                color = OnGradient,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(OnGradient.copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .testTag("today_shopping_count"),
            )
        }
        Text(
            card.name,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = OnGradient,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            ShoppingLogic.remaining(card).text(),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = OnGradient,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        GradientProgress(card.fraction)
        Row(
            Modifier.padding(top = Spacing.xs).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            val shopper = card.shopper
            if (shopper != null) {
                Box(Modifier.size(28.dp).background(OnGradient, CircleShape), contentAlignment = Alignment.Center) {
                    Avatar(initialsFor(shopper.name), shopper.color, size = 24.dp)
                }
                Text(
                    ShoppingLogic.shopperNow(shopper).text(),
                    style = small,
                    color = OnGradient,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("today_shopping_shopper"),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(OnGradient)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .padding(horizontal = Spacing.l)
                    .testTag("today_shopping_open"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Res.string.today_shopping_open),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    color = OpenText,
                    maxLines = 1,
                    modifier = Modifier.padding(vertical = Spacing.s),
                )
            }
        }
    }
}

/** 6 dp progress on the gradient: white fill on white 28 %. */
@Composable
private fun GradientProgress(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(OnGradient.copy(alpha = 0.28f)),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(OnGradient),
            )
        }
    }
}
