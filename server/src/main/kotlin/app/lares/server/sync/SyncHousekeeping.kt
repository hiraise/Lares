package app.lares.server.sync

import app.lares.contract.sync.SyncLimits
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.platform.sync.lockHousehold
import app.lares.server.platform.toDb
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.javatime.JavaInstantColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/** Daily sync jobs (architecture/sync.md § Housekeeping). The job functions are plain so tests call them directly. */
class SyncHousekeeping(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    /**
     * Deletes tombstones older than 90 days that nothing references: tags, groups (their permission and membership rows
     * cascade), persons that no member or invite points to, and checklist items, completions and tasks (children first;
     * the foreign keys cascade anyway). Member and household tombstones are kept (sessions,
     * invites and login codes reference members). Moves `purge_horizon_seq` past the newest purged seq, so clients whose
     * cursor is older make a full resync. Returns the number of purged rows.
     */
    suspend fun purgeTombstones(now: Instant): Int {
        val cutoff = (now - SyncLimits.TOMBSTONE_TTL_DAYS.days).toDb()
        val households =
            db.dbQuery {
                HouseholdSyncStateTable.select(HouseholdSyncStateTable.householdId).map { it[HouseholdSyncStateTable.householdId] }
            }
        var total = 0
        for (householdId in households) {
            total +=
                db.dbQuery {
                    // Same lock as every writer of the household: no push can race with the purge.
                    lockHousehold(householdId)
                    val purged =
                        listOf(
                            purge("tag", householdId, cutoff),
                            purge("group_entity", householdId, cutoff),
                            purge(
                                "person",
                                householdId,
                                cutoff,
                                extraCondition =
                                    "AND NOT EXISTS (SELECT 1 FROM member m WHERE m.person_id = t.id) " +
                                        "AND NOT EXISTS (SELECT 1 FROM invite i WHERE i.person_id = t.id)",
                            ),
                            purge("checklist_item", householdId, cutoff),
                            purge("task_completion", householdId, cutoff),
                            purge("task", householdId, cutoff),
                        )
                    val maxSeq = purged.mapNotNull { it.maxSeq }.maxOrNull()
                    if (maxSeq != null) {
                        exec(
                            "UPDATE household_sync_state SET purge_horizon_seq = GREATEST(purge_horizon_seq, ?) WHERE household_id = ?",
                            listOf(
                                org.jetbrains.exposed.v1.core
                                    .LongColumnType() to maxSeq,
                                UUIDColumnType() to householdId,
                            ),
                        )
                    }
                    purged.sumOf { it.count }
                }
        }
        return total
    }

    /** Deletes idempotency log rows older than 30 days (mutations that old are rejected as expired anyway). */
    suspend fun cleanupMutationLog(now: Instant): Int =
        db.dbQuery { MutationLogRepository.deleteProcessedBefore((now - SyncLimits.MUTATION_TTL_DAYS.days).toDb()) }

    suspend fun runOnce() {
        val now = clock.now()
        val purged = purgeTombstones(now)
        val cleaned = cleanupMutationLog(now)
        log.info { "Sync housekeeping: purged $purged tombstones, removed $cleaned mutation log rows" }
    }

    /** Runs the jobs 5 minutes after start and then every 24 hours; failures are logged and retried next time. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            delay(5.minutes)
            while (isActive) {
                try {
                    runOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "Sync housekeeping failed" }
                }
                delay(24.hours)
            }
        }

    private class Purged(
        val count: Int,
        val maxSeq: Long?,
    )

    /** [table] is a constant from this file, never user input. */
    private fun JdbcTransaction.purge(
        table: String,
        householdId: UUID,
        cutoff: java.time.Instant,
        extraCondition: String = "",
    ): Purged =
        exec(
            "WITH purged AS (DELETE FROM $table t WHERE t.household_id = ? AND t.deleted AND t.updated_at < ? $extraCondition " +
                "RETURNING t.seq) SELECT count(*), max(seq) FROM purged",
            listOf(UUIDColumnType() to householdId, JavaInstantColumnType() to cutoff),
            explicitStatementType = StatementType.SELECT,
        ) { rs ->
            rs.next()
            val max = rs.getLong(2)
            Purged(rs.getInt(1), if (rs.wasNull()) null else max)
        } ?: Purged(0, null)
}
