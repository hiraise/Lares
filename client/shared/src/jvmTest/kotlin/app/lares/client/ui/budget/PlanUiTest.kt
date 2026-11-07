package app.lares.client.ui.budget

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import app.lares.client.data.budget.BudgetInputs
import app.lares.client.data.budget.BudgetViews
import app.lares.client.data.budget.DEC
import app.lares.client.data.budget.JAN
import app.lares.client.data.budget.MEMBERS
import app.lares.client.data.budget.NOV
import app.lares.client.data.budget.Notebook
import app.lares.client.data.budget.plan
import app.lares.client.data.tasks.OLGA
import app.lares.client.ui.FamilyFixture
import app.lares.client.ui.budget.plan.PlanActions
import app.lares.client.ui.budget.plan.PlanArgs
import app.lares.client.ui.budget.plan.PlanHistoryPage
import app.lares.client.ui.budget.plan.PlanHistoryUiState
import app.lares.client.ui.budget.plan.PlanRoute
import app.lares.client.ui.budget.plan.PlanScreen
import app.lares.client.ui.budget.plan.PlanTab
import app.lares.client.ui.budget.plan.PlanUiState
import app.lares.client.ui.budget.plan.PlanViewModel
import app.lares.client.ui.theme.LaresTheme
import app.lares.contract.budget.BudgetMath
import app.lares.contract.entities.BudgetTable
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import org.koin.core.parameter.parametersOf
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The plan screens with the customer's notebook (spec scenarios 1 and 5), in Russian; today is 10 December 2026. */
@OptIn(ExperimentalTestApi::class)
class PlanUiTest {
    @BeforeTest
    fun russian() = Locale.setDefault(Locale.forLanguageTag("ru"))

    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    private fun digits(value: Long) = BudgetFormat.digits(value)

