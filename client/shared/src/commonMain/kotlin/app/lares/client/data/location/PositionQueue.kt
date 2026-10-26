package app.lares.client.data.location

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import app.lares.client.db.LocalDb
import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.PositionFix
import app.lares.contract.location.LocationLimits
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A queued fix with its row id. */
data class QueuedFix(
    val id: Long,
    val fix: PositionFix,
)

/**
 * Fixes recorded on this phone and not yet uploaded (LOC-027, ADR-0028): oldest first, at most 24 hours and
 * [MAX_FIXES] fixes (the server refuses older ones anyway).
 */
class PositionQueue(
    private val localDb: LocalDb,
) {
    suspend fun add(fix: PositionFix) =
        localDb.transaction {
            position_outboxQueries.insert(fix.recordedAt.toEpochMilliseconds(), LaresJson.encodeToString(PositionFix.serializer(), fix))
        }

    /** The oldest [max] fixes; rows that no longer decode are dropped. */
    suspend fun batch(max: Int): List<QueuedFix> =
        localDb.transaction {
            position_outboxQueries.oldest(max.toLong()).executeAsList().mapNotNull { row ->
                val fix = runCatching { LaresJson.decodeFromString(PositionFix.serializer(), row.json) }.getOrNull()
                if (fix == null) position_outboxQueries.deleteById(row.id)
                fix?.let { QueuedFix(row.id, it) }
            }
        }

    suspend fun remove(ids: Collection<Long>) = localDb.transaction { ids.forEach { position_outboxQueries.deleteById(it) } }

    /** Drops fixes older than 24 hours before [now] and the oldest beyond [MAX_FIXES]. */
    suspend fun trim(now: Instant) =
        localDb.transaction {
            position_outboxQueries.deleteBefore((now - LocationLimits.MAX_FIX_AGE_HOURS.hours).toEpochMilliseconds())
            position_outboxQueries.deleteOldestBeyond(MAX_FIXES.toLong())
        }

    suspend fun size(): Long = localDb.read { position_outboxQueries.count().executeAsOne() }

    /** Time of the oldest queued fix, null when the queue is empty. */
    suspend fun oldest(): Instant? =
        localDb.read { position_outboxQueries.oldestRecordedAt().executeAsOne().oldest }?.let(Instant::fromEpochMilliseconds)

    fun observeSize(): Flow<Long> =
        localDb.db.position_outboxQueries
            .count()
            .asFlow()
            .mapToOne(localDb.dispatcher)

    suspend fun clear() = localDb.transaction { position_outboxQueries.deleteAll() }

    companion object {
        const val MAX_FIXES = 5_000
    }
}
