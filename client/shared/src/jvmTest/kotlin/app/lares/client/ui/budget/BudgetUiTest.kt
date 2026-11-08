package app.lares.client.ui.budget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.data.budget.BUDGET_MANAGER
import app.lares.client.data.budget.BUDGET_VIEWER
import app.lares.client.data.budget.BudgetInputs
import app.lares.client.data.budget.BudgetViews
import app.lares.client.data.budget.DEC
import app.lares.client.data.budget.IncomeKey
import app.lares.client.data.budget.MEMBERS
import app.lares.client.data.budget.NOV
import app.lares.client.data.budget.Notebook
import app.lares.client.data.budget.plan
import app.lares.client.data.tasks.OLGA
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.Ids
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** The budget screens with the customer's notebook (spec § Scenarios), in Russian; today is 10 December 2026. */
@OptIn(ExperimentalTestApi::class)
class BudgetUiTest {
    @BeforeTest
    fun russian() = Locale.setDefault(Locale.forLanguageTag("ru"))

    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val today = LocalDate(2026, 12, 10)
    private val receipt150 =
        Ids.budgetReceiptRowId(Notebook.i5, DEC) to
            BudgetReceiptData(DEC, Notebook.i5, amount = 150_000, date = LocalDate(2026, 12, 4), updatedBy = OLGA)
    private val food20 = BudgetFundingData(DEC, Notebook.i5, Notebook.food, 20_000, OLGA, Instant.parse("2026-12-04T10:00:00Z"))

    private fun inputs(
        permissions: Array<Permission> = BUDGET_MANAGER,
        plans: List<BudgetPlanData> = listOf(plan(NOV)),
        receipts: Map<String, BudgetReceiptData> = emptyMap(),
        fundings: List<BudgetFundingData> = emptyList(),
    ) = BudgetInputs(
        plans,
        emptyList(),
        receipts,
        fundings,
        MEMBERS,
        TimeZone.of("Europe/Moscow"),
        Viewer(OLGA, "p-olga", EffectivePermissions(false, permissions.toSet())),
        today,
    )

    private fun screen(inputs: BudgetInputs) =
        BudgetUiState(
            loaded = true,
            month = DEC,
            current = DEC,
            today = today,
            view = BudgetViews.month(inputs, DEC),
            months = BudgetViews.months(inputs),
            canManage = inputs.viewer!!.permissions.has(Permission.BUDGET_MANAGE),
        )

    private fun income(inputs: BudgetInputs): IncomeUiState {
        val view = BudgetViews.income(inputs, DEC, IncomeKey.Planned(Notebook.i5))
        return IncomeUiState(
            loaded = true,
            view = view,
            today = today,
            canManage = inputs.viewer!!.permissions.has(Permission.BUDGET_MANAGE),
            amountText =
                view
                    ?.summary
                    ?.actual
                    ?.toString()
                    .orEmpty(),
            date = view?.summary?.actualDate,
        )
    }

    private fun amount(value: Long) = BudgetFormat.amount(value)

    @Test
    fun `BUD-020 the month shows the hero, four incomes and the used categories`() =
        runComposeUiTest {
            setContent { LaresTheme { BudgetScreen(screen(inputs()), BudgetActions()) } }
            onNodeWithTag("budget_hero_text").assert(hasText("Ожидается ${amount(346_000)}"))
            onNodeWithTag("budget_month_title").assert(hasText("Декабрь 2026"))
            onAllNodesWithText("Ожидается").assertCountEquals(4)
            onNodeWithText("5 декабря · ${amount(162_000)}").assertExists()
            onNodeWithText("20 декабря · ${amount(160_000)}").assertExists()
            listOf("Продукты", "Машина", "Коммуналка", "Ипотека", "Сейф", "Отпуск", "Подарки", "Накопления").forEach {
                onNode(hasText(it) and hasAnyAncestor(hasTestTag("budget_categories"))).assertExists()
            }
            onAllNodesWithText("Кружок").assertCountEquals(0)
        }

