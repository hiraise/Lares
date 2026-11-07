package app.lares.server.budget

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.CreateGroupRequest
import app.lares.contract.api.admin.GroupCreated
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.Mutation
import app.lares.server.AppDeps
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertRejected
import app.lares.server.testing.budgetFunding
import app.lares.server.testing.budgetMonth
import app.lares.server.testing.budgetReceipt
import app.lares.server.testing.delete
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.memberSession
import app.lares.server.testing.newBudgetPlan
import app.lares.server.testing.pullAll
import app.lares.server.testing.pushOne
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Budget spec § Access: `budget.view` reads, `budget.manage` writes; rows carry only their own writer. */
class BudgetPermissionsTest {
    private val october = YearMonth(2026, 10)
    private val budgetTypes =
        setOf(EntityTypes.BUDGET_PLAN, EntityTypes.BUDGET_MONTH, EntityTypes.BUDGET_RECEIPT, EntityTypes.BUDGET_FUNDING)

    private class Family(
        val admin: AuthResult,
        val adult: AuthResult,
        val kid: AuthResult,
        val viewer: AuthResult,
    )

    private suspend fun HttpClient.family(deps: AppDeps): Family {
        val admin = setup()
        val token = admin.accessToken
        val response = send(HttpMethod.Post, "/api/v1/groups", token, CreateGroupRequest("Viewers", setOf(Permission.BUDGET_VIEW)))
        assertEquals(HttpStatusCode.Created, response.status)
        val viewers = UUID.fromString(response.body<GroupCreated>().groupId)
        return Family(
            admin,
            memberSession(token, "adult", setOf(deps.groupId(admin.householdUuid, ADULTS))),
            memberSession(token, "kid", setOf(deps.groupId(admin.householdUuid, KIDS))),
            memberSession(token, "viewer", setOf(viewers)),
        )
    }

    /** One write of every type with [me] as the writer. */
    private fun AppDeps.writes(
        household: String,
        me: String,
    ): List<Mutation> {
        val incomeId = Ids.newId()
        return listOf(
            newBudgetPlan(household, october, me),
            budgetMonth(household, october, me),
            budgetReceipt(october, incomeId, me, 60_000, LocalDate(2026, 10, 10)),
            budgetReceipt(october, null, me, 5_000, LocalDate(2026, 10, 12)),
            budgetFunding(october, incomeId, Ids.newId(), 1_000, me),
        )
    }

    /** Adults read and write; Kids see nothing and write nothing; `budget.view` alone reads but never writes. */
    @Test
    fun bud015AccessByPermission() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val household = f.admin.me.householdId
            val adultWrites = deps.writes(household, f.adult.me.memberId)
            for (m in adultWrites) client.pushOne(f.adult.accessToken, m).assertApplied()
            val extra = adultWrites[3]
            client.pushOne(f.adult.accessToken, deps.delete(EntityTypes.BUDGET_RECEIPT, extra.id)).assertApplied()

            for (member in listOf(f.admin, f.adult, f.viewer)) {
                val pulled = client.pullAll(member.accessToken).second.filter { it.type in budgetTypes && !it.deleted }
                assertEquals((adultWrites - extra).map { it.id }.toSet(), pulled.map { it.id }.toSet(), member.me.memberId)
            }
            assertTrue(client.pullAll(f.kid.accessToken).second.none { it.type in budgetTypes })

            for (member in listOf(f.kid, f.viewer)) {
                for (m in deps.writes(household, member.me.memberId)) {
                    client.pushOne(member.accessToken, m).assertRejected(ErrorCode.FORBIDDEN)
                }
                val another = deps.budgetReceipt(october, null, f.adult.me.memberId)
                client.pushOne(f.adult.accessToken, another).assertApplied()
                client.pushOne(member.accessToken, deps.delete(EntityTypes.BUDGET_RECEIPT, another.id)).assertRejected(ErrorCode.FORBIDDEN)
            }
        }

    /** `updatedBy` and a new `fundedBy` must be the writer. */
    @Test
    fun bud015WriterIsTheCaller() =
        testApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            for (m in deps.writes(f.admin.me.householdId, f.admin.me.memberId)) {
                client.pushOne(f.adult.accessToken, m).assertRejected(ErrorCode.FORBIDDEN)
            }
        }
}
