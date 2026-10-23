package app.lares.contract.permissions

import app.lares.contract.api.LaresJson
import app.lares.contract.entities.GroupData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionEvaluatorTest {
    private fun group(
        id: String,
        permissions: Set<Permission> = emptySet(),
        members: Set<String> = setOf("m1"),
        builtIn: BuiltInGroup? = null,
        deleted: Boolean = false,
    ) = GroupView(id, builtIn, permissions, members, deleted)

    @Test
    fun catalogueKeysMatchSpec() {
        val keys =
            listOf(
                "household.manage",
                "persons.manage",
                "tags.manage",
                "tasks.view",
                "tasks.create",
                "tasks.edit_any",
                "shopping.view",
                "shopping.edit",
                "shopping.manage_lists",
                "calendar.view",
                "calendar.create",
                "calendar.edit_any",
                "location.view",
                "location.pause_own",
                "location.manage_places",
                "budget.view",
                "budget.manage",
            )
        assertEquals(keys, Permission.entries.map { it.key })
        keys.forEach { assertEquals(it, Permission.fromKey(it)?.key) }
        assertNull(Permission.fromKey("unknown.permission"))
    }

    @Test
    fun templatesMatchSpecTable() {
        assertEquals(Permission.entries.toSet() - Permission.HOUSEHOLD_MANAGE, PermissionCatalogue.adultsTemplate)
        assertEquals(
            setOf("tasks.view", "tasks.create", "shopping.view", "shopping.edit", "calendar.view", "calendar.create", "location.view"),
            PermissionCatalogue.kidsTemplate.map { it.key }.toSet(),
        )
    }

    @Test
    fun actionImpliesItsSection() {
        assertEquals(
            setOf(Permission.BUDGET_MANAGE, Permission.BUDGET_VIEW, Permission.LOCATION_PAUSE_OWN, Permission.LOCATION_VIEW),
            PermissionCatalogue.withImplied(setOf(Permission.BUDGET_MANAGE, Permission.LOCATION_PAUSE_OWN)),
        )
        // Permissions outside sections imply nothing.
        assertEquals(setOf(Permission.TAGS_MANAGE), PermissionCatalogue.withImplied(setOf(Permission.TAGS_MANAGE)))
    }

    @Test
    fun unionOfGroupsWithImpliedSections() {
        val result =
            PermissionEvaluator.evaluate(
                "m1",
                listOf(
                    group("g1", setOf(Permission.TASKS_CREATE)),
                    group("g2", setOf(Permission.SHOPPING_VIEW)),
                    group("g3", setOf(Permission.BUDGET_MANAGE), members = setOf("m2")),
                ),
            )
        assertFalse(result.isAdmin)
        assertEquals(setOf(Permission.TASKS_CREATE, Permission.TASKS_VIEW, Permission.SHOPPING_VIEW), result.permissions)
        assertTrue(result.has(Permission.TASKS_VIEW))
        assertFalse(result.has(Permission.BUDGET_VIEW))
    }

    @Test
    fun administratorsHaveEverything() {
        val result = PermissionEvaluator.evaluate("m1", listOf(group("admins", builtIn = BuiltInGroup.ADMINISTRATORS)))
        assertTrue(result.isAdmin)
        assertEquals(Permission.entries.toSet(), result.permissions)
        Permission.entries.forEach { assertTrue(result.has(it)) }
    }

    @Test
    fun deletedGroupsAreIgnored() {
        val result =
            PermissionEvaluator.evaluate(
                "m1",
                listOf(
                    group("admins", builtIn = BuiltInGroup.ADMINISTRATORS, deleted = true),
                    group("g1", setOf(Permission.TASKS_CREATE), deleted = true),
                ),
            )
        assertEquals(EffectivePermissions(isAdmin = false, permissions = emptySet()), result)
    }

    @Test
    fun memberInNoGroupHasNothing() {
        val result = PermissionEvaluator.evaluate("m9", listOf(group("g1", setOf(Permission.TASKS_CREATE))))
        assertEquals(EffectivePermissions(isAdmin = false, permissions = emptySet()), result)
        Permission.entries.forEach { assertFalse(result.has(it)) }
    }

    @Test
    fun permissionSetsUseKeysAndDropUnknownValues() {
        val data = GroupData("Kids", null, setOf(Permission.TASKS_VIEW), setOf("m1"))
        val json = LaresJson.encodeToString(data)
        assertEquals("""{"name":"Kids","permissions":["tasks.view"],"memberIds":["m1"]}""", json)
        val decoded =
            LaresJson.decodeFromString<GroupData>(
                """{"name":"Kids","builtIn":"ADMINISTRATORS","permissions":["tasks.view","future.thing"],"memberIds":[]}""",
            )
        assertEquals(setOf(Permission.TASKS_VIEW), decoded.permissions)
        assertEquals(BuiltInGroup.ADMINISTRATORS, decoded.builtIn)
    }
}
