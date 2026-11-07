package app.lares.client.data.budget

import app.lares.client.api.LaresApi
import app.lares.client.api.createHttpClient
import app.lares.client.auth.InMemoryTokenStore
import app.lares.client.auth.LocalSession
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MemberView
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.tasks.ANYA
import app.lares.client.data.tasks.DIMA
import app.lares.client.data.tasks.OLGA
import app.lares.client.data.tasks.TestClock
import app.lares.client.platform.InMemoryServerStore
import app.lares.client.sync.EntityAdapter
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.Materializer
import app.lares.client.sync.SyncEngine
import app.lares.client.sync.defaultEntityAdapters
import app.lares.client.sync.testLocalDb
import app.lares.contract.Ids
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.BudgetCategory
import app.lares.contract.entities.BudgetCell
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetIncome
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.entities.BudgetTable
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColor
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.YearMonth
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

val BUDGET_MANAGER = arrayOf(Permission.BUDGET_VIEW, Permission.BUDGET_MANAGE)
val BUDGET_VIEWER = arrayOf(Permission.BUDGET_VIEW)

const val HOUSEHOLD = "01928c4e-0000-7000-8000-0000000000f1"

val NOV = YearMonth(2026, 11)
val DEC = YearMonth(2026, 12)
val JAN = YearMonth(2027, 1)

/**
 * The customer's notebook (budget spec § Scenarios), as in the contract tests of M5-01: 346 000 a month in four
 * incomes, every row and column adds up, plus one unused category with a leftover cell.
 */
object Notebook {
    fun id(n: Int): String = "0192f3a4-0000-7000-8000-" + n.toString().padStart(12, '0')

    val i5 = id(105)
    val i10 = id(110)
    val i20 = id(120)
    val i25 = id(125)

    val food = id(1)
    val car = id(2)
    val utilities = id(3)
    val mortgage = id(4)
    val safe = id(5)
    val vacation = id(6)
    val gifts = id(7)
    val savings = id(8)
    val old = id(9)

    val table =
        BudgetTable(
            categories =
                listOf(
                    BudgetCategory(food, "Продукты", 60_000),
                    BudgetCategory(car, "Машина", 20_000),
                    BudgetCategory(utilities, "Коммуналка", 25_000),
                    BudgetCategory(mortgage, "Ипотека", 100_000),
                    BudgetCategory(safe, "Сейф", 20_000),
                    BudgetCategory(vacation, "Отпуск", 30_000),
                    BudgetCategory(gifts, "Подарки", 10_000),
                    BudgetCategory(savings, "Накопления", 81_000),
                    BudgetCategory(old, "Кружок", 5_000, unused = true),
                ),
            incomes =
                listOf(
                    BudgetIncome(i5, 5, 162_000),
                    BudgetIncome(i20, 20, 160_000),
                    BudgetIncome(i10, 10, 12_000),
                    BudgetIncome(i25, 25, 12_000),
                ),
            cells =
                listOf(
                    BudgetCell(food, i5, 27_000),
                    BudgetCell(food, i10, 2_000),
                    BudgetCell(food, i20, 29_000),
                    BudgetCell(food, i25, 2_000),
                    BudgetCell(car, i5, 10_000),
                    BudgetCell(car, i20, 10_000),
                    BudgetCell(utilities, i5, 25_000),
                    BudgetCell(mortgage, i5, 100_000),
                    BudgetCell(safe, i10, 10_000),
                    BudgetCell(safe, i25, 10_000),
                    BudgetCell(vacation, i20, 30_000),
                    BudgetCell(gifts, i20, 10_000),
                    BudgetCell(savings, i20, 81_000),
                    BudgetCell(old, i5, 5_000),
                ),
        )

    /** The notebook with food raised to 65 000 (5 000 more from the 20th, taken from savings). */
    val raised =
        table.copy(
            categories =
                table.categories.map {
                    if (it.id ==
                        food
                    ) {
                        it.copy(amount = 65_000)
                    } else if (it.id == savings) {
                        it.copy(amount = 76_000)
                    } else {
                        it
                    }
                },
            cells =
                table.cells.map {
                    when {
                        it.categoryId == food && it.incomeId == i20 -> it.copy(amount = 34_000)
                        it.categoryId == savings -> it.copy(amount = 76_000)
                        else -> it
                    }
                },
        )
}

