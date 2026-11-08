package app.lares.client.ui.budget

import app.lares.client.data.budget.Notebook
import app.lares.client.resources.Res
import app.lares.client.resources.bud_amount_of
import app.lares.client.resources.bud_income_shortfall
import app.lares.client.ui.FamilyFixture
import app.lares.client.ui.FamilyFixture.change
import app.lares.client.ui.UiTestCore
import app.lares.client.ui.awaitState
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.contract.Ids
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.GroupData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionCatalogue
import app.lares.contract.sync.EntityTypes
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The budget view models on a synced core: the notebook plan in force since last month, the household in Moscow. */
class BudgetViewModelTest {
    private val today =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.of("Europe/Moscow"))
            .date
    private val current = today.yearMonth
    private val previous = current.minusMonth()

    /** Derived budget ids need a UUID household id. */
    private val household = "01928c4e-0000-7000-8000-0000000000f1"

    private val planChange =
        change(
            EntityTypes.BUDGET_PLAN,
            Ids.budgetPlanRowId(household, previous),
            BudgetPlanData.serializer(),
            BudgetPlanData(previous, Notebook.table, "m1", Clock.System.now()),
        )

    /** Adults (Boris) may only look at the budget. */
    private val viewOnlyAdults =
        change(
            EntityTypes.GROUP,
            FamilyFixture.ADULTS,
            GroupData.serializer(),
            GroupData("Adults", null, PermissionCatalogue.adultsTemplate - Permission.BUDGET_MANAGE, setOf("m2")),
        )

    private fun core(viewOnly: Boolean = false): UiTestCore =
        if (viewOnly) {
            FamilyFixture.core(FamilyFixture.boris.copy(householdId = household), listOf(planChange, viewOnlyAdults))
        } else {
            FamilyFixture.core(FamilyFixture.olga.copy(householdId = household), listOf(planChange))
        }

    private fun income(
        core: UiTestCore,
        incomeId: String = Notebook.i5,
    ) = core.koin.get<IncomeViewModel> { parametersOf(IncomeArgs(current, incomeId)) }

    @Test
    fun `BUD-020 the budget opens on the current month and moves within the months of the plan`() =
        core().use { core ->
            val vm = core.koin.get<BudgetViewModel>()
            var state = awaitState(vm.state) { it.view != null }
            assertEquals(current, state.month)
            assertEquals(listOf(current, previous), state.months.map { it.month })
            assertEquals(previous, state.previous)
            assertNull(state.next)
            assertEquals(346_000L, state.view!!.summary.expected)

            vm.show(previous)
            state = awaitState(vm.state) { it.month == previous }
            assertNull(state.previous)
            assertEquals(current, state.next)

            vm.show(YearMonth(2000, 1))
            state = awaitState(vm.state) { it.month == current }
            assertEquals(current, state.month)
        }

    @Test
    fun `BUD-011 the first edit prefills the expected amount and today, 150 000 records a shortfall of 12 000`() =
        core().use { core ->
            val vm = income(core)
            awaitState(vm.state) { it.view != null && it.canManage }
            vm.startEdit()
            var state = awaitState(vm.state) { it.editing }
            assertEquals("162000", state.amountText)
            assertEquals(today, state.date)

            vm.changeAmount("150 000")
            vm.commit()
            state = awaitState(vm.state) { !it.editing && it.view?.summary?.actual == 150_000L }
            assertEquals(today, state.view!!.summary.actualDate)
            assertEquals("150000", state.amountText)
            assertEquals(
                uiText(
                    Res.string.bud_income_shortfall,
                    raw(BudgetFormat.amount(12_000)),
                ),
                BudgetLogic.incomeShortfall(state.view.summary),
            )

            vm.clear()
            state = awaitState(vm.state) { it.view?.summary?.actual == null }
            assertEquals("", state.amountText)
        }

    @Test
    fun `BUD-012 a tick writes the planned amount, the sheet changes it, Tick all funds the rest`() =
        core().use { core ->
            val vm = income(core)
            var state = awaitState(vm.state) { it.view != null }
            assertEquals(4, state.toFund.size)
            vm.toggle(state.toFund.first { it.categoryId == Notebook.car })
            state = awaitState(vm.state) { s -> s.funded.any { it.categoryId == Notebook.car } }
            assertEquals(10_000L, state.funded.single().funding)
            assertEquals("Olga", state.funded.single().fundedBy)
            assertEquals(3, state.toFund.size)

            vm.openSheet(state.toFund.first { it.categoryId == Notebook.food })
            assertEquals("27000", awaitState(vm.state) { it.sheet != null }.sheet!!.text)
            vm.changeSheet("20000")
            vm.saveSheet()
            state = awaitState(vm.state) { s -> s.funded.any { it.categoryId == Notebook.food } }
            assertNull(state.sheet)
            val food = state.funded.single { it.categoryId == Notebook.food }
            assertEquals(20_000L, food.funding)
            assertEquals(
                uiText(
                    Res.string.bud_amount_of,
                    raw("20 000"),
                    raw("27 000"),
                ),
                BudgetLogic.fundingAmount(food),
            )
            assertEquals(2, state.view!!.summary.fundedCount)

            vm.toggle(state.funded.first { it.categoryId == Notebook.car })
            state = awaitState(vm.state) { s -> s.toFund.any { it.categoryId == Notebook.car } }

            vm.fundAll()
            state = awaitState(vm.state) { it.toFund.isEmpty() }
            assertEquals(4, state.view!!.summary.fundedCount)
            assertEquals(20_000L, state.funded.single { it.categoryId == Notebook.food }.funding)
        }

    @Test
    fun `BUD-012 a member with only budget view cannot edit`() =
        core(viewOnly = true).use { core ->
            val vm = income(core)
            var state = awaitState(vm.state) { it.view != null }
            assertFalse(state.canManage)
            vm.startEdit()
            vm.openSheet(state.toFund.first())
            state = vm.state.value
            assertFalse(state.editing)
            assertNull(state.sheet)
            assertTrue(state.funded.isEmpty())
        }
}
