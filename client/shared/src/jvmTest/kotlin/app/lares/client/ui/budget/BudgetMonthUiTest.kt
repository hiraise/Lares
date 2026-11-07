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
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.data.budget.BUDGET_MANAGER
import app.lares.client.data.budget.BudgetInputs
import app.lares.client.data.budget.BudgetViews
import app.lares.client.data.budget.DEC
import app.lares.client.data.budget.IncomeKey
import app.lares.client.data.budget.MEMBERS
import app.lares.client.data.budget.NOV
import app.lares.client.data.budget.Notebook
import app.lares.client.data.budget.plan
import app.lares.client.data.tasks.OLGA
import app.lares.client.ui.budget.plan.PlanActions
import app.lares.client.ui.budget.plan.PlanLogic
import app.lares.client.ui.budget.plan.PlanScreen
import app.lares.client.ui.budget.plan.PlanUiState
import app.lares.client.ui.main.TodayBudget
import app.lares.client.ui.main.TodayBudgetUi
import app.lares.client.ui.main.TodayViewModel
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.Ids
import app.lares.contract.budget.BudgetMath
import app.lares.contract.entities.BudgetCell
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Month-only changes, extra incomes and the Today card (plan M5-06), in Russian; today is 10 December 2026. */
@OptIn(ExperimentalTestApi::class)
class BudgetMonthUiTest {
    @BeforeTest
    fun russian() = Locale.setDefault(Locale.forLanguageTag("ru"))

    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private val today = LocalDate(2026, 12, 10)
    private val bonus = "0192f3a4-0000-7000-8000-00000000b001"

    /** Scenario 4: Gifts 30 000 instead of 10 000, only in December. */
    private val gifts30 =
        Notebook.table.copy(
            categories = Notebook.table.categories.map { if (it.id == Notebook.gifts) it.copy(amount = 30_000) else it },
            cells =
                Notebook.table.cells.map {
                    if (it.categoryId == Notebook.gifts) BudgetCell(Notebook.gifts, Notebook.i20, 30_000) else it
                },
        )

    private fun receipt(
        incomeId: String,
        amount: Long,
        day: Int,
    ) = Ids.budgetReceiptRowId(incomeId, DEC) to
        BudgetReceiptData(DEC, incomeId, amount = amount, date = LocalDate(2026, 12, day), updatedBy = OLGA)

    private val extra =
        bonus to BudgetReceiptData(DEC, null, label = "Премия", amount = 20_000, date = LocalDate(2026, 12, 8), updatedBy = OLGA)

    private fun inputs(
        monthTable: Boolean = false,
        receipts: Map<String, BudgetReceiptData> = emptyMap(),
        fundings: List<BudgetFundingData> = emptyList(),
        withPlan: Boolean = true,
    ) = BudgetInputs(
        if (withPlan) listOf(plan(NOV)) else emptyList(),
        if (monthTable) listOf(BudgetMonthData(DEC, gifts30, OLGA)) else emptyList(),
        receipts,
        fundings,
        MEMBERS,
        TimeZone.of("Europe/Moscow"),
        Viewer(OLGA, "p-olga", EffectivePermissions(false, BUDGET_MANAGER.toSet())),
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
            canManage = true,
        )

    private fun amount(value: Long) = BudgetFormat.amount(value)

    @Test
    fun `BUD-013 scenario 4 marks Gifts and the month under the hero, the line and the menu open the editor and the reset`() =
        runComposeUiTest {
            var edit = 0
            var reset = 0
            setContent {
                LaresTheme {
                    BudgetScreen(
                        screen(inputs(monthTable = true)),
                        BudgetActions(onEditMonth = { edit++ }, onReset = { reset++ }),
                    )
                }
            }
            onNode(hasTestTag("budget_changed") and hasAnyAncestor(hasTestTag("budget_categories")), useUnmergedTree = true)
                .assertExists()
            onAllNodesWithTag("budget_changed", useUnmergedTree = true).assertCountEquals(2)
            // «Подарки» 30 000 this month, like «Отпуск»
            onAllNodes(hasText("0 из ${BudgetFormat.digits(30_000)}") and hasAnyAncestor(hasTestTag("budget_categories")))
                .assertCountEquals(2)
            onNodeWithText("В этом месяце план изменён").performClick()
            assertEquals(1, edit)
            onNodeWithTag("budget_menu").performClick()
            onNodeWithText("Изменить этот месяц").assertExists()
            onNodeWithText("Вернуть как в плане").performClick()
            assertEquals(1, reset)
        }

