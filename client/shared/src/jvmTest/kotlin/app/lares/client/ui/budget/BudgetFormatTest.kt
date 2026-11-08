package app.lares.client.ui.budget

import app.lares.client.data.budget.FundingRow
import app.lares.client.resources.Res
import app.lares.client.resources.bud_amount_of
import app.lares.client.resources.bud_hero_done
import app.lares.client.resources.bud_hero_expected
import app.lares.client.resources.bud_hero_free
import app.lares.client.resources.bud_hero_over
import app.lares.client.resources.bud_hero_shortfall
import app.lares.client.ui.common.load
import app.lares.client.ui.common.raw
import app.lares.client.ui.common.uiText
import app.lares.contract.budget.IncomeSummary
import app.lares.contract.budget.MonthSummary
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.YearMonth
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BudgetFormatTest {
    @AfterTest
    fun english() = Locale.setDefault(Locale.ENGLISH)

    @Test
    fun `BUD-014 amounts are grouped by a narrow no-break space, negative with a minus, then the ruble sign`() {
        assertEquals("0", BudgetFormat.digits(0))
        assertEquals("999", BudgetFormat.digits(999))
        assertEquals("1 234", BudgetFormat.digits(1_234))
        assertEquals("346 000", BudgetFormat.digits(346_000))
        assertEquals("1 234 567", BudgetFormat.digits(1_234_567))
        assertEquals("−3 000", BudgetFormat.digits(-3_000))
        assertEquals("1 234 ₽", BudgetFormat.amount(1_234))
    }

    @Test
    fun `BUD-011 amount input keeps at most ten digits, empty is not recorded`() {
        assertEquals("150000", BudgetFormat.input("150 000 ₽"))
        assertEquals("123456789", BudgetFormat.input("123456789012"), "nine digits always fit the 1 000 000 000 limit")
        assertEquals("", BudgetFormat.input("-,."))
        assertNull(BudgetFormat.parse(""))
        assertEquals(150_000L, BudgetFormat.parse("150000"))
    }

    private fun summary(
        expected: Long = 346_000,
        actual: Long = 0,
        transferred: Long = 0,
        shortfall: Long = 0,
        recorded: Boolean = actual > 0,
    ) = MonthSummary(
        incomes =
            listOf(
                IncomeSummary("i5", null, null, null, expected, if (recorded) actual else null, null, transferred, 0, 4),
            ),
        categories = emptyList(),
        expected = expected,
        actual = actual,
        transferred = transferred,
        shortfall = shortfall,
        free = actual - transferred,
    )

    private fun amount(value: Long) = raw(BudgetFormat.amount(value))

    @Test
    fun `BUD-014 hero precedence - free, shortfall, over-transferred, expected, all distributed`() {
        assertEquals(
            uiText(Res.string.bud_hero_free, amount(12_000)),
            BudgetLogic.hero(summary(actual = 150_000, transferred = 138_000, shortfall = 12_000)),
        )
        assertEquals(
            uiText(Res.string.bud_hero_shortfall, amount(12_000)),
            BudgetLogic.hero(summary(actual = 150_000, transferred = 150_000, shortfall = 12_000)),
        )
        assertEquals(uiText(Res.string.bud_hero_over, amount(3_000)), BudgetLogic.hero(summary(actual = 150_000, transferred = 153_000)))
        assertEquals(uiText(Res.string.bud_hero_expected, amount(346_000)), BudgetLogic.hero(summary()))
        assertEquals(uiText(Res.string.bud_hero_done), BudgetLogic.hero(summary(actual = 162_000, transferred = 162_000)))
    }

    @Test
    fun `BUD-012 a changed transfer reads 20 000 of 27 000 in both languages`() {
        val row = FundingRow("food", "Продукты", 27_000, 20_000, "Ольга", false)
        assertEquals(uiText(Res.string.bud_amount_of, raw("20 000"), raw("27 000")), BudgetLogic.fundingAmount(row))
        assertEquals(raw(BudgetFormat.amount(27_000)), BudgetLogic.fundingAmount(row.copy(funding = 27_000)))
        runBlocking {
            Locale.setDefault(Locale.forLanguageTag("ru"))
            assertEquals("20 000 из 27 000", BudgetLogic.fundingAmount(row).load())
            assertEquals("Декабрь 2026", BudgetLogic.monthTitle(YearMonth(2026, 12)).load())
            Locale.setDefault(Locale.ENGLISH)
            assertEquals("20 000 of 27 000", BudgetLogic.fundingAmount(row).load())
        }
    }
}
