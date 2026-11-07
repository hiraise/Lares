package app.lares.contract.permissions

import app.lares.contract.entities.BudgetFundingData

/**
 * Entity-level write rules of the budget module (budget spec § Access; architecture/budget.md § Write rules). Called by
 * the server's sync handlers and by the client's adapters. Whether a plan version is in the past and whether a deleted
 * receipt is an extra income need the stored row and the household time; the handlers check those.
 */
object BudgetRules {
    /** Change the plan, record incomes, tick funding, change a month. */
    fun canManage(viewer: Viewer): Boolean = viewer.permissions.has(Permission.BUDGET_MANAGE)

    /** True when [updatedBy] is someone other than the writer (plan, month, receipt rows): `FORBIDDEN`. */
    fun foreignUpdater(
        viewer: Viewer,
        updatedBy: String,
    ): Boolean = updatedBy != viewer.memberId

    /**
     * True when the write sets `fundedBy` to someone other than the writer (changed to a non-null other member):
     * `FORBIDDEN`. Keeping another member's value is fine (unticking keeps it).
     */
    fun foreignFunder(
        viewer: Viewer,
        existing: BudgetFundingData?,
        merged: BudgetFundingData,
    ): Boolean {
        val fundedBy = merged.fundedBy
        return fundedBy != null && fundedBy != existing?.fundedBy && fundedBy != viewer.memberId
    }
}
