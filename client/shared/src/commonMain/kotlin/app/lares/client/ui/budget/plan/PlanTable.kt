package app.lares.client.ui.budget.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.lares.client.resources.Res
import app.lares.client.resources.bud_plan_category
import app.lares.client.resources.bud_plan_table_amount
import app.lares.client.resources.bud_plan_table_total
import app.lares.client.ui.budget.BudgetFormat
import app.lares.client.ui.budget.ChangedDot
import app.lares.client.ui.common.text
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.theme.LaresTheme
import app.lares.client.ui.theme.Spacing
import app.lares.client.ui.theme.tabular
import app.lares.contract.budget.TableCheck
import app.lares.contract.budget.TableDifferences
import app.lares.contract.entities.BudgetTable
import org.jetbrains.compose.resources.stringResource

private val NameWidth = 128.dp
private val CellWidth = 92.dp
private val HeaderHeight = 52.dp
private val RowHeight = 44.dp

/**
 * The read-only table like the notebook (BUD-003): used categories as rows with a sticky name column, «Сумма», the
 * incomes by day as columns, «Итого» per row and a totals row; empty cells for no amount; sums that do not add up in
 * the warning color. The numbers scroll sideways.
 */
@Composable
fun PlanTable(
    table: BudgetTable,
    check: TableCheck,
    differences: TableDifferences? = null,
) {
    val changedCells = differences?.changedCells.orEmpty()
    val categories = table.categories.filterNot { it.unused }
    val incomes = PlanLogic.incomesByDay(table)
    val cells = table.cells.associate { (it.categoryId to it.incomeId) to it.amount }
    val warning = LaresTheme.colors.warning
    val normal = MaterialTheme.colorScheme.onSurface
    val header = MaterialTheme.typography.labelMedium
    val body = MaterialTheme.typography.bodySmall.tabular()
    val strong = body.copy(fontWeight = FontWeight.SemiBold)
    ListGroup(Modifier.testTag("plan_table")) {
        Row {
            Column(Modifier.width(NameWidth).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                NameCell(stringResource(Res.string.bud_plan_category), HeaderHeight, header)
                categories.forEach {
                    NameCell(it.name, RowHeight, body, Modifier.testTag("plan_table_name"), PlanLogic.categoryChanged(it.id, differences))
                }
                NameCell(stringResource(Res.string.bud_plan_table_total), RowHeight, strong)
            }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Column {
                    HeaderCell(stringResource(Res.string.bud_plan_table_amount), null)
                    categories.forEach { NumberCell(BudgetFormat.digits(it.amount), body, normal) }
                    NumberCell(BudgetFormat.digits(check.categoriesTotal), strong, normal)
                }
                incomes.forEach { income ->
                    val bad = income.id in check.badColumns
                    Column {
                        HeaderCell(PlanLogic.incomeDay(income).text(), BudgetFormat.digits(income.amount))
                        categories.forEach { category ->
                            val amount = cells[category.id to income.id]
                            NumberCell(
                                amount?.let(BudgetFormat::digits).orEmpty(),
                                body,
                                normal,
                                changed = (category.id to income.id) in changedCells,
                            )
                        }
                        NumberCell(
                            BudgetFormat.digits(check.columns[income.id] ?: 0),
                            strong,
                            if (bad) warning else normal,
                            if (bad) Modifier.testTag("plan_table_bad") else Modifier,
                        )
                    }
                }
                Column {
                    HeaderCell(stringResource(Res.string.bud_plan_table_total), null)
                    categories.forEach { category ->
                        val bad = category.id in check.badRows
                        NumberCell(
                            BudgetFormat.digits(check.rows[category.id] ?: 0),
                            body,
                            if (bad) warning else normal,
                            if (bad) Modifier.testTag("plan_table_bad") else Modifier,
                        )
                    }
                    NumberCell(BudgetFormat.digits(check.rows.values.sum()), strong, normal)
                }
            }
        }
    }
}

@Composable
private fun NameCell(
    text: String,
    height: Dp,
    style: TextStyle,
    modifier: Modifier = Modifier,
    changed: Boolean = false,
) {
    Row(
        modifier.width(NameWidth).height(height).padding(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (changed) ChangedDot()
    }
}

@Composable
private fun HeaderCell(
    title: String,
    amount: String?,
) {
    Column(
        Modifier.width(CellWidth).height(HeaderHeight).padding(end = Spacing.l),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        if (amount != null) {
            Text(
                amount,
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun NumberCell(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    changed: Boolean = false,
) {
    Row(
        modifier.width(CellWidth).height(RowHeight).padding(end = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End),
    ) {
        if (changed) ChangedDot()
        Text(text, style = style, color = color, maxLines = 1, textAlign = TextAlign.End)
    }
}
