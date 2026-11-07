package app.lares.contract.budget

import app.lares.contract.Ids
import app.lares.contract.budget.Notebook.car
import app.lares.contract.budget.Notebook.food
import app.lares.contract.budget.Notebook.gifts
import app.lares.contract.budget.Notebook.i10
import app.lares.contract.budget.Notebook.i20
import app.lares.contract.budget.Notebook.i25
import app.lares.contract.budget.Notebook.i5
import app.lares.contract.budget.Notebook.mortgage
import app.lares.contract.budget.Notebook.old
import app.lares.contract.budget.Notebook.safe
import app.lares.contract.budget.Notebook.table
import app.lares.contract.budget.Notebook.utilities
import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetCell
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetIncome
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.entities.BudgetTable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The customer's notebook ([Notebook]) as the fixture. */
class BudgetMathTest {
    private val december = YearMonth(2026, 12)
    private val at = Instant.parse("2026-11-07T10:00:00Z")

    private fun plan(
        from: YearMonth,
        table: BudgetTable = Notebook.table,
    ) = BudgetPlanData(from, table, "m1", at)

    private fun tick(
        income: String,
        category: String,
        amount: Long?,
        month: YearMonth = december,
    ) = BudgetFundingData(month, income, category, amount, fundedBy = "m1", fundedAt = at)

    private fun receipt(
        income: String?,
        amount: Long?,
        day: Int?,
        label: String? = null,
    ) = BudgetReceiptData(december, income, label, amount, day?.let { LocalDate(2026, 12, it) }, "m1")

    private fun sumOfRow(category: String) = table.cells.filter { it.categoryId == category }.sumOf { it.amount }

    @Test
    fun `BUD-004 BUD-010 planFor picks the version in force, firstMonth the earliest`() {
        val v1 = plan(YearMonth(2026, 11))
        val v2 = plan(YearMonth(2027, 1), BudgetTable())
        val v3 = plan(YearMonth(2027, 4), BudgetTable(incomes = listOf(BudgetIncome(i5, 5, 1))))
        val plans = listOf(v3, v1, v2)
        assertNull(BudgetMath.planFor(YearMonth(2026, 10), plans))
        assertEquals(v1, BudgetMath.planFor(YearMonth(2026, 11), plans))
        assertEquals(v1, BudgetMath.planFor(december, plans))
        assertEquals(v2, BudgetMath.planFor(YearMonth(2027, 1), plans))
        assertEquals(v2, BudgetMath.planFor(YearMonth(2027, 3), plans))
        assertEquals(v3, BudgetMath.planFor(YearMonth(2030, 6), plans))
        assertEquals(YearMonth(2026, 11), BudgetMath.firstMonth(plans))
        assertNull(BudgetMath.firstMonth(emptyList()))
    }

    @Test
    fun `BUD-013 tableFor prefers the month's own table`() {
        val plans = listOf(plan(YearMonth(2026, 11)))
        val own = table.copy(cells = table.cells.drop(1))
        assertEquals(own, BudgetMath.tableFor(december, plans, BudgetMonthData(december, own, "m1")))
        assertEquals(table, BudgetMath.tableFor(december, plans, BudgetMonthData(december, null, "m1")))
        assertEquals(table, BudgetMath.tableFor(december, plans, null))
        assertNull(BudgetMath.tableFor(YearMonth(2026, 10), plans, null))
    }

    @Test
    fun `BUD-002 incomeDate clamps to the month's last day`() {
        assertEquals(LocalDate(2027, 2, 28), BudgetMath.incomeDate(YearMonth(2027, 2), 31))
        assertEquals(LocalDate(2028, 2, 29), BudgetMath.incomeDate(YearMonth(2028, 2), 31))
        assertEquals(LocalDate(2027, 4, 30), BudgetMath.incomeDate(YearMonth(2027, 4), 31))
        assertEquals(LocalDate(2027, 5, 31), BudgetMath.incomeDate(YearMonth(2027, 5), 31))
        assertEquals(LocalDate(2026, 12, 5), BudgetMath.incomeDate(december, 5))
    }

