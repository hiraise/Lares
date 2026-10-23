package app.lares.contract.permissions

/** Default permissions of the group templates and the implication rule (foundation spec § Permission catalogue). */
object PermissionCatalogue {
    val adultsTemplate: Set<Permission> = Permission.entries.toSet() - Permission.HOUSEHOLD_MANAGE

    val kidsTemplate: Set<Permission> =
        setOf(
            Permission.TASKS_VIEW,
            Permission.TASKS_CREATE,
            Permission.SHOPPING_VIEW,
            Permission.SHOPPING_EDIT,
            Permission.CALENDAR_VIEW,
            Permission.CALENDAR_CREATE,
            Permission.LOCATION_VIEW,
        )

    fun sectionPermission(section: Section): Permission =
        when (section) {
            Section.TASKS -> Permission.TASKS_VIEW
            Section.SHOPPING -> Permission.SHOPPING_VIEW
            Section.CALENDAR -> Permission.CALENDAR_VIEW
            Section.MAP -> Permission.LOCATION_VIEW
            Section.BUDGET -> Permission.BUDGET_VIEW
        }

    /** Adds the section access implied by every action permission of that section (ADR-0014). */
    fun withImplied(perms: Set<Permission>): Set<Permission> =
        perms + perms.filter { it.kind == PermissionKind.ACTION }.mapNotNull { it.section }.map(::sectionPermission)
}

/** What a member may do. Administrators have every permission. */
data class EffectivePermissions(
    val isAdmin: Boolean,
    val permissions: Set<Permission>,
) {
    fun has(permission: Permission): Boolean = isAdmin || permission in permissions
}

/** A group as the evaluator needs it; built by the server from its tables and by the client from synced data. */
data class GroupView(
    val id: String,
    val builtIn: BuiltInGroup?,
    val permissions: Set<Permission>,
    val memberIds: Set<String>,
    val deleted: Boolean,
)

/** Shared permission evaluation (ADR-0014): the union of the member's groups, plus implied section access. */
object PermissionEvaluator {
    fun evaluate(
        memberId: String,
        groups: Collection<GroupView>,
    ): EffectivePermissions {
        val own = groups.filter { !it.deleted && memberId in it.memberIds }
        val isAdmin = own.any { it.builtIn == BuiltInGroup.ADMINISTRATORS }
        if (isAdmin) return EffectivePermissions(isAdmin = true, permissions = Permission.entries.toSet())
        val granted = own.flatMapTo(mutableSetOf()) { it.permissions }
        return EffectivePermissions(isAdmin = false, permissions = PermissionCatalogue.withImplied(granted))
    }
}
