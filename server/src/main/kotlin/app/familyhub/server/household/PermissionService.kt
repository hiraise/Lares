package app.familyhub.server.household

import app.familyhub.contract.permissions.EffectivePermissions
import app.familyhub.contract.permissions.PermissionEvaluator
import app.familyhub.server.platform.db.AppDatabase
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
