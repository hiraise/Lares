package app.lares.server.budget

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.sync.Change
import app.lares.contract.sync.EntityTypes
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.tasks.assertField
import app.lares.server.testing.ADMIN_LOGIN
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.ADULTS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertRejected
import app.lares.server.testing.budgetFunding
import app.lares.server.testing.budgetMonth
import app.lares.server.testing.budgetReceipt
import app.lares.server.testing.dataAs
import app.lares.server.testing.delete
import app.lares.server.testing.fields
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.lastSeq
import app.lares.server.testing.login
import app.lares.server.testing.memberSession
import app.lares.server.testing.newBudgetPlan
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.sampleBudgetTable
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upsert
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Budget sync on the server (M5-02, architecture/budget.md § Server). The test clock starts on 2026-10-22, Moscow time. */
class BudgetSyncTest {
    private val october = YearMonth(2026, 10)
    private val september = YearMonth(2026, 9)

    /** A fresh admin token after the test clock jumped past the access token's lifetime. */
    private suspend fun HttpClient.adminToken(): String = login(ADMIN_LOGIN, ADMIN_PASSWORD).body<AuthResult>().accessToken

    private fun List<Change>.live(type: String) = filter { it.type == type && !it.deleted }

    /** BUD-004, BUD-011…BUD-013: every type is stored and pulled back exactly as written; an unchanged upsert is a no-op. */
    @Test
    fun bud015EachTypeRoundTrips() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val household = admin.me.householdId
            val table =
                sampleBudgetTable().let {
                    it.copy(
                        categories = it.categories + BudgetCategory(Ids.newId(), "Old", 0, unused = true),
                    )
                }
            val salary = table.incomes[0].id
            val food = table.categories[0].id
            val mutations =
                listOf(
                    deps.newBudgetPlan(household, october, me, table, Instant.parse("2026-10-22T09:59:00.123Z")),
                    deps.newBudgetPlan(household, YearMonth(2026, 12), me, sampleBudgetTable()),
                    deps.budgetMonth(household, october, me, table),
                    deps.budgetMonth(household, YearMonth(2026, 11), me, null),
                    deps.budgetReceipt(october, salary, me, 60_000, LocalDate(2026, 10, 10)),
                    deps.budgetReceipt(october, null, me, 5_000, LocalDate(2026, 10, 15), mapOf("label" to "Bonus")),
                    deps.budgetFunding(october, salary, food, 10_000, me),
                )
            for (m in mutations) client.pushOne(token, m).assertApplied()

