package app.lares.client.data.budget

import app.lares.client.data.budget.Notebook.car
import app.lares.client.data.budget.Notebook.food
import app.lares.client.data.budget.Notebook.i10
import app.lares.client.data.budget.Notebook.i5
import app.lares.client.data.budget.Notebook.mortgage
import app.lares.client.data.budget.Notebook.old
import app.lares.client.data.budget.Notebook.utilities
import app.lares.client.data.tasks.DIMA
import app.lares.client.data.tasks.OLGA
import app.lares.client.sync.LocalWriteException
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class BudgetWriterTest {
    private val f = BudgetFixture()
    private val monthId = Ids.budgetMonthRowId(HOUSEHOLD, DEC)
    private val now = Instant.parse("2026-12-10T09:00:00Z")

    /** December with its own copy: no gifts this month. */
    private val noGifts =
        Notebook.table.copy(
            categories = Notebook.table.categories.filterNot { it.id == Notebook.gifts },
            cells = Notebook.table.cells.filterNot { it.categoryId == Notebook.gifts },
        )

    private suspend fun setUp() {
        f.family()
        f.loginAs(OLGA, *BUDGET_MANAGER)
        f.putPlan(plan(NOV))
    }

    private suspend fun month(id: String = monthId) = f.row<BudgetMonthData>(EntityTypes.BUDGET_MONTH, id)

    private suspend fun funding(
        incomeId: String,
        categoryId: String,
    ) = f.row<BudgetFundingData>(EntityTypes.BUDGET_FUNDING, Ids.budgetFundingRowId(incomeId, categoryId, DEC))

    @Test
    fun `BUD-004 a plan saved from next month leaves the current month unchanged`() =
        runTest {
            setUp()
            f.budgetWriter.savePlan(Notebook.raised, JAN)
            assertEquals(
                Notebook.table,
                f.repository
                    .observeMonth(DEC)
                    .first()!!
                    .table,
            )
            assertEquals(
                Notebook.raised,
                f.repository
                    .observeMonth(JAN)
                    .first()!!
                    .table,
            )
            val saved = f.row<BudgetPlanData>(EntityTypes.BUDGET_PLAN, Ids.budgetPlanRowId(HOUSEHOLD, JAN))!!
            assertEquals(OLGA, saved.updatedBy)
            assertEquals(now, saved.updatedAt)
            assertEquals(listOf(EntityTypes.BUDGET_PLAN), f.outbox().map { it.type })
        }

    @Test
    fun `BUD-004 a plan saved from the current month resets its month copy and keeps receipts and ticks`() =
        runTest {
            setUp()
            f.putMonth(BudgetMonthData(DEC, noGifts, DIMA))
            f.putReceipt(BudgetReceiptData(DEC, i5, amount = 150_000, date = LocalDate(2026, 12, 4), updatedBy = DIMA))
            f.putFunding(BudgetFundingData(DEC, i5, food, 27_000, DIMA, now))

            f.budgetWriter.savePlan(Notebook.raised, DEC)
            assertEquals(listOf(EntityTypes.BUDGET_PLAN, EntityTypes.BUDGET_MONTH), f.outbox().map { it.type }, "the version first")
            assertEquals(BudgetMonthData(DEC, null, OLGA), month())
            assertEquals(JsonNull, f.fields(1)["table"])
            val view = f.repository.observeMonth(DEC).first()!!
            assertEquals(Notebook.raised, view.table)
            assertNull(view.differences)
            assertEquals(150_000, view.summary.actual)
            assertEquals(27_000, view.summary.transferred)
        }

    @Test
    fun `BUD-004 a plan cannot be saved from a past month`() =
        runTest {
            setUp()
            val e = assertFailsWith<LocalWriteException> { f.budgetWriter.savePlan(Notebook.raised, NOV) }
            assertEquals(ErrorCode.VALIDATION_FAILED, e.code)
            assertEquals(emptyList(), f.outbox())
        }

    @Test
    fun `BUD-013 a month copy equal to the plan is written as null, a reset clears it`() =
        runTest {
            setUp()
            f.budgetWriter.editMonth(DEC, Notebook.table)
            assertEquals(emptyList(), f.outbox(), "no copy, nothing to write")

            f.budgetWriter.editMonth(DEC, noGifts)
            assertEquals(noGifts, month()!!.table)
            assertEquals(
                setOf(Notebook.gifts),
                f.repository
                    .observeMonth(DEC)
                    .first()!!
                    .differences!!
                    .removedCategories,
            )

            // Names are compared normalized: the same plan with extra spaces is the plan.
            val spaced = Notebook.table.copy(categories = Notebook.table.categories.map { it.copy(name = " ${it.name} ") })
            f.budgetWriter.editMonth(DEC, spaced)
            assertNull(month()!!.table)

            f.budgetWriter.editMonth(DEC, noGifts)
            f.budgetWriter.resetMonth(DEC)
            assertNull(month()!!.table)
            f.budgetWriter.resetMonth(DEC)
            assertEquals(4, f.outbox().size, "a reset of a month that follows its plan writes nothing")
        }

    @Test
    fun `BUD-011 record and clear a planned income`() =
        runTest {
            setUp()
            val id = Ids.budgetReceiptRowId(i5, DEC)
            f.budgetWriter.recordIncome(DEC, i5, 150_000, LocalDate(2026, 12, 4))
            assertEquals(BudgetReceiptData(DEC, i5, null, 150_000, LocalDate(2026, 12, 4), OLGA), f.row(EntityTypes.BUDGET_RECEIPT, id))
            f.budgetWriter.clearIncome(DEC, i5)
            assertEquals(BudgetReceiptData(DEC, i5, updatedBy = OLGA), f.row(EntityTypes.BUDGET_RECEIPT, id))
            assertEquals(JsonNull, f.fields(1)["amount"])
            f.budgetWriter.clearIncome(DEC, i10)
            assertEquals(2, f.outbox().size, "nothing recorded, nothing to clear")
        }

    @Test
    fun `BUD-011 extra incomes are added, changed and deleted`() =
        runTest {
            setUp()
            val id = f.budgetWriter.addExtraIncome(DEC, " Премия ", 30_000, LocalDate(2026, 12, 8))
            assertEquals(
                BudgetReceiptData(DEC, null, "Премия", 30_000, LocalDate(2026, 12, 8), OLGA),
                f.row(EntityTypes.BUDGET_RECEIPT, id),
            )
            f.budgetWriter.updateExtraIncome(id, null, 35_000, LocalDate(2026, 12, 9))
            assertEquals(BudgetReceiptData(DEC, null, null, 35_000, LocalDate(2026, 12, 9), OLGA), f.row(EntityTypes.BUDGET_RECEIPT, id))
            assertEquals(
                35_000,
                f.repository
                    .observeMonth(DEC)
                    .first()!!
                    .summary.free,
            )
            f.budgetWriter.deleteExtraIncome(id)
            assertNull(f.row<BudgetReceiptData>(EntityTypes.BUDGET_RECEIPT, id))
            assertEquals("delete", f.outbox().last().op)

            f.budgetWriter.recordIncome(DEC, i5, 150_000, LocalDate(2026, 12, 4))
            val e = assertFailsWith<LocalWriteException> { f.budgetWriter.deleteExtraIncome(Ids.budgetReceiptRowId(i5, DEC)) }
            assertEquals(ErrorCode.ENTITY_NOT_FOUND, e.code, "a planned income is not an extra one")
        }

    @Test
    fun `BUD-012 fund, unfund and fund all`() =
        runTest {
            setUp()
            f.budgetWriter.fund(DEC, i5, food, 20_000)
            assertEquals(BudgetFundingData(DEC, i5, food, 20_000, OLGA, now), funding(i5, food))

            f.loginAs(DIMA, *BUDGET_MANAGER)
            f.budgetWriter.unfund(DEC, i5, food)
            assertEquals(BudgetFundingData(DEC, i5, food, null, OLGA, now), funding(i5, food), "who ticked it is kept")
            assertEquals(setOf("amount"), f.fields(1).keys)
            f.budgetWriter.unfund(DEC, i5, car)
            assertEquals(2, f.outbox().size, "not ticked, nothing to untick")

            f.budgetWriter.fund(DEC, i5, car, 9_000)
            f.budgetWriter.fundAll(DEC, i5)
            assertEquals(
                listOf(food, utilities, mortgage),
                f.outbox().drop(3).map { f.row<BudgetFundingData>(EntityTypes.BUDGET_FUNDING, it.entity_id)!!.categoryId },
                "unticked rows of used categories in table order",
            )
            assertEquals(27_000, funding(i5, food)!!.amount)
            assertEquals(9_000, funding(i5, car)!!.amount, "a ticked row keeps its amount")
            assertNull(funding(i5, old), "the unused category is not ticked")
            assertEquals(DIMA, funding(i5, mortgage)!!.fundedBy)
            val income = f.repository.observeIncome(DEC, IncomeKey.Planned(i5)).first()!!
            assertEquals(4, income.summary.fundedCount)
            assertEquals(f.fields(3)["fundedBy"]?.jsonPrimitive?.content, DIMA)
        }

    @Test
    fun `BUD-001 a category or income is used by earlier versions, month copies, receipts and ticks`() =
        runTest {
            setUp()
            val newCategory = Ids.newId()
            val newIncome = Ids.newId()
            assertTrue(f.repository.isCategoryUsed(food, before = DEC))
            assertFalse(f.repository.isCategoryUsed(food, before = NOV), "only the version being edited has it")
            assertFalse(f.repository.isCategoryUsed(newCategory, before = DEC))
            assertTrue(f.repository.isIncomeUsed(i5, before = DEC))
            assertFalse(f.repository.isIncomeUsed(newIncome, before = DEC))

            val withNew =
                Notebook.table.copy(
                    categories =
                        Notebook.table.categories + Notebook.table.categories[0].copy(id = newCategory, name = "Новое"),
                )
            f.budgetWriter.editMonth(DEC, withNew)
            assertTrue(f.repository.isCategoryUsed(newCategory, before = NOV), "a month copy has it")

            f.budgetWriter.fund(JAN, newIncome, Ids.newId(), 1)
            assertTrue(f.repository.isIncomeUsed(newIncome, before = NOV), "a tick has it")
            val other = Ids.newId()
            f.budgetWriter.recordIncome(DEC, other, 1, LocalDate(2026, 12, 1))
            assertTrue(f.repository.isIncomeUsed(other, before = NOV), "a receipt has it")
        }

    @Test
    fun `BUD-020 the views roll over at midnight in the household zone`() =
        runTest {
            setUp()
            assertEquals(
                listOf(DEC, NOV),
                f.repository
                    .observeMonths()
                    .first()
                    .map { it.month },
            )
            f.setNow(Instant.parse("2026-12-31T21:00:00Z"))
            assertEquals(
                listOf(JAN, DEC, NOV),
                f.repository
                    .observeMonths()
                    .first()
                    .map { it.month },
            )
        }
}