    @Test
    fun `BUD-003 check of the notebook adds up and ignores the unused category`() {
        val check = BudgetMath.check(table)
        assertEquals(emptySet(), check.badRows)
        assertEquals(emptySet(), check.badColumns)
        assertEquals(346_000, check.expectedIncome)
        assertEquals(346_000, check.categoriesTotal)
        assertEquals(60_000, check.rows[food])
        assertEquals(162_000, check.columns[i5])
        assertFalse(old in check.rows)
        assertEquals(listOf(i5, i20, i10, i25), check.columns.keys.toList())
    }

    @Test
    fun `BUD-003 check flags a row and a column that do not add up`() {
        val changed =
            table.copy(
                cells =
                    table.cells.map {
                        if (it.categoryId == food &&
                            it.incomeId == i20
                        ) {
                            it.copy(amount = 30_000)
                        } else {
                            it
                        }
                    },
            )
        val check = BudgetMath.check(changed)
        assertEquals(setOf(food), check.badRows)
        assertEquals(setOf(i20), check.badColumns)
        assertEquals(61_000, check.rows[food])
        assertEquals(161_000, check.columns[i20])
        assertEquals(60_000, sumOfRow(food))
    }

    @Test
    fun `BUD-013 differences list changed cells, an added one-off category and a renamed one`() {
        assertTrue(BudgetMath.differences(table, table).isEmpty)
        val repair = Notebook.id(60)
        val month =
            table.copy(
                categories =
                    table.categories.map {
                        when (it.id) {
                            gifts -> it.copy(amount = 30_000)
                            safe -> it.copy(name = "Копилка")
                            mortgage -> it.copy(unused = true)
                            else -> it
                        }
                    } + BudgetCategory(repair, "Ремонт", 5_000),
                cells =
                    table.cells.map { if (it.categoryId == gifts) it.copy(amount = 30_000) else it } +
                        BudgetCell(repair, i5, 5_000) +
                        // A changed cell of an unused category is not a difference.
                        BudgetCell(old, i10, 1_000),
            )
        val differences = BudgetMath.differences(month, table)
        assertFalse(differences.isEmpty)
        assertEquals(setOf(repair), differences.addedCategories)
        assertEquals(setOf(mortgage), differences.removedCategories)
        assertEquals(setOf(gifts, safe), differences.changedCategories)
        assertEquals(setOf(gifts to i20, repair to i5, mortgage to i5), differences.changedCells)
        assertEquals(emptySet(), differences.addedIncomes + differences.removedIncomes + differences.changedIncomes)
    }

    @Test
    fun `BUD-013 differences of incomes`() {
        val extra = Notebook.id(130)
        val month =
            table.copy(
                incomes =
                    table.incomes.filter { it.id != i25 }.map { if (it.id == i10) it.copy(day = 11) else it } +
                        BudgetIncome(extra, 28, 5_000),
            )
        val differences = BudgetMath.differences(month, table)
        assertEquals(setOf(extra), differences.addedIncomes)
        assertEquals(setOf(i25), differences.removedIncomes)
        assertEquals(setOf(i10), differences.changedIncomes)
        assertEquals(setOf(food to i25, safe to i25), differences.changedCells)
    }

