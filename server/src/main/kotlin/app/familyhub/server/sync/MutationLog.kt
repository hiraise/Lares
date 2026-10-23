package app.familyhub.server.sync

import app.familyhub.contract.api.ApiError
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.sync.MutationResult
import app.familyhub.contract.sync.MutationStatus
import app.familyhub.server.platform.db.HouseholdTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.UUID

/** `sync_mutation_log`: the outcome of every processed mutation, for idempotent retries (architecture/sync.md). */
object MutationLogTable : Table("sync_mutation_log") {
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val mutationId = javaUUID("mutation_id")
    val memberId = javaUUID("member_id")

    /** `MutationStatus` name. */
    val status = text("status")
    val seq = long("seq").nullable()

    /** `ErrorCode` name of a rejection. The message is not stored; a replay regenerates a generic one. */
    val errorCode = text("error_code").nullable()
    val processedAt = timestamp("processed_at")

    override val primaryKey = PrimaryKey(householdId, mutationId)
}

object MutationLogRepository {
    /** The logged result as a duplicate, or null if the mutation was not processed yet. */
    fun find(
        householdId: UUID,
        mutationId: UUID,
    ): MutationResult? =
        MutationLogTable
            .selectAll()
            .where { (MutationLogTable.householdId eq householdId) and (MutationLogTable.mutationId eq mutationId) }
            .singleOrNull()
            ?.let { row ->
                val status = MutationStatus.valueOf(row[MutationLogTable.status])
                val error =
                    row[MutationLogTable.errorCode]?.let { name ->
                        val code = ErrorCode.entries.firstOrNull { it.name == name } ?: ErrorCode.INTERNAL
                        ApiError(code, "Rejected earlier: $name")
                    }
                MutationResult(mutationId.toString(), status, row[MutationLogTable.seq], error, duplicate = true)
            }

    fun record(
        householdId: UUID,
        mutationId: UUID,
        memberId: UUID,
        result: MutationResult,
        now: Instant,
    ) {
        MutationLogTable.insert {
            it[MutationLogTable.householdId] = householdId
            it[MutationLogTable.mutationId] = mutationId
            it[MutationLogTable.memberId] = memberId
            it[status] = result.status.name
            it[seq] = result.seq
            it[errorCode] = result.error?.code?.name
            it[processedAt] = now
        }
    }

    fun deleteProcessedBefore(cutoff: Instant): Int = MutationLogTable.deleteWhere { processedAt less cutoff }
}
