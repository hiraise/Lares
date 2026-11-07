package app.lares.client.ui.budget

import app.lares.client.data.budget.Notebook
import app.lares.client.ui.FamilyFixture
import app.lares.client.ui.FamilyFixture.change
import app.lares.client.ui.UiTestCore
import app.lares.client.ui.awaitState
import app.lares.client.ui.budget.plan.PlanArgs
import app.lares.client.ui.budget.plan.PlanViewModel
import app.lares.contract.Ids
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.sync.EntityTypes
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minusMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Month-only changes and extra incomes on a synced core (plan M5-06); the household in Moscow, today from the clock. */
class BudgetMonthViewModelTest {
    private val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.of("Europe/Moscow"))
            .date
    private val current = today.yearMonth
    private val previous = current.minusMonth()
    private val household = "01928c4e-0000-7000-8000-0000000000f1"

    private val planChange =
        change(
            EntityTypes.BUDGET_PLAN,
            Ids.budgetPlanRowId(household, previous),
            BudgetPlanData.serializer(),
            BudgetPlanData(previous, Notebook.table, "m1", Clock.System.now()),
        )

    private fun core(): UiTestCore = FamilyFixture.core(FamilyFixture.olga.copy(householdId = household), listOf(planChange))

    private fun UiTestCore.monthMode() =
        koin.get<PlanViewModel> { parametersOf(PlanArgs(month = current)) }.also { vm ->
            awaitState(vm.state) { it.loaded && it.table.categories.isNotEmpty() }
        }

    private fun UiTestCore.budget() = koin.get<BudgetViewModel>().also { vm -> awaitState(vm.state) { it.view != null } }

    /** Scenario 4: Gifts 30 000 instead of 10 000, only this month. */
    private fun PlanViewModel.giftsOnlyThisMonth(amount: Long) {
        editCategory(Notebook.gifts)
        awaitState(state) { it.categoryForm?.deletable != null }
        onCategoryAmount(amount.toString())
        onCell(Notebook.i20, amount.toString())
        saveCategory()
        awaitState(state) { it.categoryForm == null }
    }

    @Test
    fun `BUD-013 month mode saves the month copy with differences marked, equal to the plan writes null`() =
        core().use { core ->
            val vm = core.monthMode()
            var state = vm.state.value
            assertEquals(current, state.month)
            assertTrue(state.editable)
            assertNull(state.differences, "the month starts equal to its plan")

            vm.editCategory(Notebook.gifts)
            assertEquals(false, awaitState(vm.state) { it.categoryForm?.deletable != null }.categoryForm!!.deletable, "a plan category")
            vm.closeCategory()
            vm.giftsOnlyThisMonth(30_000)
            state = awaitState(vm.state) { it.differences != null }
            assertTrue(Notebook.gifts in state.differences!!.changedCategories)
            assertTrue((Notebook.gifts to Notebook.i20) in state.differences.changedCells)

            vm.openSave()
            awaitState(vm.state) { it.saved }
            val budget = core.budget()
            var month = awaitState(budget.state) { it.monthChanged }
            assertTrue(BudgetLogic.categoryChanged(Notebook.gifts, month.view!!.differences))
            assertEquals(
                30_000L,
                month.view.summary.categories
                    .first { it.categoryId == Notebook.gifts }
                    .amount,
            )
            assertEquals(
                10_000L,
                month.view.plan.table.categories
                    .first { it.id == Notebook.gifts }
                    .amount,
                "the plan is not changed",
            )

            val again = core.monthMode()
            assertEquals(
                30_000L,
                again.state.value.table.categories
                    .first { it.id == Notebook.gifts }
                    .amount,
                "month mode starts from the month copy",
            )
            again.giftsOnlyThisMonth(10_000)
            assertNull(awaitState(again.state) { it.changed }.differences)
            again.openSave()
            awaitState(again.state) { it.saved }
            month = awaitState(budget.state) { !it.monthChanged }
            assertNull(month.view!!.differences, "a table equal to the plan is written as null")
        }

    @Test
    fun `BUD-013 a one-off category is only in the month and can be deleted there, reset returns the plan`() =
        core().use { core ->
            val vm = core.monthMode()
            vm.newCategory()
            awaitState(vm.state) { it.categoryForm != null }
            vm.onCategoryName("Ремонт")
            vm.onCategoryAmount("5000")
            vm.onCell(Notebook.i5, "5000")
            vm.saveCategory()
            val state = awaitState(vm.state) { it.categoryForm == null && it.differences != null }
            val repair =
                state.table.categories
                    .first { it.name == "Ремонт" }
                    .id
            assertTrue(repair in state.differences!!.addedCategories)
            vm.editCategory(repair)
            assertEquals(true, awaitState(vm.state) { it.categoryForm?.deletable != null }.categoryForm!!.deletable)
            vm.closeCategory()
            vm.openSave()
            awaitState(vm.state) { it.saved }

            val budget = core.budget()
            var month = awaitState(budget.state) { it.monthChanged }
            assertTrue(
                month.view!!
                    .summary.categories
                    .any { it.categoryId == repair },
            )
            assertTrue(
                month.view.plan.table.categories
                    .none { it.id == repair },
                "never enters the plan",
            )

            budget.resetMonth()
            month = awaitState(budget.state) { !it.monthChanged }
            assertTrue(
                month.view!!
                    .summary.categories
                    .none { it.categoryId == repair },
            )
        }

    @Test
    fun `BUD-011 an extra income needs an amount, adds to free money, changes and is deleted`() =
        core().use { core ->
            val budget = core.budget()
            budget.newExtra()
            var state = awaitState(budget.state) { it.extraForm != null }
            assertEquals(today, state.extraForm!!.date)
            budget.saveExtra()
            assertNotNull(awaitState(budget.state) { it.extraForm?.amountError != null }.extraForm)

            budget.onExtraLabel("Премия")
            budget.onExtraAmount("20 000")
            budget.saveExtra()
            state =
                awaitState(budget.state) {
                    it.extraForm == null &&
                        it.view!!
                            .summary.incomes
                            .any { i -> i.incomeId == null }
                }
            val extra =
                state.view!!
                    .summary.incomes
                    .last()
            assertNull(extra.incomeId, "extra incomes come after the planned ones")
            assertEquals("Премия", extra.label)
            assertEquals(20_000L, extra.actual)
            assertEquals(20_000L, state.view.summary.free)
            val receipt = assertNotNull(extra.receiptId)

            val page = core.koin.get<IncomeViewModel> { parametersOf(IncomeArgs(current, null, receipt)) }
            var income = awaitState(page.state) { it.view != null && it.canManage }
            assertTrue(income.extra)
            assertTrue(income.view!!.rows.isEmpty(), "no categories")
            assertEquals("Премия", income.labelText)

            page.changeLabel("Бонус")
            page.changeAmount("25 000")
            page.commit()
            income = awaitState(page.state) { !it.editing && it.view?.summary?.actual == 25_000L }
            assertEquals("Бонус", income.view!!.summary.label)

            page.changeAmount("")
            page.commit()
            income = awaitState(page.state) { !it.editing }
            assertEquals(25_000L, income.view!!.summary.actual, "an emptied amount is not written")

            page.delete()
            assertTrue(awaitState(page.state) { it.deleted }.deleted)
            state =
                awaitState(budget.state) {
                    it.view!!
                        .summary.incomes
                        .none { i -> i.incomeId == null }
                }
            assertEquals(0L, state.view!!.summary.free)
            assertFalse(state.monthChanged, "an extra income is not a change of the plan")
        }
}