            val changes = client.pullAll(token).second
            for (m in mutations) {
                val pulled = changes.single { it.id == m.id }
                assertEquals(m.type, pulled.type)
                // explicitNulls = false: null fields come back absent.
                assertEquals<JsonElement?>(JsonObject(m.fields!!.filterValues { it != JsonNull }), pulled.data, m.type)
            }
            for (m in mutations) assertNull(client.pushOne(token, m.copy(mutationId = Ids.newId())).assertApplied(), "no-op ${m.type}")
        }

    /** BUD-004: a version from the current month is accepted, a past one is read-only, with a day of grace after midnight. */
    @Test
    fun bud004PastVersionsAreReadOnly() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val household = admin.me.householdId
            client.pushOne(token, deps.newBudgetPlan(household, october, me)).assertApplied()
            client.pushOne(token, deps.newBudgetPlan(household, september, me)).assertField("effectiveFrom", FieldErrorCode.OUT_OF_RANGE)

            // 00:30 on 1 November in Moscow: October is still writable for a day.
            deps.testClock.current = Instant.parse("2026-10-31T21:30:00Z")
            val grace = deps.newBudgetPlan(household, october, me)
            client.pushOne(client.adminToken(), grace).assertApplied()
            deps.testClock.current = Instant.parse("2026-11-01T21:30:00Z")
            val later = client.adminToken()
            client.pushOne(later, deps.newBudgetPlan(household, october, me)).assertField("effectiveFrom", FieldErrorCode.OUT_OF_RANGE)
            // Even an unchanged upsert of a past version is rejected.
            client.pushOne(later, grace.copy(mutationId = Ids.newId())).assertField("effectiveFrom", FieldErrorCode.OUT_OF_RANGE)
        }

    /** Derived ids, immutable fields and field rules (architecture/budget.md § Validation). */
    @Test
    fun bud015ServerChecks() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val household = admin.me.householdId
            val incomeId = Ids.newId()
            val categoryId = Ids.newId()

            client
                .pushOne(
                    token,
                    deps.newBudgetPlan(household, october, me, id = Ids.newId()),
                ).assertField("id", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(
                    token,
                    deps.budgetMonth(household, october, me, id = Ids.newId()),
                ).assertField("id", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(
                    token,
                    deps.budgetFunding(october, incomeId, categoryId, 1, me, id = Ids.budgetFundingRowId(incomeId, categoryId, september)),
                ).assertField("id", FieldErrorCode.INVALID_FORMAT)
            // An extra income needs a UUIDv7; a planned one its derived id.
            client
                .pushOne(token, deps.budgetReceipt(october, null, me, id = Ids.budgetReceiptRowId(incomeId, october)))
                .assertField("id", FieldErrorCode.INVALID_FORMAT)
            client
                .pushOne(
                    token,
                    deps.budgetReceipt(october, incomeId, me, id = Ids.newId()),
                ).assertField("id", FieldErrorCode.INVALID_FORMAT)

            val month = deps.budgetMonth(household, YearMonth(2026, 11), me)
            client.pushOne(token, month).assertApplied()
            client
                .pushOne(token, deps.upsert(EntityTypes.BUDGET_MONTH, month.id, fields("month" to "2026-12")))
                .assertField("month", FieldErrorCode.INVALID_FORMAT)

            val table = sampleBudgetTable()
            val duplicate = table.copy(categories = table.categories + table.categories[0].copy(id = Ids.newId(), name = "food"))
            client.pushOne(token, deps.newBudgetPlan(household, october, me, duplicate)).assertField("table", FieldErrorCode.INVALID_FORMAT)

            client
                .pushOne(token, deps.budgetReceipt(october, incomeId, me, amount = 100))
                .assertField("date", FieldErrorCode.REQUIRED)
            client
                .pushOne(token, deps.budgetFunding(october, incomeId, categoryId, 1, me, fundedAt = deps.clock.now() + 2.days))
                .assertField("fundedAt", FieldErrorCode.OUT_OF_RANGE)
            client
                .pushOne(token, deps.newBudgetPlan(household, october, me, updatedAt = deps.clock.now() + 2.days))
                .assertField("updatedAt", FieldErrorCode.OUT_OF_RANGE)
        }

    /** BUD-011: planned receipts are never deleted; an extra income is, and stays deleted. */
    @Test
    fun bud011OnlyExtraIncomesAreDeleted() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val me = admin.me.memberId
            val planned = deps.budgetReceipt(october, Ids.newId(), me, 60_000, LocalDate(2026, 10, 10))
            val extra = deps.budgetReceipt(october, null, me, 5_000, LocalDate(2026, 10, 15))
            client.pushOne(token, planned).assertApplied()
            client.pushOne(token, extra).assertApplied()

            val before = client.pullAll(token).first

            client.pushOne(token, deps.delete(EntityTypes.BUDGET_RECEIPT, planned.id)).assertRejected(ErrorCode.FORBIDDEN)
            client.pushOne(token, deps.delete(EntityTypes.BUDGET_RECEIPT, extra.id)).assertApplied()
            assertEquals(
                listOf(extra.id),
                client
                    .pullAll(token, before)
                    .second
                    .filter { it.deleted }
                    .map { it.id },
            )
            assertEquals(
                listOf(planned.id),
                client
                    .pullAll(token)
                    .second
                    .live(EntityTypes.BUDGET_RECEIPT)
                    .map { it.id },
            )
            client.pushOne(token, extra.copy(mutationId = Ids.newId())).assertRejected(ErrorCode.ENTITY_DELETED)
        }

    /** BUD-012: unticking keeps who ticked; two members ticking one category write one row, the later amount wins. */
    @Test
    fun bud012TicksMerge() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val adult = client.memberSession(admin.accessToken, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            val salary = Ids.newId()
            val food = Ids.newId()
            val rent = Ids.newId()

            client.pushOne(admin.accessToken, deps.budgetFunding(october, salary, food, 10_000, admin.me.memberId)).assertApplied()
            client.pushOne(adult.accessToken, deps.budgetFunding(october, salary, rent, 50_000, adult.me.memberId)).assertApplied()
            client.pushOne(adult.accessToken, deps.budgetFunding(october, salary, food, 12_000, adult.me.memberId)).assertApplied()
            var fundings = client.pullAll(admin.accessToken).second.live(EntityTypes.BUDGET_FUNDING)
            assertEquals(2, fundings.size)
            val foodTick = fundings.single { it.id == Ids.budgetFundingRowId(salary, food, october) }.dataAs<BudgetFundingData>()
            assertEquals(12_000L, foodTick.amount)
            assertEquals(adult.me.memberId, foodTick.fundedBy)

            // The admin unticks the adult's tick: the amount goes, fundedBy stays.
            client.pushOne(admin.accessToken, deps.budgetFunding(october, salary, food, null, admin.me.memberId)).assertApplied()
            fundings = client.pullAll(admin.accessToken).second.live(EntityTypes.BUDGET_FUNDING)
            val unticked = fundings.single { it.id == Ids.budgetFundingRowId(salary, food, october) }.dataAs<BudgetFundingData>()
            assertNull(unticked.amount)
            assertEquals(adult.me.memberId, unticked.fundedBy)
        }

    /** Housekeeping purges old tombstones of extra incomes and raises the purge horizon. */
    @Test
    fun receiptTombstonesArePurged() =
        testApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val token = admin.accessToken
            val extra = deps.budgetReceipt(october, null, admin.me.memberId)
            client.pushOne(token, extra).assertApplied()
            client.pushOne(token, deps.delete(EntityTypes.BUDGET_RECEIPT, extra.id)).assertApplied()
            val lastSeq = deps.lastSeq(admin.householdUuid)

            assertEquals(0, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            deps.testClock.advance(91.days)
            client.login(ADMIN_LOGIN, ADMIN_PASSWORD)
            assertEquals(1, deps.syncHousekeeping.purgeTombstones(deps.clock.now()))
            assertEquals(lastSeq, deps.db.dbQuery { HouseholdSyncStateTable.selectAll().single()[HouseholdSyncStateTable.purgeHorizonSeq] })
            assertNull(deps.db.dbQuery { BudgetRepository.loadReceipt(UUID.fromString(extra.id)) })
        }
}