val MEMBERS =
    listOf(
        MemberView(OLGA, MemberData("p-olga", null, null, false), "Ольга", PaletteColor.RED),
        MemberView(DIMA, MemberData("p-dima", null, null, false), "Дмитрий", PaletteColor.BLUE),
    )

fun plan(
    from: YearMonth,
    table: BudgetTable = Notebook.table,
    by: String = OLGA,
    at: String = "2026-10-01T10:00:00Z",
) = BudgetPlanData(from, table, by, Instant.parse(at))

/**
 * One offline client core for budget tests, like `ShoppingFixture`, in the household zone Europe/Moscow. Starts on
 * Thursday 2026-12-10 12:00 in Moscow (09:00 UTC).
 */
class BudgetFixture(
    start: Instant = Instant.parse("2026-12-10T09:00:00Z"),
) {
    val clock = TestClock(start)
    val localDb = testLocalDb()
    val adapters = defaultEntityAdapters(clock)
    private val api =
        LaresApi(
            createHttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
            InMemoryServerStore("http://family.test"),
            InMemoryTokenStore(),
            clock,
        )
    private val engine = SyncEngine(api, localDb, adapters, Materializer(adapters), clock, CoroutineScope(Job()))
    val writer = LocalWriter(localDb, adapters, clock, engine)
    val budgetWriter = BudgetWriter(writer, localDb, clock)
    val ticks = MutableStateFlow(start)
    val repository =
        BudgetRepository(localDb, MembersRepository(localDb), HouseholdRepository(localDb), PermissionsRepository(localDb), clock, ticks)

    fun setNow(now: Instant) {
        clock.now = now
        ticks.value = now
    }

    suspend fun family() =
        localDb.transaction {
            householdQueries.upsert(HOUSEHOLD, "Петровы", "Europe/Moscow", "RUB", "ru")
            listOf(OLGA to "Ольга", DIMA to "Дмитрий", ANYA to "Аня").forEachIndexed { i, (id, name) ->
                personQueries.upsert("person-$i", name, null, null, null, null, null, "[]", null)
                memberQueries.upsert(id, "person-$i", null, null, 0)
            }
        }

    suspend fun loginAs(
        memberId: String,
        vararg permissions: Permission,
    ) = localDb.transaction {
        LocalSession.save(
            this,
            Me("u", memberId, HOUSEHOLD, "Петровы", "p", "Me", "me", true, isAdmin = false, permissions = permissions.toSet()),
        )
    }

    /** Rows as if they had come from the server. */
    suspend fun putPlan(data: BudgetPlanData) = put(EntityTypes.BUDGET_PLAN, Ids.budgetPlanRowId(HOUSEHOLD, data.effectiveFrom), data)

    suspend fun putMonth(data: BudgetMonthData) = put(EntityTypes.BUDGET_MONTH, Ids.budgetMonthRowId(HOUSEHOLD, data.month), data)

    suspend fun putReceipt(data: BudgetReceiptData) =
        put(EntityTypes.BUDGET_RECEIPT, Ids.budgetReceiptRowId(data.incomeId!!, data.month), data)

    suspend fun putFunding(data: BudgetFundingData) =
        put(EntityTypes.BUDGET_FUNDING, Ids.budgetFundingRowId(data.incomeId, data.categoryId, data.month), data)

    private suspend fun <T : Any> put(
        type: String,
        id: String,
        data: T,
    ) = localDb.transaction {
        @Suppress("UNCHECKED_CAST")
        (adapters[type] as EntityAdapter<T>).upsertTyped(this, id, data)
    }

    suspend fun <T : Any> row(
        type: String,
        id: String,
    ): T? =
        localDb.read {
            @Suppress("UNCHECKED_CAST")
            (adapters[type] as EntityAdapter<T>).selectTyped(this, id)
        }

    suspend fun outbox() = localDb.read { sync_outboxQueries.peek(100).executeAsList() }

    suspend fun fields(index: Int): JsonObject = LaresJson.parseToJsonElement(outbox()[index].fields!!).jsonObject
}
