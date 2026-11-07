package app.lares.client.ui.budget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lares.client.data.budget.FundingRow
import app.lares.client.resources.Res
import app.lares.client.resources.bud_changed
import app.lares.client.resources.bud_funded_by
import app.lares.client.resources.bud_income_all_funded
import app.lares.client.resources.bud_income_progress
import app.lares.client.resources.ic_account_balance_wallet
import app.lares.client.resources.ic_check
import app.lares.client.ui.common.text
import app.lares.client.ui.components.AppIcon
import app.lares.client.ui.shopping.ShoppingCheckbox
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import app.lares.contract.budget.CategorySummary
import app.lares.contract.budget.IncomeSummary
import app.lares.contract.budget.MonthSummary
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.jetbrains.compose.resources.stringResource

/** Text on the lake gradient: always 100 % white (visual-style.md). */
private val OnGradient = Color.White

/** An amount in tabular figures: «1 234 ₽» ([currency]) or «1 234». */
@Composable
fun AmountText(
    value: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    currency: Boolean = true,
) {
    Text(
        if (currency) BudgetFormat.amount(value) else BudgetFormat.digits(value),
        modifier = modifier,
        style = style.tabular(),
        color = color,
        maxLines = 1,
    )
}

/**
 * The hero of the Budget screen (lake gradient, the one per screen): the month, the main amount in `displaySmall`
 * ([BudgetLogic.hero]), «Пришло … из … · переведено …» and the month's shortfall when free money took the hero.
 */
@Composable
fun BudgetHero(
    month: YearMonth,
    summary: MonthSummary,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    val small = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LaresTheme.gradient, shape)
            .padding(18.dp)
            .testTag("budget_hero"),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            AppIcon(Res.drawable.ic_account_balance_wallet, null, size = 20.dp, tint = OnGradient)
            Text(BudgetLogic.monthTitle(month).text(), style = small, color = OnGradient)
        }
        Text(
            BudgetLogic.hero(summary).text(),
            style = MaterialTheme.typography.displaySmall.tabular(),
            color = OnGradient,
            modifier = Modifier.testTag("budget_hero_text"),
        )
        Text(
            BudgetLogic.heroLine(summary).text(),
            style =
                MaterialTheme.typography.bodyMedium
                    .copy(fontSize = 14.sp, lineHeight = 20.sp)
                    .tabular(),
            color = OnGradient,
        )
        BudgetLogic.heroShortfall(summary)?.let {
            Text(it.text(), style = small.tabular(), color = OnGradient, modifier = Modifier.testTag("budget_hero_shortfall"))
        }
    }
}

/** 6 dp progress bar: `primary` on `divider`; past 100 % `error` on `errorContainer` (visual-style.md). */
@Composable
fun BudgetProgressBar(
    done: Long,
    total: Long,
    modifier: Modifier = Modifier,
) {
    val over = total > 0 && done > total
    val fraction = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(if (over) colors.errorContainer else LaresTheme.colors.divider),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(if (over) colors.error else colors.primary),
            )
        }
    }
}

/** The 8 dp «изменено» dot of a month difference (BUD-013). */
@Composable
fun ChangedDot(modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.bud_changed)
    Box(
        modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(LaresTheme.colors.warning)
            .semantics { contentDescription = description }
            .testTag("budget_changed"),
    )
}

/**
 * One income of the month: «5 декабря · 162 000 ₽», its state, «Недобор …», «Переведено 7 из 12» with a bar, a check
 * when every planned row is ticked. Extra incomes have no progress.
 */
@Composable
fun IncomeRow(
    income: IncomeSummary,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val complete = income.plannedCount > 0 && income.fundedCount == income.plannedCount
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = Spacing.l, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(BudgetLogic.incomeTitle(income, today).text(), style = MaterialTheme.typography.bodyMedium.tabular())
            val state = BudgetLogic.incomeState(income).text()
            val shortfall = BudgetLogic.incomeShortfall(income)?.text()
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(state, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shortfall != null) {
                    Text(shortfall, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.error)
                }
            }
            if (income.incomeId != null && income.plannedCount > 0) {
                Text(
                    if (complete) {
                        stringResource(Res.string.bud_income_all_funded)
                    } else {
                        stringResource(Res.string.bud_income_progress, income.fundedCount, income.plannedCount)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BudgetProgressBar(income.fundedCount.toLong(), income.plannedCount.toLong(), Modifier.padding(top = 4.dp))
            }
        }
        if (complete) AppIcon(Res.drawable.ic_check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** One category of the month: name (with the «изменено» dot), «45 000 из 60 000», progress bar. */
@Composable
fun CategoryRow(
    category: CategorySummary,
    changed: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.l, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                Text(
                    category.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (changed) ChangedDot()
            }
            Text(
                BudgetLogic.categoryAmount(category.funded, category.amount).text(),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BudgetProgressBar(category.funded, category.amount)
    }
}

/**
 * A category in an income's column: the shopping-style checkbox (a "done" mark), name, who ticked it, the amount
 * («20 000 из 27 000» when changed). The row toggles; the amount opens «Перевели». Read-only: no checkbox, no taps.
 */
@Composable
fun FundingRowItem(
    row: FundingRow,
    canManage: Boolean,
    onToggle: () -> Unit,
    onAmount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val checked = row.funding != null
    Row(
        modifier
            .fillMaxWidth()
            .then(if (canManage) Modifier.clickable(role = Role.Checkbox, onClick = onToggle) else Modifier)
            .heightIn(min = 56.dp)
            .padding(start = Spacing.l, end = Spacing.s, top = 10.dp, bottom = 10.dp)
            .testTag("funding_row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (canManage) {
            val state = if (checked) "checked" else "unchecked"
            ShoppingCheckbox(checked, Modifier.testTag("funding_check").semantics { stateDescription = state })
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (row.changed) ChangedDot()
            }
            if (checked && row.fundedBy != null) {
                Text(
                    stringResource(Res.string.bud_funded_by, row.fundedBy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val amount = BudgetLogic.fundingAmount(row).text()
        Text(
            amount,
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = if (canManage) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .then(if (canManage) Modifier.clickable(role = Role.Button, onClick = onAmount) else Modifier)
                    .padding(horizontal = Spacing.s, vertical = Spacing.s)
                    .testTag("funding_amount"),
        )
    }
}
