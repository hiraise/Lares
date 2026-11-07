package app.lares.client.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.lares.client.resources.Res
import app.lares.client.resources.ic_account_balance_wallet
import app.lares.client.resources.section_budget
import app.lares.client.ui.budget.BudgetLogic
import app.lares.client.ui.common.text
import app.lares.client.ui.components.ListGroup
import app.lares.client.ui.components.ListRow
import app.lares.client.ui.components.SectionHeader
import app.lares.client.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

/**
 * «Бюджет» on Today (BUD-021), after «Семья сейчас»: the next unrecorded income and how many categories are not
 * transferred yet, or «Всё распределено»; a tap opens the Budget screen.
 */
@Composable
fun TodayBudget(
    card: TodayBudgetUi,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, supporting) = BudgetLogic.todayLines(card.nextIncome, card.notFunded, card.today)
    Column(modifier.testTag("today_budget"), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(stringResource(Res.string.section_budget))
        ListGroup {
            ListRow(
                title.text(),
                Modifier.testTag("today_budget_row"),
                icon = Res.drawable.ic_account_balance_wallet,
                supporting = supporting?.text(),
                showChevron = true,
                onClick = onOpen,
            )
        }
    }
}
