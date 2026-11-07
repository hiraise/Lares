package app.lares.client.ui.budget

import app.lares.client.data.budget.Notebook
import app.lares.client.resources.Res
import app.lares.client.resources.bud_plan_summary
import app.lares.client.ui.FamilyFixture
import app.lares.client.ui.FamilyFixture.change
import app.lares.client.ui.UiTestCore
import app.lares.client.ui.awaitState
import app.lares.client.ui.budget.plan.PlanArgs
import app.lares.client.ui.budget.plan.PlanHistoryViewModel
import app.lares.client.ui.budget.plan.PlanLogic
import app.lares.client.ui.budget.plan.PlanTab
import app.lares.client.ui.budget.plan.PlanViewModel
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.contract.Ids
import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.GroupData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.contract.sync.EntityTypes
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plusMonth
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

/** The plan editor on a synced core (plan M5-05); the household in Moscow, today from the system clock. */
class PlanViewModelTest {
    private val current =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.of("Europe/Moscow"))
            .date.yearMonth
    private val previous = current.minusMonth()
    private val next = current.plusMonth()
    private val household = "01928c4e-0000-7000-8000-0000000000f1"

    private val planChange =
        change(
            EntityTypes.BUDGET_PLAN,
            Ids.budgetPlanRowId(household, previous),
            BudgetPlanData.serializer(),
            BudgetPlanData(previous, Notebook.table, "m1", Clock.System.now()),
        )

    private val viewOnlyAdults =
        change(
            EntityTypes.GROUP,
            FamilyFixture.ADULTS,
            GroupData.serializer(),
            GroupData("Adults", null, PermissionCatalogue.adultsTemplate - Permission.BUDGET_MANAGE, setOf("m2")),
        )

    private fun core(
        withPlan: Boolean = true,
        viewOnly: Boolean = false,
    ): UiTestCore {
        val plans = if (withPlan) listOf(planChange) else emptyList()
        return if (viewOnly) {
            FamilyFixture.core(FamilyFixture.boris.copy(householdId = household), plans + viewOnlyAdults)
        } else {
            FamilyFixture.core(FamilyFixture.olga.copy(householdId = household), plans)
        }
    }

    private fun UiTestCore.plan(args: PlanArgs = PlanArgs()) = koin.get<PlanViewModel> { parametersOf(args) }

    private fun PlanViewModel.loaded() = awaitState(state) { it.loaded && it.current != null }

    private fun PlanViewModel.addIncome(
        day: Int,
        amount: Long,
    ): String {
        newIncome()
        val id = awaitState(state) { it.incomeForm?.isNew == true }.incomeForm!!.id
        onIncomeDay(day.toString())
        onIncomeAmount(amount.toString())
        saveIncome()
        awaitState(state) { it.incomeForm == null }
        return id
    }

    private fun PlanViewModel.addCategory(
        name: String,
        amount: Long,
        cells: Map<String, Long>,
    ) {
        newCategory()
        onCategoryName(name)
        onCategoryAmount(amount.toString())
        cells.forEach { (income, value) -> onCell(income, value.toString()) }
        saveCategory()
        awaitState(state) { it.categoryForm == null }
    }

    @Test
    fun `BUD-001 BUD-002 BUD-003 the notebook built from scratch adds up to 346 000 and saves from this month`() =
        core(withPlan = false).use { core ->
            val vm = core.plan()
            var state = vm.loaded()
            assertTrue(state.editable)
            assertEquals(listOf(current, next), state.saveMonths)

            val notebook = Notebook.table
            val ids =
                notebook.incomes.associate { it.id to vm.addIncome(it.day, it.amount) }
            notebook.categories.filterNot { it.unused }.forEach { category ->
                val cells = notebook.cells.filter { it.categoryId == category.id }.associate { ids.getValue(it.incomeId) to it.amount }
                vm.addCategory(category.name, category.amount, cells)
            }
            state = awaitState(vm.state) { it.table.categories.size == 8 }
            assertEquals(listOf(5, 10, 20, 25), state.incomes.map { it.day })
            assertEquals(346_000L, state.check.expectedIncome)
            assertEquals(346_000L, state.check.categoriesTotal)
            assertTrue(PlanLogic.balanced(state.check))
            assertEquals(
                uiText(Res.string.bud_plan_summary, raw(BudgetFormat.digits(346_000)), raw(BudgetFormat.digits(346_000))),
                PlanLogic.summary(state.check),
            )
            assertTrue(state.changed)

            vm.openSave()
            assertEquals(current, awaitState(vm.state) { it.saving != null }.saving)
            vm.confirmSave()
            awaitState(vm.state) { it.saved }
            val budget = core.koin.get<BudgetViewModel>()
            val month = awaitState(budget.state) { it.view != null }
            assertEquals(346_000L, month.view!!.summary.expected)
        }

    @Test
    fun `BUD-001 BUD-003 rename, per-income amounts, unused and a duplicate name in the draft`() =
        core().use { core ->
            val vm = core.plan()
            vm.loaded()
            assertEquals(
                next,
                vm.state.value.saveMonths
                    .first(),
            )

            vm.editCategory(Notebook.food)
            var state = awaitState(vm.state) { it.categoryForm != null }
            assertEquals("27000", state.categoryForm!!.cells[Notebook.i5])
            vm.onCategoryName("Машина")
            vm.saveCategory()
            assertNotNull(awaitState(vm.state) { it.categoryForm?.nameError != null }.categoryForm)
            vm.onCategoryName("Еда")
            vm.onCell(Notebook.i5, "25 000")
            awaitState(vm.state) { it.categoryForm?.distributed == 58_000L }
            vm.saveCategory()
            state = awaitState(vm.state) { it.categoryForm == null }
            assertEquals(
                "Еда",
                state.table.categories
                    .first { it.id == Notebook.food }
                    .name,
            )
            assertTrue(Notebook.food in state.check.badRows)
            assertTrue(Notebook.i5 in state.check.badColumns)
            assertTrue(state.changed)

            vm.editCategory(Notebook.safe)
            awaitState(vm.state) { it.categoryForm != null }
            vm.onCategoryUnused(true)
            vm.saveCategory()
            state = awaitState(vm.state) { it.categoryForm == null }
            assertFalse(state.usedCategories.any { it.id == Notebook.safe })
            assertTrue(state.unusedCategories.any { it.id == Notebook.safe })
            assertEquals(326_000L, state.check.categoriesTotal)
        }

    @Test
    fun `BUD-001 BUD-002 used rows cannot be deleted, new ones can, incomes and categories leave with their cells`() =
        core().use { core ->
            val vm = core.plan()
            vm.loaded()
            vm.editCategory(Notebook.food)
            // The version in force uses the category: «Удалить» does nothing.
            assertEquals(false, awaitState(vm.state) { it.categoryForm?.deletable != null }.categoryForm!!.deletable)
            vm.deleteCategory()
            assertTrue(awaitState(vm.state) { it.categoryForm == null }.table.categories.any { it.id == Notebook.food })

            vm.editIncome(Notebook.i5)
            assertEquals(false, awaitState(vm.state) { it.incomeForm?.deletable != null }.incomeForm!!.deletable)
            vm.closeIncome()

            val income = vm.addIncome(15, 5_000)
            vm.addCategory("Кино", 5_000, mapOf(income to 5_000L))
            var state = vm.state.value
            assertEquals(5, state.table.incomes.size)
            assertEquals(
                BudgetCategory(
                    state.table.categories
                        .last()
                        .id,
                    "Кино",
                    5_000,
                ),
                state.table.categories.last(),
            )

            vm.editIncome(income)
            awaitState(vm.state) { it.incomeForm?.deletable == true }
            vm.deleteIncome()
            state = awaitState(vm.state) { it.table.incomes.size == 4 }
            assertTrue(state.table.cells.none { it.incomeId == income })

            val kino =
                state.table.categories
                    .last()
                    .id
            vm.editCategory(kino)
            awaitState(vm.state) { it.categoryForm?.deletable == true }
            vm.deleteCategory()
            state = awaitState(vm.state) { s -> s.table.categories.none { it.id == kino } }
            assertFalse(state.changed)
        }

    @Test
    fun `BUD-004 saving from next month leaves this month as it was and the history lists two versions`() =
        core().use { core ->
            val vm = core.plan()
            vm.loaded()
            vm.editCategory(Notebook.food)
            awaitState(vm.state) { it.categoryForm != null }
            vm.onCategoryAmount("65000")
            vm.onCell(Notebook.i20, "34000")
            vm.saveCategory()
            awaitState(vm.state) { it.categoryForm == null && it.changed }
            vm.openSave()
            assertEquals(next, awaitState(vm.state) { it.saving != null }.saving)
            vm.chooseSaveMonth(current)
            vm.chooseSaveMonth(next)
            vm.confirmSave()
            awaitState(vm.state) { it.saved }

            val budget = core.koin.get<BudgetViewModel>()
            val month = awaitState(budget.state) { it.view != null }
            assertEquals(
                60_000L,
                month.view!!
                    .table.categories
                    .first { it.id == Notebook.food }
                    .amount,
            )

            val history = core.koin.get<PlanHistoryViewModel>()
            val rows = awaitState(history.state) { it.rows.size == 2 }.rows
            assertEquals(listOf(next, previous), rows.map { it.effectiveFrom })
            assertEquals(listOf(false, true), rows.map { it.current })

            // The editor starts from the version saved for next month.
            val again = core.plan()
            assertEquals(
                65_000L,
                awaitState(again.state) { it.loaded && it.table.categories.isNotEmpty() }
                    .table.categories
                    .first()
                    .amount,
            )
            // A version opens read-only on the table.
            val old = core.plan(PlanArgs(version = previous))
            val shown = awaitState(old.state) { it.loaded && it.table.categories.isNotEmpty() }
            assertFalse(shown.editable)
            assertEquals(PlanTab.TABLE, shown.tab)
            assertEquals(
                60_000L,
                shown.table.categories
                    .first()
                    .amount,
            )
        }

    @Test
    fun `BUD-004 a member with only budget view sees the plan but cannot change it`() =
        core(viewOnly = true).use { core ->
            val vm = core.plan()
            val state = awaitState(vm.state) { it.loaded && it.table.categories.isNotEmpty() }
            assertFalse(state.editable)
            vm.newCategory()
            vm.newIncome()
            vm.editCategory(Notebook.food)
            vm.moveCategory(Notebook.car, up = true)
            vm.openSave()
            val after = vm.state.value
            assertNull(after.categoryForm)
            assertNull(after.incomeForm)
            assertNull(after.saving)
            assertFalse(after.changed)
        }

    @Test
    fun `BUD-001 categories move up and down within their group`() {
        val categories = Notebook.table.categories
        val up = PlanLogic.move(categories, Notebook.car, up = true)!!
        assertEquals(listOf(Notebook.car, Notebook.food), up.take(2).map { it.id })
        assertNull(PlanLogic.move(categories, Notebook.food, up = true))
        // «Накопления» is the last used one; the unused «Кружок» after it is another group.
        assertNull(PlanLogic.move(categories, Notebook.savings, up = false))
        assertNull(PlanLogic.move(categories, Notebook.old, up = true))
    }
}