    @Test
    fun `BUD-013 a month as in the plan has no mark and no reset`() =
        runComposeUiTest {
            setContent { LaresTheme { BudgetScreen(screen(inputs()), BudgetActions()) } }
            onAllNodesWithTag("budget_changed", useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithText("В этом месяце план изменён").assertCountEquals(0)
            onNodeWithTag("budget_menu").performClick()
            onNodeWithText("Изменить этот месяц").assertExists()
            onAllNodesWithText("Вернуть как в плане").assertCountEquals(0)
        }

    @Test
    fun `BUD-013 month mode is titled with the month and marks the changed cell in the table`() =
        runComposeUiTest {
            val state =
                PlanUiState(
                    loaded = true,
                    editable = true,
                    month = DEC,
                    table = gifts30,
                    check = BudgetMath.check(gifts30),
                    differences = BudgetMath.differences(gifts30, Notebook.table),
                    current = DEC,
                )
            setContent { LaresTheme { PlanScreen(state, PlanActions()) } }
            onNodeWithText("Только декабрь").assertExists()
            onAllNodesWithTag("plan_history").assertCountEquals(0)
            onNode(hasTestTag("budget_changed") and hasAnyAncestor(hasTestTag("plan_categories")), useUnmergedTree = true)
                .assertExists()
            assertTrue(PlanLogic.categoryChanged(Notebook.gifts, state.differences))
        }

    @Test
    fun `BUD-011 an extra income comes after the planned ones and adds to free money`() =
        runComposeUiTest {
            var opened: String? = null
            var add = 0
            val inputs = inputs(receipts = mapOf(receipt(Notebook.i5, 150_000, 4), extra))
            setContent { LaresTheme { BudgetScreen(screen(inputs), BudgetActions(onExtra = { opened = it }, onAddExtra = { add++ })) } }
            onNodeWithTag("budget_hero_text").assert(hasText("Свободно ${amount(170_000)}"))
            onNode(hasText("Премия · ${amount(20_000)}") and hasAnyAncestor(hasTestTag("budget_incomes"))).performClick()
            assertEquals(bonus, opened)
            onNodeWithTag("budget_add_extra").performScrollTo().performClick()
            assertEquals(1, add)
        }

    @Test
    fun `BUD-011 the extra income page has no categories and deletes after asking`() =
        runComposeUiTest {
            var deleted = 0
            val view = BudgetViews.income(inputs(receipts = mapOf(extra)), DEC, IncomeKey.Extra(bonus))
            val state =
                IncomeUiState(
                    loaded = true,
                    extra = true,
                    view = view,
                    today = today,
                    canManage = true,
                    labelText = "Премия",
                    amountText = "20000",
                    date = LocalDate(2026, 12, 8),
                )
            setContent { LaresTheme { IncomePage(state, IncomeActions(onDelete = { deleted++ })) } }
            onNodeWithText("Премия · ${amount(20_000)}").assertExists()
            onNodeWithTag("income_label").assertExists()
            onAllNodesWithText("Перевести").assertCountEquals(0)
            onAllNodesWithText("Отметить все").assertCountEquals(0)
            onAllNodesWithTag("income_clear").assertCountEquals(0)
            onNodeWithTag("income_delete").performClick()
            onNodeWithText("Удалить поступление?").assertExists()
            onNode(hasText("Удалить") and hasAnyAncestor(isDialog())).performClick()
            assertEquals(1, deleted)
        }

    private fun card(inputs: BudgetInputs) = TodayViewModel.budgetCard(inputs)!!

    @Test
    fun `BUD-021 the Today card shows the next income, what is not transferred, or that all is distributed`() =
        runComposeUiTest {
            val nothingRecorded = card(inputs())
            val fiveRecorded = card(inputs(receipts = mapOf(receipt(Notebook.i5, 150_000, 4))))
            val allIn =
                mapOf(
                    receipt(Notebook.i5, 162_000, 4),
                    receipt(Notebook.i10, 12_000, 10),
                    receipt(Notebook.i20, 160_000, 10),
                    receipt(Notebook.i25, 12_000, 10),
                )
            val ticks =
                Notebook.table.cells
                    .filter { it.categoryId != Notebook.old }
                    .map { BudgetFundingData(DEC, it.incomeId, it.categoryId, it.amount, OLGA, Instant.parse("2026-12-10T10:00:00Z")) }
            val done = card(inputs(receipts = allIn, fundings = ticks))
            var shown by mutableStateOf(nothingRecorded)
            var opened = 0
            setContent { LaresTheme { TodayBudget(shown, onOpen = { opened++ }) } }

            onNodeWithText("Бюджет").assertExists()
            onNodeWithText("10 декабря · ${amount(12_000)}").assertExists()
            onAllNodesWithText("Не переведено", substring = true).assertCountEquals(0)
            onNodeWithTag("today_budget_row").performClick()
            assertEquals(1, opened)

            shown = fiveRecorded
            waitForIdle()
            onNodeWithText("10 декабря · ${amount(12_000)}").assertExists()
            onNodeWithText("Не переведено: 4 категории").assertExists()

            shown = done
            waitForIdle()
            onNodeWithText("Всё распределено").assertExists()
        }

    @Test
    fun `BUD-021 no card without a plan`() {
        assertNull(TodayViewModel.budgetCard(inputs(withPlan = false)))
        assertEquals(TodayBudgetUi(null, 0, today).today, card(inputs()).today)
    }
}
