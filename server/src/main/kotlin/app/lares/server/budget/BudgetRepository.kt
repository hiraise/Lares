package app.lares.server.budget

import app.lares.contract.api.LaresJson
import app.lares.contract.entities.BudgetFundingData
import app.lares.contract.entities.BudgetMonthData
import app.lares.contract.entities.BudgetPlanData
import app.lares.contract.entities.BudgetReceiptData
import app.lares.contract.entities.BudgetTable
import app.lares.server.platform.toContract
import app.lares.server.platform.toDb
import app.lares.server.sync.StoredEntity
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Reads and row mapping of the budget tables. All functions run inside `dbQuery`. */
object BudgetRepository {
    /** The plan version by id in any household (the sync handlers tell "other household" apart themselves). */
    fun loadPlan(id: UUID): StoredEntity<BudgetPlanData>? =
        BudgetPlanTable.selectAll().where { BudgetPlanTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[BudgetPlanTable.householdId], it[BudgetPlanTable.deleted], toPlanData(it))
        }

    fun loadMonth(id: UUID): StoredEntity<BudgetMonthData>? =
        BudgetMonthTable.selectAll().where { BudgetMonthTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[BudgetMonthTable.householdId], it[BudgetMonthTable.deleted], toMonthData(it))
        }

    fun loadReceipt(id: UUID): StoredEntity<BudgetReceiptData>? =
        BudgetReceiptTable.selectAll().where { BudgetReceiptTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[BudgetReceiptTable.householdId], it[BudgetReceiptTable.deleted], toReceiptData(it))
        }

    fun loadFunding(id: UUID): StoredEntity<BudgetFundingData>? =
        BudgetFundingTable.selectAll().where { BudgetFundingTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[BudgetFundingTable.householdId], it[BudgetFundingTable.deleted], toFundingData(it))
        }

    fun toPlanData(row: ResultRow) =
        BudgetPlanData(
            effectiveFrom = YearMonth.parse(row[BudgetPlanTable.effectiveFrom]),
            table = decodeTable(row[BudgetPlanTable.planTable]),
            updatedBy = row[BudgetPlanTable.updatedBy].toString(),
            updatedAt = row[BudgetPlanTable.savedAt].toContract(),
        )

    fun writePlan(
        it: UpdateBuilder<*>,
        data: BudgetPlanData,
    ) {
        it[BudgetPlanTable.effectiveFrom] = data.effectiveFrom.toString()
        it[BudgetPlanTable.planTable] = encodeTable(data.table)
        it[BudgetPlanTable.updatedBy] = UUID.fromString(data.updatedBy)
        it[BudgetPlanTable.savedAt] = data.updatedAt.toDb()
    }

    fun toMonthData(row: ResultRow) =
        BudgetMonthData(
            month = YearMonth.parse(row[BudgetMonthTable.month]),
            table = row[BudgetMonthTable.monthTable]?.let(::decodeTable),
            updatedBy = row[BudgetMonthTable.updatedBy].toString(),
        )

    fun writeMonth(
        it: UpdateBuilder<*>,
        data: BudgetMonthData,
    ) {
        it[BudgetMonthTable.month] = data.month.toString()
        it[BudgetMonthTable.monthTable] = data.table?.let(::encodeTable)
        it[BudgetMonthTable.updatedBy] = UUID.fromString(data.updatedBy)
    }

    fun toReceiptData(row: ResultRow) =
        BudgetReceiptData(
            month = YearMonth.parse(row[BudgetReceiptTable.month]),
            incomeId = row[BudgetReceiptTable.incomeId]?.toString(),
            label = row[BudgetReceiptTable.label],
            amount = row[BudgetReceiptTable.amount],
            date = row[BudgetReceiptTable.date]?.toKotlinLocalDate(),
            updatedBy = row[BudgetReceiptTable.updatedBy].toString(),
        )

    fun writeReceipt(
        it: UpdateBuilder<*>,
        data: BudgetReceiptData,
    ) {
        it[BudgetReceiptTable.month] = data.month.toString()
        it[BudgetReceiptTable.incomeId] = data.incomeId?.let(UUID::fromString)
        it[BudgetReceiptTable.label] = data.label
        it[BudgetReceiptTable.amount] = data.amount
        it[BudgetReceiptTable.date] = data.date?.toJavaLocalDate()
        it[BudgetReceiptTable.updatedBy] = UUID.fromString(data.updatedBy)
    }

    fun toFundingData(row: ResultRow) =
        BudgetFundingData(
            month = YearMonth.parse(row[BudgetFundingTable.month]),
            incomeId = row[BudgetFundingTable.incomeId].toString(),
            categoryId = row[BudgetFundingTable.categoryId].toString(),
            amount = row[BudgetFundingTable.amount],
            fundedBy = row[BudgetFundingTable.fundedBy]?.toString(),
            fundedAt = row[BudgetFundingTable.fundedAt]?.toContract(),
        )

    fun writeFunding(
        it: UpdateBuilder<*>,
        data: BudgetFundingData,
    ) {
        it[BudgetFundingTable.month] = data.month.toString()
        it[BudgetFundingTable.incomeId] = UUID.fromString(data.incomeId)
        it[BudgetFundingTable.categoryId] = UUID.fromString(data.categoryId)
        it[BudgetFundingTable.amount] = data.amount
        it[BudgetFundingTable.fundedBy] = data.fundedBy?.let(UUID::fromString)
        it[BudgetFundingTable.fundedAt] = data.fundedAt?.toDb()
    }

    private fun encodeTable(table: BudgetTable): String = LaresJson.encodeToString(BudgetTable.serializer(), table)

    private fun decodeTable(text: String): BudgetTable = LaresJson.decodeFromString(BudgetTable.serializer(), text)
}
