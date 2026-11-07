package app.lares.contract.entities

import app.lares.contract.api.LaresJson
import app.lares.contract.budget.Notebook
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class BudgetEntitiesTest {
    private fun <T> roundTrip(
        serializer: KSerializer<T>,
        value: T,
    ): String {
        val text = LaresJson.encodeToString(serializer, value)
        assertEquals(value, LaresJson.decodeFromString(serializer, text), text)
        return text
    }

    private val december = YearMonth(2026, 12)
    private val at = Instant.parse("2026-11-07T10:15:30Z")

    @Test
    fun `BUD-001 BUD-002 BUD-003 BUD-004 plan round trip with the whole table and the month as an ISO string`() {
        val text = roundTrip(BudgetPlanData.serializer(), BudgetPlanData(december, Notebook.table, "m1", at))
        assertTrue("\"effectiveFrom\":\"2026-12\"" in text, text)
        assertTrue("\"updatedAt\":\"2026-11-07T10:15:30Z\"" in text, text)
        assertTrue("\"unused\":true" in text, text)
        assertTrue("{\"id\":\"${Notebook.i5}\",\"day\":5,\"amount\":162000}" in text, text)
        assertTrue("{\"categoryId\":\"${Notebook.food}\",\"incomeId\":\"${Notebook.i5}\",\"amount\":27000}" in text, text)
    }

    @Test
    fun `BUD-004 an empty plan table decodes`() {
        val json = """{"effectiveFrom":"2026-12","table":{},"updatedBy":"m1","updatedAt":"2026-11-07T10:15:30Z"}"""
        val plan = LaresJson.decodeFromString(BudgetPlanData.serializer(), json)
        assertEquals(BudgetPlanData(december, BudgetTable(), "m1", at), plan)
        val category = LaresJson.decodeFromString(BudgetCategory.serializer(), """{"id":"c1","name":"Еда","amount":5}""")
        assertFalse(category.unused)
    }

    @Test
    fun `BUD-013 month round trip with and without its own table`() {
        val text = roundTrip(BudgetMonthData.serializer(), BudgetMonthData(december, Notebook.table, "m1"))
        assertTrue("\"month\":\"2026-12\"" in text, text)
        val follows = roundTrip(BudgetMonthData.serializer(), BudgetMonthData(december, updatedBy = "m1"))
        assertEquals("""{"month":"2026-12","updatedBy":"m1"}""", follows)
    }

    @Test
    fun `BUD-011 receipt round trip for a planned and an extra income`() {
        val planned =
            roundTrip(
                BudgetReceiptData.serializer(),
                BudgetReceiptData(december, Notebook.i5, amount = 150_000, date = LocalDate(2026, 12, 4), updatedBy = "m1"),
            )
        assertTrue("\"date\":\"2026-12-04\"" in planned && "\"amount\":150000" in planned, planned)
        assertFalse("label" in planned, planned)
        val extra =
            roundTrip(
                BudgetReceiptData.serializer(),
                BudgetReceiptData(december, label = "Премия", amount = 20_000, date = LocalDate(2026, 12, 15), updatedBy = "m2"),
            )
        assertTrue("\"label\":\"Премия\"" in extra, extra)
        assertFalse("incomeId" in extra, extra)
    }

    @Test
    fun `BUD-012 funding round trip, ticked and unticked`() {
        val ticked = BudgetFundingData(december, Notebook.i5, Notebook.food, amount = 20_000, fundedBy = "m1", fundedAt = at)
        val text = roundTrip(BudgetFundingData.serializer(), ticked)
        assertTrue("\"fundedAt\":\"2026-11-07T10:15:30Z\"" in text, text)
        val unticked = roundTrip(BudgetFundingData.serializer(), ticked.copy(amount = null))
        assertFalse("amount" in unticked, unticked)
        assertTrue("\"fundedBy\":\"m1\"" in unticked, unticked)
    }
}
