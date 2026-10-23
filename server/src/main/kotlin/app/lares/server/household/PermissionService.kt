package app.lares.server.household

import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.PermissionEvaluator
import app.lares.server.platform.db.AppDatabase
import java.util.UUID

/** Effective permissions of a member, computed with the shared evaluator from `contract/` (ADR-0014). */
class PermissionService(
    private val db: AppDatabase,
) {
    suspend fun effective(
        householdId: UUID,
        memberId: UUID,
    ): EffectivePermissions = db.dbQuery { effectiveInTransaction(householdId, memberId) }

    /** Same as [effective], for callers already inside `dbQuery`. */
    fun effectiveInTransaction(
        householdId: UUID,
        memberId: UUID,
    ): EffectivePermissions = PermissionEvaluator.evaluate(memberId.toString(), GroupRepository.loadViews(householdId))
}
