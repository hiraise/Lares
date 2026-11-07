package app.lares.client.data.budget

import app.lares.client.data.budget.Notebook.food
import app.lares.client.data.budget.Notebook.i5
import app.lares.client.data.tasks.ANYA
import app.lares.client.data.tasks.DIMA
import app.lares.client.data.tasks.OLGA
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalValidationException
import app.lares.contract.Ids
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Each server rule of the budget handlers (M5-02) once, through `LocalWriter`. */
class BudgetAdaptersTest {
    private val f = BudgetFixture()
    private val now = Instant.parse("2026-12-10T09:00:00Z")
    private val receiptId = Ids.budgetReceiptRowId(i5, DEC)
    private val fundingId = Ids.budgetFundingRowId(i5, food, DEC)

    private fun <T> json(
        serializer: KSerializer<T>,
        data: T,
    ): JsonObject = LaresJson.encodeToJsonElement(serializer, data).jsonObject

    private suspend fun upsert(
        type: String,
        id: String,
        fields: JsonObject,
    ) = f.writer.upsert(type, id, fields)

    private suspend fun fundingAs(
        by: String,
        id: String = fundingId,
        data: BudgetFundingData = BudgetFundingData(DEC, i5, food, 27_000, by, now),
    ) = upsert(EntityTypes.BUDGET_FUNDING, id, json(BudgetFundingData.serializer(), data))

    private suspend fun login(
        member: String = OLGA,
        vararg permissions: app.lares.contract.permissions.Permission = BUDGET_MANAGER,
    ) {
        f.family()
        f.loginAs(member, *permissions)
    }

    @Test
    fun `BUD-015 only budget manage writes`() =
        runTest {
            login(ANYA, *BUDGET_VIEWER)
            assertFailsWith<LocalForbiddenException> { fundingAs(ANYA) }
            assertFailsWith<LocalForbiddenException> { f.budgetWriter.savePlan(Notebook.table, DEC) }
            assertEquals(emptyList(), f.outbox())
        }

    @Test
    fun `BUD-004 past plan versions are read-only, with a day of grace`() =
        runTest {
            login()
            val e = assertFailsWith<LocalValidationException> { f.budgetWriter.savePlan(Notebook.table, NOV) }
            assertEquals(mapOf("effectiveFrom" to FieldErrorCode.OUT_OF_RANGE), e.fields)

            f.setNow(Instant.parse("2026-11-30T21:30:00Z")) // 00:30 on 1 December in Moscow
            f.budgetWriter.savePlan(Notebook.table, NOV)
            assertEquals(1, f.outbox().size)
        }

    @Test
    fun `derived ids and immutable fields are checked`() =
        runTest {
            login()
            val e = assertFailsWith<LocalValidationException> { fundingAs(OLGA, id = Ids.budgetFundingRowId(i5, food, JAN)) }
            assertEquals(mapOf("id" to FieldErrorCode.INVALID_FORMAT), e.fields)
            val month = BudgetMonthData(DEC, null, OLGA)
            assertFailsWith<LocalValidationException> {
                upsert(EntityTypes.BUDGET_MONTH, Ids.budgetMonthRowId(Ids.newId(), DEC), json(BudgetMonthData.serializer(), month))
            }

            fundingAs(OLGA)
            val moved =
                assertFailsWith<LocalValidationException> {
                    upsert(
                        EntityTypes.BUDGET_FUNDING,
                        fundingId,
                        JsonObject(
                            mapOf("month" to JsonPrimitive("2027-01")),
                        ),
                    )
                }
            assertEquals(mapOf("month" to FieldErrorCode.INVALID_FORMAT), moved.fields)
            val upper =
                assertFailsWith<LocalValidationException> {
                    upsert(
                        EntityTypes.BUDGET_FUNDING,
                        fundingId,
                        JsonObject(
                            mapOf("categoryId" to JsonPrimitive(food.uppercase())),
                        ),
                    )
                }
            assertEquals(setOf("categoryId"), upper.fields.keys)
        }

    @Test
    fun `rows carry their writer - updatedBy and fundedBy only as oneself`() =
        runTest {
            login()
            val receipt = BudgetReceiptData(DEC, i5, amount = 1, date = LocalDate(2026, 12, 1), updatedBy = DIMA)
            assertFailsWith<LocalForbiddenException> {
                upsert(
                    EntityTypes.BUDGET_RECEIPT,
                    receiptId,
                    json(BudgetReceiptData.serializer(), receipt),
                )
            }
            assertFailsWith<LocalForbiddenException> { fundingAs(DIMA) }

            // Unticking another member's tick keeps their name and is allowed.
            f.putFunding(BudgetFundingData(DEC, i5, food, 27_000, DIMA, now))
            f.budgetWriter.unfund(DEC, i5, food)
            assertEquals(1, f.outbox().size)
        }

    @Test
    fun `BUD-011 only extra incomes are deleted, and their ids are random UUIDv7`() =
        runTest {
            login()
            f.budgetWriter.recordIncome(DEC, i5, 150_000, LocalDate(2026, 12, 4))
            assertFailsWith<LocalForbiddenException> { f.writer.delete(EntityTypes.BUDGET_RECEIPT, receiptId) }

            val extra = BudgetReceiptData(DEC, null, "Премия", 1, LocalDate(2026, 12, 1), OLGA)
            val e =
                assertFailsWith<LocalValidationException> {
                    upsert(EntityTypes.BUDGET_RECEIPT, receiptId.replaceRange(14, 15, "4"), json(BudgetReceiptData.serializer(), extra))
                }
            assertEquals(mapOf("id" to FieldErrorCode.INVALID_FORMAT), e.fields)
        }

    @Test
    fun `a time far ahead is refused`() =
        runTest {
            login()
            val e =
                assertFailsWith<LocalValidationException> {
                    fundingAs(
                        OLGA,
                        data =
                            BudgetFundingData(
                                DEC,
                                i5,
                                food,
                                1,
                                OLGA,
                                now + 2.days,
                            ),
                    )
                }
            assertEquals(mapOf("fundedAt" to FieldErrorCode.OUT_OF_RANGE), e.fields)
        }
}
