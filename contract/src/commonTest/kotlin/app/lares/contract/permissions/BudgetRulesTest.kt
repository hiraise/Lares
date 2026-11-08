package app.lares.contract.permissions

import app.lares.contract.entities.BudgetFundingData
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The access table of the budget spec for admin, Adults, Kids and a custom "view only" group. */
class BudgetRulesTest {
    private val admin = EffectivePermissions(isAdmin = true, permissions = emptySet())
    private val adults = EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.adultsTemplate)
    private val kids =
        EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.withImplied(PermissionCatalogue.kidsTemplate))
    private val viewOnly = EffectivePermissions(isAdmin = false, permissions = setOf(Permission.BUDGET_VIEW))

    private fun viewer(
        member: String,
        permissions: EffectivePermissions = adults,
    ) = Viewer(member, "p-$member", permissions)

    @Test
    fun `BUD-004 BUD-012 the budget is managed by admins and adults, not by kids or view only`() {
        val expected =
            mapOf(
                "admin" to (admin to true),
                "adults" to (adults to true),
                "kids" to (kids to false),
                "view only" to (viewOnly to false),
            )
        for ((role, entry) in expected) {
            val (permissions, manage) = entry
            assertEquals(manage, BudgetRules.canManage(viewer("me", permissions)), role)
        }
    }

    @Test
    fun `BUD-004 BUD-011 BUD-013 rows are written only as oneself`() {
        assertFalse(BudgetRules.foreignUpdater(viewer("me"), "me"))
        assertTrue(BudgetRules.foreignUpdater(viewer("me"), "other"))
    }

    @Test
    fun `BUD-012 a tick sets fundedBy only to the writer, keeping another member's value is fine`() {
        val me = viewer("me")
        val untouched = BudgetFundingData(YearMonth(2026, 12), "i1", "c1")
        assertFalse(BudgetRules.foreignFunder(me, null, untouched))
        assertFalse(BudgetRules.foreignFunder(me, null, untouched.copy(amount = 1, fundedBy = "me")))
        assertTrue(BudgetRules.foreignFunder(me, null, untouched.copy(amount = 1, fundedBy = "other")))
        val byOther = untouched.copy(amount = 1, fundedBy = "other")
        assertFalse(BudgetRules.foreignFunder(me, byOther, byOther.copy(amount = null)), "unticking keeps fundedBy")
        assertFalse(BudgetRules.foreignFunder(me, byOther, byOther.copy(amount = 2, fundedBy = "me")))
        assertTrue(BudgetRules.foreignFunder(me, byOther.copy(fundedBy = "me"), byOther.copy(fundedBy = "third")))
    }
}
