package app.lares.contract.permissions

import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceRulesTest {
    private val admin = EffectivePermissions(isAdmin = true, permissions = Permission.entries.toSet())
    private val adults =
        EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.withImplied(PermissionCatalogue.adultsTemplate))
    private val kids =
        EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.withImplied(PermissionCatalogue.kidsTemplate))
    private val placesOnly =
        EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.withImplied(setOf(Permission.LOCATION_MANAGE_PLACES)))
    private val noMap = EffectivePermissions(isAdmin = false, permissions = setOf(Permission.TASKS_VIEW))

    private fun viewer(
        member: String,
        permissions: EffectivePermissions,
    ) = Viewer(member, "p-$member", permissions)

    private val place = PlaceData("Школа", latitude = 55.7, longitude = 37.6, createdBy = "me")

    @Test
    fun `LOC-020 places are managed with manage places, created as oneself`() {
        for (permissions in listOf(admin, adults, placesOnly)) {
            assertTrue(PlaceRules.canManage(viewer("me", permissions)))
            assertTrue(PlaceRules.canCreate(viewer("me", permissions), place))
            assertFalse(PlaceRules.canCreate(viewer("other", permissions), place))
        }
        for (permissions in listOf(kids, noMap)) {
            assertFalse(PlaceRules.canManage(viewer("me", permissions)))
            assertFalse(PlaceRules.canCreate(viewer("me", permissions), place))
        }
    }

    @Test
    fun `LOC-021 alerts are set by viewers for themselves about somebody else`() {
        for (permissions in listOf(admin, adults, kids)) {
            assertTrue(PlaceRules.canSetAlert(viewer("me", permissions), PlaceAlertData("kid", "me")))
            assertFalse(PlaceRules.canSetAlert(viewer("me", permissions), PlaceAlertData("kid", "other")))
            assertFalse(PlaceRules.canSetAlert(viewer("me", permissions), PlaceAlertData("me", "me")))
        }
        assertFalse(PlaceRules.canSetAlert(viewer("me", noMap), PlaceAlertData("kid", "me")))
    }

    @Test
    fun `LOC-020 LOC-021 write rules for places and alerts`() {
        fun can(
            type: String,
            op: MutationOp,
            permissions: EffectivePermissions,
        ) = WriteRules.canMutate(type, op, viewer("me", permissions), existingPersonOwner = false)
        for (op in MutationOp.entries) {
            assertTrue(can(EntityTypes.PLACE, op, adults))
            assertTrue(can(EntityTypes.PLACE, op, admin))
            assertFalse(can(EntityTypes.PLACE, op, kids))
            assertFalse(can(EntityTypes.PLACE, op, noMap))
        }
        assertTrue(can(EntityTypes.PLACE_ALERT, MutationOp.UPSERT, kids))
        assertTrue(can(EntityTypes.PLACE_ALERT, MutationOp.UPSERT, adults))
        assertFalse(can(EntityTypes.PLACE_ALERT, MutationOp.UPSERT, noMap))
        assertFalse(can(EntityTypes.PLACE_ALERT, MutationOp.DELETE, adults))
        assertFalse(can(EntityTypes.PLACE_ALERT, MutationOp.DELETE, admin))
    }
}