    private fun ComposeUiTest.field(tag: String) = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)))

    private fun state(
        table: BudgetTable = Notebook.table,
        editable: Boolean = true,
        tab: PlanTab = PlanTab.CATEGORIES,
        changed: Boolean = false,
    ) = PlanUiState(
        loaded = true,
        editable = editable,
        table = table,
        check = BudgetMath.check(table),
        changed = changed,
        tab = tab,
        current = DEC,
        saveMonths = listOf(JAN, DEC),
    )

    /** Food gets 25 000 instead of 27 000 on the 5th: its row and the 5th's column no longer add up. */
    private val foodShort =
        Notebook.table.copy(
            cells =
                Notebook.table.cells.map {
                    if (it.categoryId == Notebook.food &&
                        it.incomeId == Notebook.i5
                    ) {
                        it.copy(amount = 25_000)
                    } else {
                        it
                    }
                },
        )

    @Test
    fun `BUD-003 the summary shows income and categories of the notebook as balanced`() =
        runComposeUiTest {
            setContent { LaresTheme { PlanScreen(state(), PlanActions()) } }
            onNodeWithTag("plan_summary_text").assert(hasText("Доход ${digits(346_000)} · Категории ${digits(346_000)}"))
            onAllNodesWithTag("plan_mismatch").assertCountEquals(0)
            onAllNodesWithTag("plan_bad_cells").assertCountEquals(0)
            onAllNodesWithTag("plan_category_row").assertCountEquals(8)
            onNode(hasText("Не используются") and hasAnyAncestor(hasTestTag("plan_unused"))).assertExists()
            onAllNodesWithText("Кружок").assertCountEquals(0)
        }

    @Test
    fun `BUD-003 a row that does not add up shows the warning and saving stays allowed`() =
        runComposeUiTest {
            val raised =
                foodShort.copy(
                    categories =
                        foodShort.categories.map {
                            if (it.id ==
                                Notebook.car
                            ) {
                                it.copy(amount = 26_000)
                            } else {
                                it
                            }
                        },
                )
            var saved = false
            setContent { LaresTheme { PlanScreen(state(raised, changed = true), PlanActions(onSave = { saved = true })) } }
            onNodeWithTag("plan_mismatch").assert(hasText("не сходится на ${digits(6_000)}"))
            onNodeWithTag("plan_bad_cells").assertExists()
            onAllNodesWithTag("plan_row_warning", useUnmergedTree = true).assertCountEquals(2)
            onNodeWithText("Распределено ${digits(58_000)} из ${digits(60_000)}").assertExists()
            onNodeWithTag("plan_save").performClick()
            assertEquals(true, saved)
        }

    @Test
    fun `BUD-002 incomes are listed by day with their column check`() =
        runComposeUiTest {
            setContent { LaresTheme { PlanScreen(state(foodShort, tab = PlanTab.INCOMES), PlanActions()) } }
            val rows = onAllNodesWithTag("plan_income_row")
            rows.assertCountEquals(4)
            rows[0].assert(hasText("5-е число · ${BudgetFormat.amount(162_000)}"))
            rows[1].assert(hasText("10-е число · ${BudgetFormat.amount(12_000)}"))
            onNodeWithText("Распределено ${digits(160_000)} из ${digits(162_000)}").assertExists()
        }

    @Test
    fun `BUD-003 the table shows used categories, sums and bad sums in the warning color`() =
        runComposeUiTest {
            setContent { LaresTheme { PlanScreen(state(foodShort, tab = PlanTab.TABLE), PlanActions()) } }
            onAllNodesWithTag("plan_table_name").assertCountEquals(8)
            onAllNodesWithText("Кружок").assertCountEquals(0)
            onNode(hasText("5-е") and hasAnyAncestor(hasTestTag("plan_table"))).assertExists()
            // Food's row total and the 5th's column total.
            onAllNodesWithTag("plan_table_bad").assertCountEquals(2)
            onNode(hasText(digits(58_000)) and hasAnyAncestor(hasTestTag("plan_table_bad"))).assertExists()
        }

    @Test
    fun `BUD-004 a member with only budget view sees no edit actions`() =
        runComposeUiTest {
            setContent { LaresTheme { PlanScreen(state(editable = false), PlanActions()) } }
            onAllNodesWithTag("plan_save").assertCountEquals(0)
            onAllNodesWithTag("plan_add_category").assertCountEquals(0)
            onAllNodesWithTag("plan_category_menu").assertCountEquals(0)
            onNodeWithTag("plan_history").assertExists()
        }

    @Test
    fun `BUD-004 the history lists versions newest first with the one in force`() =
        runComposeUiTest {
            val inputs =
                BudgetInputs(
                    listOf(plan(NOV), plan(JAN, Notebook.raised, at = "2026-12-07T10:00:00Z")),
                    emptyList(),
                    emptyMap(),
                    emptyList(),
                    MEMBERS,
                    TimeZone.of("Europe/Moscow"),
                    Viewer(OLGA, "p-olga", EffectivePermissions(false, setOf(Permission.BUDGET_VIEW))),
                    LocalDate(2026, 12, 10),
                )
            val history = PlanHistoryUiState(true, BudgetViews.history(inputs), inputs.zone)
            var opened: YearMonth? = null
            setContent { LaresTheme { PlanHistoryPage(history, {}, { opened = it }) } }
            val rows = onAllNodesWithTag("plan_version_row")
            rows.assertCountEquals(2)
            rows[0].assert(hasText("С января 2027", substring = true))
            rows[0].assert(hasText("Ольга · 7 дек.", substring = true))
            rows[1].assert(hasText("С ноября 2026", substring = true))
            rows[1].assert(hasText("Действует", substring = true))
            rows[1].performClick()
            assertEquals(NOV, opened)
        }

    @Test
    fun `BUD-001 BUD-002 an income and a category added through the sheets reach the summary`() =
        runComposeUiTest {
            FamilyFixture.core(FamilyFixture.olga.copy(householdId = "01928c4e-0000-7000-8000-0000000000f1")).use { core ->
                val vm = core.koin.get<PlanViewModel> { parametersOf(PlanArgs()) }
                setContent { LaresTheme { PlanRoute(PlanArgs(), {}, {}, vm) } }
                waitUntil(timeoutMillis = 5_000) { vm.state.value.loaded }
                onNodeWithText("Поступления").performClick()
                onNodeWithTag("plan_add_income").performClick()
                field("income_day").performTextReplacement("5")
                field("income_expected").performTextInput("162000")
                onNodeWithTag("income_done").performClick()
                waitForIdle()
                onNodeWithText("5-е число · ${BudgetFormat.amount(162_000)}").assertExists()

                onNodeWithText("Категории").performClick()
                onNodeWithTag("plan_add_category").performClick()
                field("category_name").performTextInput("Продукты")
                field("category_amount").performTextInput("60000")
                field("category_cell").performTextInput("27000")
                onNodeWithTag("category_distributed").assert(hasText("Распределено ${digits(27_000)} из ${digits(60_000)}"))
                onNodeWithTag("category_done").performClick()
                waitForIdle()
                onNodeWithTag("plan_summary_text").assert(hasText("Доход ${digits(162_000)} · Категории ${digits(60_000)}"))
                onNodeWithTag("plan_mismatch").assert(hasText("не сходится на ${digits(102_000)}"))
            }
        }
}
