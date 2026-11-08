package app.lares.client.data.budget

import app.lares.client.data.budget.Notebook.car
import app.lares.client.data.budget.Notebook.food
import app.lares.client.data.budget.Notebook.i10
import app.lares.client.data.budget.Notebook.i20
import app.lares.client.data.budget.Notebook.i25
import app.lares.client.data.budget.Notebook.i5
import app.lares.client.data.budget.Notebook.mortgage
import app.lares.client.data.budget.Notebook.utilities
import app.lares.client.data.tasks.DIMA
import app.lares.client.data.tasks.OLGA
import app.lares.contract.Ids
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.entities.BudgetTable
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BudgetViewsTest {
    private val oct = YearMonth(2026, 10)
    private val v1 = plan(oct)
    private val v2 = plan(DEC, Notebook.raised, by = DIMA, at = "2026-11-20T10:00:00Z")
    private val v3 = plan(JAN, Notebook.table, at = "2026-12-05T10:00:00Z")

    /** December with its own copy: food from the 5th is 25 000 instead of 27 000, its monthly amount 63 000. */
    private val decCopy =
        Notebook.raised.copy(
            categories = Notebook.raised.categories.map { if (it.id == food) it.copy(amount = 63_000) else it },
            cells = Notebook.raised.cells.map { if (it.categoryId == food && it.incomeId == i5) it.copy(amount = 25_000) else it },
        )

    private fun receipt(
        incomeId: String,
        amount: Long,
        date: LocalDate,
        month: YearMonth = DEC,
    ) = Ids.budgetReceiptRowId(incomeId, month) to BudgetReceiptData(month, incomeId, amount = amount, date = date, updatedBy = OLGA)

    private fun tick(
        incomeId: String,
        categoryId: String,
        amount: Long?,
        by: String = OLGA,
    ) = BudgetFundingData(DEC, incomeId, categoryId, amount, by, Instant.parse("2026-12-05T10:00:00Z"))

    private fun inputs(
        plans: List<BudgetPlanData> = listOf(v1, v2, v3),
        monthRows: List<BudgetMonthData> = emptyList(),
        receipts: Map<String, BudgetReceiptData> = emptyMap(),
        fundings: List<BudgetFundingData> = emptyList(),
        today: LocalDate = LocalDate(2026, 12, 10),
        permissions: Set<Permission> = BUDGET_MANAGER.toSet(),
    ) = BudgetInputs(
        plans,
        monthRows,
        receipts,
        fundings,
        MEMBERS,
        TimeZone.of("Europe/Moscow"),
        Viewer(OLGA, "p-olga", EffectivePermissions(false, permissions)),
        today,
    )

    @Test
    fun `BUD-010 months from the first version to the current one across two versions, newest first`() {
        val rows = BudgetViews.months(inputs(receipts = mapOf(receipt(i5, 150_000, LocalDate(2026, 12, 4)))))
        assertEquals(listOf(DEC, NOV, oct), rows.map { it.month }, "the future version of January adds no month")
        assertEquals(listOf(true, false, false), rows.map { it.current })
        val dec = rows.first()
        assertEquals(346_000, dec.expected)
        assertEquals(150_000, dec.actual)
        assertEquals(12_000, dec.shortfall)
        assertEquals(150_000, dec.free)
    }

    @Test
    fun `BUD-013 month view with a month copy shows the differences from its plan version`() {
        val view = BudgetViews.month(inputs(monthRows = listOf(BudgetMonthData(DEC, decCopy, OLGA))), DEC)!!
        assertEquals(v2, view.plan)
        assertEquals(decCopy, view.table)
        assertEquals(setOf(food to i5), view.differences!!.changedCells)
        assertEquals(setOf(food), view.differences!!.changedCategories)
        assertEquals(setOf(i5), view.check.badColumns, "160 000 of 162 000 distributed")
        assertTrue(view.canManage)
        assertTrue(view.isCurrent)

        val nov = BudgetViews.month(inputs(permissions = BUDGET_VIEWER.toSet()), NOV)!!
        assertEquals(v1, nov.plan)
        assertNull(nov.differences, "follows its plan")
        assertFalse(nov.canManage)
        assertFalse(nov.isCurrent)
    }

    @Test
    fun `BUD-012 income view with partial and changed ticks`() {
        val view =
            BudgetViews.income(
                inputs(
                    monthRows = listOf(BudgetMonthData(DEC, decCopy, OLGA)),
                    receipts = mapOf(receipt(i5, 150_000, LocalDate(2026, 12, 4))),
                    fundings = listOf(tick(i5, food, 20_000), tick(i5, car, 10_000, by = DIMA), tick(i5, utilities, null)),
                ),
                DEC,
                IncomeKey.Planned(i5),
            )!!
        assertEquals(150_000, view.summary.actual)
        assertEquals(2, view.summary.fundedCount)
        assertEquals(4, view.summary.plannedCount, "the unused category's cell is left out")
        assertEquals(
            listOf(
                FundingRow(food, "Продукты", 25_000, 20_000, "Ольга", changed = true),
                FundingRow(car, "Машина", 10_000, 10_000, "Дмитрий", changed = false),
                FundingRow(utilities, "Коммуналка", 25_000, null, null, changed = false),
                FundingRow(mortgage, "Ипотека", 100_000, null, null, changed = false),
            ),
            view.rows,
        )
    }

    @Test
    fun `BUD-011 an extra income has no rows, a missing one no view`() {
        val bonusId = Ids.newId()
        val bonus = BudgetReceiptData(DEC, null, "Премия", 30_000, LocalDate(2026, 12, 8), OLGA)
        val inputs = inputs(receipts = mapOf(bonusId to bonus))
        val view = BudgetViews.income(inputs, DEC, IncomeKey.Extra(bonusId))!!
        assertEquals("Премия", view.summary.label)
        assertEquals(emptyList(), view.rows)
        assertNull(BudgetViews.income(inputs, DEC, IncomeKey.Extra(Ids.newId())))
        assertNull(BudgetViews.income(inputs, DEC, IncomeKey.Planned(Ids.newId())))
    }

    @Test
    fun `BUD-004 plan view for a future month and the history`() {
        val inputs = inputs()
        val future = BudgetViews.plan(inputs, JAN)
        assertEquals(v3, future.version)
        assertEquals(DEC, future.editableFrom)
        assertTrue(future.check.badRows.isEmpty())
        assertEquals(346_000, future.check.expectedIncome)
        assertEquals(v2, BudgetViews.plan(inputs, DEC).version)

        val history = BudgetViews.history(inputs)
        assertEquals(listOf(JAN, DEC, oct), history.map { it.effectiveFrom })
        assertEquals(listOf(false, true, false), history.map { it.current })
        assertEquals(listOf("Ольга", "Дмитрий", "Ольга"), history.map { it.updatedBy })
    }

    @Test
    fun `BUD-021 today before and after all incomes are recorded and funded`() {
        val first =
            BudgetViews.today(
                inputs(
                    receipts = mapOf(receipt(i5, 150_000, LocalDate(2026, 12, 4))),
                    fundings = listOf(tick(i5, food, 20_000), tick(i5, car, 10_000)),
                ),
            )!!
        assertEquals(i10, first.nextIncome?.incomeId, "the income of today is still expected")
        assertEquals(2, first.notFunded, "utilities and mortgage from the 5th")

        val late = BudgetViews.today(inputs(today = LocalDate(2026, 12, 28)))!!
        assertEquals(i5, late.nextIncome?.incomeId, "all dates passed: the earliest unrecorded one")
        assertEquals(0, late.notFunded, "nothing recorded, nothing to fund yet")

        val all = listOf(i5, i10, i20, i25)
        val table = Notebook.raised
        val done =
            BudgetViews.today(
                inputs(
                    receipts = all.associate { receipt(it, 1, LocalDate(2026, 12, 1)) },
                    fundings = table.cells.map { tick(it.incomeId, it.categoryId, it.amount) },
                ),
            )!!
        assertEquals(BudgetToday(null, 0), done)
    }

    @Test
    fun `no plan - no months, no month, no today, an empty plan`() {
        val inputs = inputs(plans = emptyList())
        assertEquals(emptyList(), BudgetViews.months(inputs))
        assertNull(BudgetViews.month(inputs, DEC))
        assertNull(BudgetViews.today(inputs))
        assertNull(BudgetViews.income(inputs, DEC, IncomeKey.Planned(i5)))
        assertEquals(PlanView(null, BudgetTable(), BudgetViews.plan(inputs, DEC).check, DEC), BudgetViews.plan(inputs, DEC))
        assertEquals(emptyList(), BudgetViews.history(inputs))
        assertNull(BudgetViews.month(inputs(), YearMonth(2026, 9)), "before the first version")
    }
}
