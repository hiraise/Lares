package app.lares.server.budget

import app.lares.server.household.MemberTable
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.sync.SyncColumns
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

// Tables of the budget module (V12__budget.sql, architecture/budget.md § Server › Tables). Months are text "YYYY-MM";
// tables are `BudgetTable` JSON text encoded with `LaresJson`.

/** `budget_plan`: one plan version (BUD-004). `savedAt` is the data's `updatedAt`; `updatedAt` is the sync column. */
object BudgetPlanTable : Table("budget_plan") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val effectiveFrom = text("effective_from")
    val planTable = text("plan_table")
    val updatedBy = javaUUID("updated_by").references(MemberTable.id)
    val savedAt = timestamp("saved_at")
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `budget_month`: month-only changes (BUD-013); a null table follows the plan. */
object BudgetMonthTable : Table("budget_month") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val month = text("month")
    val monthTable = text("month_table").nullable()
    val updatedBy = javaUUID("updated_by").references(MemberTable.id)
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `budget_receipt`: an actual income (BUD-011); a null `incomeId` is an extra income. */
object BudgetReceiptTable : Table("budget_receipt") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val month = text("month")
    val incomeId = javaUUID("income_id").nullable()
    val label = text("label").nullable()
    val amount = long("amount").nullable()
    val date = date("date").nullable()
    val updatedBy = javaUUID("updated_by").references(MemberTable.id)
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `budget_funding`: one tick (BUD-012); a null amount is unticked. */
object BudgetFundingTable : Table("budget_funding") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val month = text("month")
    val incomeId = javaUUID("income_id")
    val categoryId = javaUUID("category_id")
    val amount = long("amount").nullable()
    val fundedBy = javaUUID("funded_by").references(MemberTable.id).nullable()
    val fundedAt = timestamp("funded_at").nullable()
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}
