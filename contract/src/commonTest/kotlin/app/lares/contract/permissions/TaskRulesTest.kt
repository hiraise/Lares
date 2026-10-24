package app.lares.contract.permissions

import app.lares.contract.entities.TaskCompletionData
import app.lares.contract.entities.TaskData
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The permission table of the tasks spec, for admin, Adults and Kids as creator, assignee and other member. */
class TaskRulesTest {
    private val admin = EffectivePermissions(isAdmin = true, permissions = Permission.entries.toSet())
    private val adults = EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.adultsTemplate)
    private val kids =
        EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.withImplied(PermissionCatalogue.kidsTemplate))
    private val noTasks = EffectivePermissions(isAdmin = false, permissions = setOf(Permission.SHOPPING_VIEW))
    private val roles = mapOf("admin" to admin, "adults" to adults, "kids" to kids)

    private fun viewer(
        member: String,
        permissions: EffectivePermissions,
    ) = Viewer(member, "p-$member", permissions)

    /** Created by `creator`, assigned to `assignee`; `other` is neither. */
    private val assigned = TaskData("Vacuum", createdBy = "creator", assigneeIds = setOf("assignee"))
    private val anyone = TaskData("Vacuum", createdBy = "creator")
    private val relations = listOf("creator", "assignee", "other")

    private fun completion(by: String) = TaskCompletionData("t1", by, Instant.parse("2026-10-23T10:00:00Z"))

    @Test
    fun `TSK-001 create needs tasks create and the viewer as creator`() {
        for ((role, permissions) in roles) {
            assertTrue(TaskRules.canCreate(viewer("creator", permissions), assigned), role)
            assertFalse(TaskRules.canCreate(viewer("other", permissions), assigned), "$role for someone else")
        }
        val viewOnly = EffectivePermissions(isAdmin = false, permissions = setOf(Permission.TASKS_VIEW))
        assertFalse(TaskRules.canCreate(viewer("creator", viewOnly), assigned))
        assertFalse(TaskRules.canCreate(viewer("creator", noTasks), assigned))
    }

    @Test
    fun `TSK-005 edit and delete by the creator or edit any`() {
        val expected =
            mapOf(
                "admin" to setOf("creator", "assignee", "other"),
                "adults" to setOf("creator", "assignee", "other"),
                "kids" to setOf("creator"),
            )
        for ((role, permissions) in roles) {
            for (who in relations) {
                assertEquals(who in expected.getValue(role), TaskRules.canEdit(viewer(who, permissions), assigned), "$role $who")
            }
        }
        assertFalse(TaskRules.canEdit(viewer("creator", noTasks), assigned))
    }

    @Test
    fun `TSK-002 complete by assignees, everybody for anyone tasks, and editors`() {
        val expected =
            mapOf(
                "admin" to setOf("creator", "assignee", "other"),
                "adults" to setOf("creator", "assignee", "other"),
                "kids" to setOf("creator", "assignee"),
            )
        for ((role, permissions) in roles) {
            for (who in relations) {
                val v = viewer(who, permissions)
                assertEquals(who in expected.getValue(role), TaskRules.canComplete(v, assigned), "$role $who")
                assertTrue(TaskRules.canComplete(v, anyone), "$role $who anyone")
            }
        }
        assertFalse(TaskRules.canComplete(viewer("assignee", noTasks), assigned))
        assertFalse(TaskRules.canComplete(viewer("other", noTasks), anyone))
    }

    @Test
    fun `TSK-003 TSK-007 undo by the completer, the creator or edit any`() {
        val byAssignee = completion("assignee")
        val expected =
            mapOf(
                "admin" to setOf("creator", "assignee", "other"),
                "adults" to setOf("creator", "assignee", "other"),
                "kids" to setOf("creator", "assignee"),
            )
        for ((role, permissions) in roles) {
            for (who in relations) {
                val allowed = TaskRules.canDeleteCompletion(viewer(who, permissions), assigned, byAssignee)
                assertEquals(who in expected.getValue(role), allowed, "$role $who")
            }
        }
        // A kid who completed an "anyone" task of someone else may undo it, but not someone else's completion.
        assertTrue(TaskRules.canDeleteCompletion(viewer("other", kids), anyone, completion("other")))
        assertFalse(TaskRules.canDeleteCompletion(viewer("other", kids), anyone, completion("assignee")))
        assertFalse(TaskRules.canDeleteCompletion(viewer("assignee", noTasks), assigned, byAssignee))
    }

    @Test
    fun `TSK-004 ticking a checklist item needs complete, other changes need edit`() {
        val tick = setOf("done", "doneOccurrence")
        val rename = setOf("text")
        assertTrue(TaskRules.canChangeChecklistItem(viewer("assignee", kids), assigned, tick))
        assertTrue(TaskRules.canChangeChecklistItem(viewer("assignee", kids), assigned, setOf("done")))
        assertFalse(TaskRules.canChangeChecklistItem(viewer("assignee", kids), assigned, rename))
        assertFalse(TaskRules.canChangeChecklistItem(viewer("assignee", kids), assigned, tick + "position"))
        assertFalse(TaskRules.canChangeChecklistItem(viewer("other", kids), assigned, tick))
        assertTrue(TaskRules.canChangeChecklistItem(viewer("other", kids), anyone, tick))
        assertTrue(TaskRules.canChangeChecklistItem(viewer("creator", kids), assigned, rename))
        assertTrue(TaskRules.canChangeChecklistItem(viewer("other", adults), assigned, rename))
        assertTrue(TaskRules.canChangeChecklistItem(viewer("other", adults), assigned, emptySet()))
    }

    @Test
    fun `TSK-001 write gate for task types is tasks view`() {
        for (type in listOf(EntityTypes.TASK, EntityTypes.CHECKLIST_ITEM, EntityTypes.TASK_COMPLETION)) {
            for (op in MutationOp.entries) {
                assertTrue(WriteRules.canMutate(type, op, viewer("m", kids), false), "$type $op")
                assertTrue(WriteRules.canMutate(type, op, viewer("m", admin), false), "$type $op")
                assertFalse(WriteRules.canMutate(type, op, viewer("m", noTasks), false), "$type $op")
            }
        }
    }
}
