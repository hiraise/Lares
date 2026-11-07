package app.lares.contract.validation

import app.lares.contract.api.FieldErrorCode
import app.lares.contract.budget.Notebook
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
import kotlin.test.assertNull
import kotlin.time.Instant

class BudgetValidatorsTest {
    private val december = YearMonth(2026, 12)
    private val at = Instant.parse("2026-11-07T10:00:00Z")
    private val table = Notebook.table
    private val max = Validators.BUDGET_AMOUNT_MAX

    private fun check(table: BudgetTable) = Validators.budgetTable(table)

    private fun withCategory(category: BudgetCategory) = table.copy(categories = table.categories + category)

    private fun withIncome(income: BudgetIncome) = table.copy(incomes = table.incomes + income)

    private fun withCell(cell: BudgetCell) = table.copy(cells = table.cells + cell)

    @Test
    fun `BUD-001 BUD-002 BUD-003 the notebook and an empty table are valid`() {
        assertNull(check(table))
        assertNull(check(BudgetTable()))
        assertEquals(emptyMap(), Validators.budgetPlan(BudgetPlanData(december, table, "m1", at)))
    }

    @Test
    fun `BUD-001 BUD-002 too many categories or incomes`() {
        val categories = (1..101).map { BudgetCategory(Notebook.id(1000 + it), "К$it", 0) }
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(BudgetTable(categories = categories)))
        assertNull(check(BudgetTable(categories = categories.take(100))))
        val incomes = (1..21).map { BudgetIncome(Notebook.id(2000 + it), it, 0) }
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(BudgetTable(incomes = incomes)))
        assertNull(check(BudgetTable(incomes = incomes.take(20))))
    }

    @Test
    fun `BUD-001 names are required, at most 60, unique in any case`() {
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withCategory(BudgetCategory(Notebook.id(50), "   ", 0))))
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withCategory(BudgetCategory(Notebook.id(50), "а".repeat(61), 0))))
        assertNull(check(withCategory(BudgetCategory(Notebook.id(50), "а".repeat(60), 0))))
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withCategory(BudgetCategory(Notebook.id(50), "ПРОДУКТЫ", 0))))
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withCategory(BudgetCategory(Notebook.id(50), " продукты ", 0))))
    }

    @Test
    fun `BUD-001 BUD-002 ids are unique lowercase UUIDs`() {
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withCategory(BudgetCategory(Notebook.food, "Еда", 0))))
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withIncome(BudgetIncome(Notebook.i5, 1, 0))))
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withCategory(BudgetCategory("c1", "Еда", 0))))
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withIncome(BudgetIncome(Notebook.id(50).uppercase(), 1, 0))))
    }

    @Test
    fun `BUD-002 BUD-003 days and amounts in range`() {
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(withIncome(BudgetIncome(Notebook.id(50), 0, 0))))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(withIncome(BudgetIncome(Notebook.id(50), 32, 0))))
        assertNull(check(withIncome(BudgetIncome(Notebook.id(50), 31, max))))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(withIncome(BudgetIncome(Notebook.id(50), 1, -1))))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(withIncome(BudgetIncome(Notebook.id(50), 1, max + 1))))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(withCategory(BudgetCategory(Notebook.id(50), "Еда", -1))))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(withCategory(BudgetCategory(Notebook.id(50), "Еда", max + 1))))
        assertNull(check(withCategory(BudgetCategory(Notebook.id(50), "Еда", max))))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(withCell(BudgetCell(Notebook.gifts, Notebook.i5, 0))))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(withCell(BudgetCell(Notebook.gifts, Notebook.i5, max + 1))))
    }

    @Test
    fun `BUD-003 cells of a known pair, one per pair`() {
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withCell(BudgetCell(Notebook.gifts, Notebook.id(50), 1))))
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withCell(BudgetCell(Notebook.id(50), Notebook.i5, 1))))
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(withCell(BudgetCell(Notebook.food, Notebook.i5, 1))))
    }

    @Test
    fun `BUD-003 the first problem in the listed order wins`() {
        val tooMany = (1..101).map { BudgetCategory(Notebook.id(1000 + it), "", -1) }
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(BudgetTable(categories = tooMany)))
        val badNameAndDay = withCategory(BudgetCategory(Notebook.id(50), "", 0)).copy(incomes = listOf(BudgetIncome(Notebook.i5, 0, 0)))
        assertEquals(FieldErrorCode.INVALID_FORMAT, check(badNameAndDay))
        val badAmountAndCell = withCell(BudgetCell(Notebook.id(50), Notebook.i5, -5))
        assertEquals(FieldErrorCode.OUT_OF_RANGE, check(badAmountAndCell))
    }

    @Test
    fun `BUD-004 BUD-013 plan and month fields`() {
        val plan = BudgetPlanData(december, table, "m1", at)
        assertEquals(
            mapOf("effectiveFrom" to FieldErrorCode.OUT_OF_RANGE, "table" to FieldErrorCode.INVALID_FORMAT),
            Validators.budgetPlan(
                plan.copy(effectiveFrom = YearMonth(1999, 12), table = withCell(BudgetCell(Notebook.food, Notebook.i5, 1))),
            ),
        )
        assertEquals(
            mapOf("effectiveFrom" to FieldErrorCode.OUT_OF_RANGE),
            Validators.budgetPlan(plan.copy(effectiveFrom = YearMonth(2101, 1))),
        )
        assertEquals(emptyMap(), Validators.budgetPlan(plan.copy(effectiveFrom = YearMonth(2000, 1))))
        val month = BudgetMonthData(december, updatedBy = "m1")
        assertEquals(emptyMap(), Validators.budgetMonth(month))
        assertEquals(emptyMap(), Validators.budgetMonth(month.copy(table = table)))
        assertEquals(mapOf("month" to FieldErrorCode.OUT_OF_RANGE), Validators.budgetMonth(month.copy(month = YearMonth(2101, 1))))
        assertEquals(
            mapOf("table" to FieldErrorCode.OUT_OF_RANGE),
            Validators.budgetMonth(month.copy(table = withIncome(BudgetIncome(Notebook.id(50), 32, 0)))),
        )
    }

    @Test
    fun `BUD-011 receipt fields`() {
        val extra = BudgetReceiptData(december, updatedBy = "m1")
        assertEquals(emptyMap(), Validators.budgetReceipt(extra))
        assertEquals(emptyMap(), Validators.budgetReceipt(extra.copy(label = "а".repeat(60), date = LocalDate(2026, 12, 4))))
        assertEquals(mapOf("label" to FieldErrorCode.TOO_LONG), Validators.budgetReceipt(extra.copy(label = "а".repeat(61))))
        assertEquals(
            mapOf("label" to FieldErrorCode.INVALID_FORMAT),
            Validators.budgetReceipt(extra.copy(incomeId = Notebook.i5, label = "Премия")),
        )
        assertEquals(mapOf("date" to FieldErrorCode.REQUIRED), Validators.budgetReceipt(extra.copy(amount = 150_000)))
        val dated = extra.copy(incomeId = Notebook.i5, date = LocalDate(2026, 12, 4))
        assertEquals(emptyMap(), Validators.budgetReceipt(dated.copy(amount = 0)))
        assertEquals(emptyMap(), Validators.budgetReceipt(dated.copy(amount = max)))
        assertEquals(mapOf("amount" to FieldErrorCode.OUT_OF_RANGE), Validators.budgetReceipt(dated.copy(amount = -1)))
        assertEquals(mapOf("amount" to FieldErrorCode.OUT_OF_RANGE), Validators.budgetReceipt(dated.copy(amount = max + 1)))
        assertEquals(mapOf("month" to FieldErrorCode.OUT_OF_RANGE), Validators.budgetReceipt(extra.copy(month = YearMonth(1999, 1))))
    }

    @Test
    fun `BUD-012 funding fields`() {
        val unticked = BudgetFundingData(december, Notebook.i5, Notebook.food)
        assertEquals(emptyMap(), Validators.budgetFunding(unticked))
        assertEquals(emptyMap(), Validators.budgetFunding(unticked.copy(fundedBy = "m1", fundedAt = at)))
        assertEquals(
            mapOf("fundedBy" to FieldErrorCode.REQUIRED, "fundedAt" to FieldErrorCode.REQUIRED),
            Validators.budgetFunding(unticked.copy(amount = 27_000)),
        )
        assertEquals(
            mapOf("fundedAt" to FieldErrorCode.REQUIRED),
            Validators.budgetFunding(unticked.copy(amount = 27_000, fundedBy = "m1")),
        )
        val ticked = unticked.copy(fundedBy = "m1", fundedAt = at)
        assertEquals(emptyMap(), Validators.budgetFunding(ticked.copy(amount = 0)))
        assertEquals(mapOf("amount" to FieldErrorCode.OUT_OF_RANGE), Validators.budgetFunding(ticked.copy(amount = -1)))
        assertEquals(mapOf("amount" to FieldErrorCode.OUT_OF_RANGE), Validators.budgetFunding(ticked.copy(amount = max + 1)))
        assertEquals(mapOf("month" to FieldErrorCode.OUT_OF_RANGE), Validators.budgetFunding(ticked.copy(month = YearMonth(2101, 1))))
    }

    @Test
    fun `BUD-001 BUD-003 BUD-011 normalizers collapse names, drop zero cells, blank labels become null`() {
        val messy =
            BudgetTable(
                categories = listOf(BudgetCategory(Notebook.food, "  Продукты \t и   быт ", 1)),
                incomes = listOf(BudgetIncome(Notebook.i5, 5, 1)),
                cells = listOf(BudgetCell(Notebook.food, Notebook.i5, 0)),
            )
        val normalized = Validators.normalizeBudgetTable(messy)
        assertEquals("Продукты и быт", normalized.categories.single().name)
        assertEquals(emptyList(), normalized.cells)
        assertEquals(normalized, Validators.normalizeBudgetPlan(BudgetPlanData(december, messy, "m1", at)).table)
        assertEquals(normalized, Validators.normalizeBudgetMonth(BudgetMonthData(december, messy, "m1")).table)
        assertNull(Validators.normalizeBudgetMonth(BudgetMonthData(december, updatedBy = "m1")).table)
        val receipt = BudgetReceiptData(december, updatedBy = "m1")
        assertNull(Validators.normalizeBudgetReceipt(receipt.copy(label = "   ")).label)
        assertEquals("Премия", Validators.normalizeBudgetReceipt(receipt.copy(label = " Премия ")).label)
    }
}
