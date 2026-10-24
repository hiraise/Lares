package app.lares.contract.permissions

import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteRulesTest {
    private fun viewer(
        vararg permissions: Permission,
        admin: Boolean = false,
    ) = Viewer("m1", "p1", EffectivePermissions(admin, permissions.toSet()))

    private fun can(
        type: String,
        op: MutationOp,
        viewer: Viewer,
        own: Boolean = false,
    ) = WriteRules.canMutate(type, op, viewer, own)

    @Test
    fun tagsNeedTagsManage() {
        for (op in MutationOp.entries) {
            assertTrue(can(EntityTypes.TAG, op, viewer(Permission.TAGS_MANAGE)))
            assertFalse(can(EntityTypes.TAG, op, viewer(Permission.PERSONS_MANAGE)))
            assertTrue(can(EntityTypes.TAG, op, viewer(admin = true)))
        }
    }

    @Test
    fun personsNeedPersonsManageExceptOwnProfileEdits() {
        assertTrue(can(EntityTypes.PERSON, MutationOp.UPSERT, viewer(Permission.PERSONS_MANAGE)))
        assertTrue(can(EntityTypes.PERSON, MutationOp.DELETE, viewer(Permission.PERSONS_MANAGE)))
        assertFalse(can(EntityTypes.PERSON, MutationOp.UPSERT, viewer(Permission.TAGS_MANAGE)))
        assertTrue(can(EntityTypes.PERSON, MutationOp.UPSERT, viewer(), own = true))
        assertFalse(can(EntityTypes.PERSON, MutationOp.DELETE, viewer(), own = true))
    }

    @Test
    fun shoppingListsNeedManageListsAndItemsNeedEdit() {
        for (op in MutationOp.entries) {
            assertTrue(can(EntityTypes.SHOPPING_LIST, op, viewer(Permission.SHOPPING_MANAGE_LISTS)))
            assertFalse(can(EntityTypes.SHOPPING_LIST, op, viewer(Permission.SHOPPING_VIEW, Permission.SHOPPING_EDIT)))
            assertTrue(can(EntityTypes.SHOPPING_LIST, op, viewer(admin = true)))
            assertTrue(can(EntityTypes.SHOPPING_ITEM, op, viewer(Permission.SHOPPING_EDIT)))
            assertFalse(can(EntityTypes.SHOPPING_ITEM, op, viewer(Permission.SHOPPING_VIEW, Permission.SHOPPING_MANAGE_LISTS)))
            assertTrue(can(EntityTypes.SHOPPING_ITEM, op, viewer(admin = true)))
        }
    }

    @Test
    fun `CAL-015 events need calendar view, child rows are never deleted`() {
        val viewer = viewer(Permission.CALENDAR_VIEW)
        for (op in MutationOp.entries) {
            assertTrue(can(EntityTypes.EVENT, op, viewer), "event $op")
            assertFalse(can(EntityTypes.EVENT, op, viewer(Permission.CALENDAR_CREATE, Permission.CALENDAR_EDIT_ANY)), "event $op")
        }
        for (type in listOf(EntityTypes.EVENT_OCCURRENCE, EntityTypes.EVENT_REMINDER)) {
            assertTrue(can(type, MutationOp.UPSERT, viewer), type)
            assertFalse(can(type, MutationOp.UPSERT, viewer(Permission.TASKS_VIEW)), type)
            assertFalse(can(type, MutationOp.DELETE, viewer), "$type delete")
            assertFalse(can(type, MutationOp.DELETE, viewer(admin = true)), "$type delete by admin")
        }
    }

    @Test
    fun readOnlyAndUnknownTypesAreNeverWritable() {
        for (type in listOf(EntityTypes.HOUSEHOLD, EntityTypes.MEMBER, EntityTypes.GROUP, "widget")) {
            for (op in MutationOp.entries) assertFalse(can(type, op, viewer(admin = true), own = true), "$type $op")
        }
    }
}