    @Test
    fun `BUD-011 BUD-012 BUD-014 summary of a December with a short income, partial funding and a bonus`() {
        val salary = Ids.budgetReceiptRowId(i5, december)
        val bonus = Notebook.id(900)
        val pending = Notebook.id(901)
        val receipts =
            mapOf(
                // An extra income without a date yet sorts after the dated one.
                pending to receipt(null, null, null, label = "Возврат"),
                salary to receipt(i5, 150_000, 4),
                Ids.budgetReceiptRowId(i10, december) to receipt(i10, 12_000, 10),
                bonus to receipt(null, 20_000, 15, label = "Премия"),
                // Another month: ignored.
                Notebook.id(902) to receipt(i20, 160_000, 20).copy(month = YearMonth(2026, 11)),
            )
        val fundings =
            listOf(
                tick(i5, food, 20_000),
                tick(i5, car, 10_000),
                tick(i5, utilities, 25_000),
                // Unticked: fundedBy kept, not counted.
                tick(i5, mortgage, null),
                tick(i10, food, 2_000),
                tick(i10, safe, 10_000),
                // Ticked before the income is recorded.
                tick(i20, car, 10_000),
                // Stale: a category no longer in the table, an unused one, another month.
                tick(i5, Notebook.id(77), 5_000),
                tick(i5, old, 5_000),
                tick(i25, food, 2_000, month = YearMonth(2026, 11)),
            )
        val summary = BudgetMath.summary(december, table, receipts, fundings)

        assertEquals(346_000, summary.expected)
        assertEquals(182_000, summary.actual)
        assertEquals(77_000, summary.transferred)
        assertEquals(12_000, summary.shortfall)
        assertEquals(105_000, summary.free)

        assertEquals(listOf(i5, i10, i20, i25, null, null), summary.incomes.map { it.incomeId })
        assertEquals(
            listOf(salary, Ids.budgetReceiptRowId(i10, december), null, null, bonus, pending),
            summary.incomes.map { it.receiptId },
        )
        val first = summary.incomes[0]
        assertEquals(
            IncomeSummary(i5, salary, null, LocalDate(2026, 12, 5), 162_000, 150_000, LocalDate(2026, 12, 4), 55_000, 3, 4),
            first,
        )
        val twentieth = summary.incomes[2]
        assertEquals(null, twentieth.actual)
        assertEquals(10_000, twentieth.transferred)
        assertEquals(1, twentieth.fundedCount)
        assertEquals(5, twentieth.plannedCount)
        assertEquals(
            IncomeSummary(null, bonus, "Премия", null, 0, 20_000, LocalDate(2026, 12, 15), 0, 0, 0),
            summary.incomes[4],
        )

        assertEquals(8, summary.categories.size)
        assertFalse(summary.categories.any { it.categoryId == old })
        val byId = summary.categories.associateBy { it.categoryId }
        assertEquals(CategorySummary(food, "Продукты", 60_000, 60_000, 22_000, complete = false), byId[food])
        assertEquals(CategorySummary(car, "Машина", 20_000, 20_000, 20_000, complete = true), byId[car])
        assertEquals(CategorySummary(utilities, "Коммуналка", 25_000, 25_000, 25_000, complete = true), byId[utilities])
        assertEquals(0, byId.getValue(mortgage).funded)
    }

    @Test
    fun `BUD-014 a tick before the income is recorded makes free money negative`() {
        val summary = BudgetMath.summary(december, table, emptyMap(), listOf(tick(i20, car, 10_000)))
        assertEquals(0, summary.actual)
        assertEquals(0, summary.shortfall)
        assertEquals(10_000, summary.transferred)
        assertEquals(-10_000, summary.free)
    }

    @Test
    fun `BUD-012 a changed amount counts as transferred and a category without cells is never complete`() {
        val gift = Notebook.id(61)
        val withEmpty = table.copy(categories = table.categories + BudgetCategory(gift, "Сюрприз", 0))
        val summary = BudgetMath.summary(december, withEmpty, emptyMap(), listOf(tick(i5, food, 30_000), tick(i5, gift, 1_000)))
        val byId = summary.categories.associateBy { it.categoryId }
        assertEquals(30_000, byId.getValue(food).funded)
        assertEquals(1_000, byId.getValue(gift).funded)
        assertFalse(byId.getValue(gift).complete)
        // Ticks of categories without a cell count as transferred but not as planned cells.
        assertEquals(31_000, summary.incomes.first().transferred)
        assertEquals(1, summary.incomes.first().fundedCount)
    }

    @Test
    fun `BUD-002 incomes on the same date keep table order`() {
        val a = Notebook.id(140)
        val b = Notebook.id(141)
        val april = YearMonth(2027, 4)
        val twoOnLastDay = BudgetTable(incomes = listOf(BudgetIncome(a, 31, 1), BudgetIncome(b, 30, 1), BudgetIncome(i5, 5, 1)))
        val summary = BudgetMath.summary(april, twoOnLastDay, emptyMap(), emptyList())
        assertEquals(listOf(i5, a, b), summary.incomes.map { it.incomeId })
        assertEquals(LocalDate(2027, 4, 30), summary.incomes[1].plannedDate)
    }
}
