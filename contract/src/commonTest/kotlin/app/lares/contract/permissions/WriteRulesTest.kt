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
    fun readOnlyAndUnknownTypesAreNeverWritable() {
        for (type in listOf(EntityTypes.HOUSEHOLD, EntityTypes.MEMBER, EntityTypes.GROUP, "task")) {
            for (op in MutationOp.entries) assertFalse(can(type, op, viewer(admin = true), own = true), "$type $op")
        }
    }
}
