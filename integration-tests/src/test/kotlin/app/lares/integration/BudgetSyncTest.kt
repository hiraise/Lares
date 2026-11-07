package app.lares.integration

import app.lares.client.data.budget.IncomeKey
import app.lares.contract.Ids
import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetCell
import app.lares.contract.entities.BudgetIncome
import app.lares.contract.entities.BudgetTable
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The budget on several phones against the real server (plan M5-03, ADR-0032). Anna and Boris manage it; Carol (Kids) has no access. */
class BudgetSyncTest {
    private val month: YearMonth =
        Clock.System
            .now()
            .toLocalDateTime(TimeZone.of("Europe/Moscow"))
            .date.yearMonth
    private val next = month.plusMonth()

    private val salary = Ids.newId()
    private val advance = Ids.newId()
    private val food = Ids.newId()
    private val car = Ids.newId()
    private val rent = Ids.newId()

    /** Two incomes, three categories; every row and column adds up. */
    private val table =
        BudgetTable(
            categories =
                listOf(
                    BudgetCategory(food, "Food", 60_000),
                    BudgetCategory(car, "Car", 20_000),
                    BudgetCategory(rent, "Rent", 70_000),
                ),
            incomes = listOf(BudgetIncome(salary, 5, 100_000), BudgetIncome(advance, 20, 50_000)),
            cells =
                listOf(
                    BudgetCell(food, salary, 30_000),
                    BudgetCell(food, advance, 30_000),
                    BudgetCell(car, advance, 20_000),
                    BudgetCell(rent, salary, 70_000),
                ),
        )

    private fun date(day: Int) = LocalDate(month.year, month.month, day)

    /** Anna saves the plan from this month; Boris has it too; both go offline. */
    private suspend fun Family.planOffline() {
        anna.budgetWriter.savePlan(table, month)
        anna.sync()
        boris.sync()
        anna.network.online = false
        boris.network.online = false
    }

    private suspend fun Family.online() {
        anna.network.online = true
        boris.network.online = true
    }

    @Test
    fun `BUD-012 two phones tick different categories of one income offline - both survive`() =
        familyTest {
            planOffline()
            anna.budgetWriter.fund(month, salary, food, 30_000)
            boris.budgetWriter.fund(month, salary, rent, 70_000)
            online()
            assertConverged(all)
            listOf(anna, boris).forEach {
                val income = it.budget.observeIncome(month, IncomeKey.Planned(salary)).first()!!
                assertEquals(listOf(30_000L, 70_000L), income.rows.map { row -> row.funding })
                assertEquals(listOf("Anna", "Boris"), income.rows.map { row -> row.fundedBy })
                assertEquals(2, income.summary.fundedCount)
            }
        }

    @Test
    fun `BUD-012 two phones tick the same category offline - one row, the later write wins`() =
        familyTest {
            planOffline()
            anna.budgetWriter.fund(month, advance, car, 20_000)
            boris.budgetWriter.fund(month, advance, car, 15_000)
            anna.network.online = true
            anna.sync()
            boris.network.online = true
            assertConverged(all)
            val rows = anna.snapshot().budgetFundings
            assertEquals(setOf(Ids.budgetFundingRowId(advance, car, month)), rows.keys)
            assertEquals(15_000, rows.values.single().amount)
            assertEquals(borisMemberId, rows.values.single().fundedBy)
        }

    @Test
    fun `BUD-011 two phones record the same income offline - one receipt row`() =
        familyTest {
            planOffline()
            anna.budgetWriter.recordIncome(month, salary, 98_000, date(4))
            boris.budgetWriter.recordIncome(month, salary, 100_000, date(5))
            online()
            assertConverged(all)
            val receipts = boris.snapshot().budgetReceipts
            assertEquals(setOf(Ids.budgetReceiptRowId(salary, month)), receipts.keys)
            val summary =
                anna.budget
                    .observeMonth(month)
                    .first()!!
                    .summary
            assertEquals(receipts.values.single().amount, summary.actual)
        }

    @Test
    fun `BUD-004 a plan saved from next month while the other phone ticks this month - both kept`() =
        familyTest {
            planOffline()
            val raised =
                table.copy(
                    categories = table.categories.map { if (it.id == food) it.copy(amount = 65_000) else it },
                    incomes = table.incomes.map { if (it.id == advance) it.copy(amount = 55_000) else it },
                    cells = table.cells.map { if (it.categoryId == food && it.incomeId == advance) it.copy(amount = 35_000) else it },
                )
            anna.budgetWriter.savePlan(raised, next)
            boris.budgetWriter.fund(month, salary, food, 30_000)
            online()
            assertConverged(all)
            listOf(anna, boris).forEach {
                val current = it.budget.observeMonth(month).first()!!
                assertEquals(table, current.table, "this month keeps its version")
                assertEquals(30_000, current.summary.transferred)
                assertEquals(
                    raised,
                    it.budget
                        .observeMonth(next)
                        .first()!!
                        .table,
                )
                assertEquals(
                    listOf(next, month),
                    it.budget
                        .observeHistory()
                        .first()
                        .map { row -> row.effectiveFrom },
                )
            }
            assertEquals(2, anna.snapshot().budgetPlans.size)
        }

    @Test
    fun `BUD-004 a plan saved from this month resets the month copy on both phones and keeps the ticks`() =
        familyTest {
            anna.budgetWriter.savePlan(table, month)
            // This month only: no car, its money goes to food.
            val ownCells =
                table.cells
                    .filterNot { it.categoryId == car }
                    .map { if (it.categoryId == food && it.incomeId == advance) it.copy(amount = 50_000) else it }
            anna.budgetWriter.editMonth(month, table.copy(cells = ownCells))
            anna.budgetWriter.fund(month, salary, food, 30_000)
            anna.sync()
            boris.sync()
            assertTrue(
                boris.budget
                    .observeMonth(month)
                    .first()!!
                    .differences != null,
            )

            boris.budgetWriter.savePlan(table.copy(categories = table.categories.map { it.copy(name = it.name + "!") }), month)
            assertConverged(all)
            val view = anna.budget.observeMonth(month).first()!!
            assertNull(view.differences)
            assertEquals(
                "Food!",
                view.table.categories
                    .first()
                    .name,
            )
            assertEquals(30_000, view.summary.transferred)
        }

    @Test
    fun `BUD-015 a member without budget view holds no budget rows`() =
        familyTest {
            anna.budgetWriter.savePlan(table, month)
            anna.budgetWriter.recordIncome(month, salary, 100_000, date(5))
            anna.budgetWriter.fund(month, salary, rent, 70_000)
            anna.budgetWriter.addExtraIncome(month, "Bonus", 10_000, date(8))
            anna.sync()
            assertConverged(all)
            assertTrue(anna.snapshot().hasBudget)
            assertFalse(carol.snapshot().hasBudget)
            assertFalse(carol.serverSnapshot().hasBudget)
            assertEquals(emptyList(), carol.budget.observeMonths().first())
            assertNull(carol.budget.observeToday().first())
        }
}