    @Test
    fun `BUD-011 recording 150 000 for the 5th shows the shortfall of 12 000`() =
        runComposeUiTest {
            val inputs = inputs(receipts = mapOf(receipt150))
            setContent { LaresTheme { IncomePage(income(inputs), IncomeActions()) } }
            onNodeWithTag("income_shortfall").assert(hasText("Недобор ${amount(12_000)}"))
            onNodeWithTag("income_free").assert(hasText("Не распределено ${amount(150_000)}"))
            onNodeWithTag("income_clear").assertExists()
        }

    @Test
    fun `BUD-011 the month shows the recorded income with its shortfall and free money in the hero`() =
        runComposeUiTest {
            setContent { LaresTheme { BudgetScreen(screen(inputs(receipts = mapOf(receipt150))), BudgetActions()) } }
            onNodeWithTag("budget_hero_text").assert(hasText("Свободно ${amount(150_000)}"))
            onNodeWithText("Пришло 150 000 · 4 дек.").assertExists()
            onAllNodesWithText("Недобор ${amount(12_000)}").assertCountEquals(2)
        }

    @Test
    fun `BUD-012 a ticked category moves to transferred and the income counts it`() =
        runComposeUiTest {
            val inputs = inputs(receipts = mapOf(receipt150), fundings = listOf(food20))
            var page by mutableStateOf(true)
            setContent {
                LaresTheme {
                    if (page) IncomePage(income(inputs), IncomeActions()) else BudgetScreen(screen(inputs), BudgetActions())
                }
            }
            onNode(hasText("Продукты") and hasAnyAncestor(hasTestTag("income_funded"))).assertExists()
            onNode(hasText("20 000 из 27 000") and hasAnyAncestor(hasTestTag("income_funded"))).assertExists()
            onNode(hasText("Машина") and hasAnyAncestor(hasTestTag("income_to_fund"))).assertExists()
            onAllNodesWithTag("funding_check", useUnmergedTree = true).assertCountEquals(4)
            onNodeWithTag("income_free").assert(hasText("Не распределено ${amount(130_000)}"))
            page = false
            waitForIdle()
            onNodeWithText("Переведено 1 из 4").assertExists()
            onNodeWithText("20 000 из 60 000").assertExists()
        }

    @Test
    fun `BUD-012 a member with only budget view sees the receipt as text and no checkboxes`() =
        runComposeUiTest {
            val inputs = inputs(BUDGET_VIEWER, receipts = mapOf(receipt150), fundings = listOf(food20))
            setContent { LaresTheme { IncomePage(income(inputs), IncomeActions()) } }
            onAllNodesWithTag("funding_check", useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithTag("income_amount").assertCountEquals(0)
            onNodeWithTag("income_receipt").assert(hasText("Пришло ${amount(150_000)}, 4 декабря"))
            onAllNodesWithText("Отметить все").assertCountEquals(0)
        }

    @Test
    fun `BUD-020 all months are listed newest first and a tap picks one`() =
        runComposeUiTest {
            val state = screen(inputs(plans = listOf(plan(YearMonth(2026, 10)))))
            var picked: YearMonth? = null
            setContent { LaresTheme { MonthsPage(state, {}, { picked = it }) } }
            val rows = onAllNodesWithTag("budget_month_row")
            rows.assertCountEquals(3)
            rows[0].assert(hasText("Декабрь 2026", substring = true))
            rows[1].assert(hasText("Ноябрь 2026", substring = true))
            rows[2].assert(hasText("Октябрь 2026", substring = true))
            rows[1].performClick()
            assertEquals(NOV, picked)
        }

    @Test
    fun `BUD-010 without a plan the budget shows the empty state`() =
        runComposeUiTest {
            setContent { LaresTheme { BudgetScreen(screen(inputs(plans = emptyList())), BudgetActions()) } }
            onNodeWithTag("budget_empty").assertExists()
            onNodeWithText("Составьте план распределения").assertExists()
        }
}
