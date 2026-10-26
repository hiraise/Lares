package app.lares.server.location

import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.platform.toDb
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val log = KotlinLogging.logger {}

/** Deletes location history older than each household's retention (LOC-005). One job serves all households. */
class LocationRetentionJob(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    /** Returns the number of positions deleted. */
    suspend fun runOnce(now: Instant): Int {
        val households =
            db.dbQuery {
                HouseholdTable
                    .select(HouseholdTable.id, HouseholdTable.locationHistoryDays)
                    .map { it[HouseholdTable.id] to it[HouseholdTable.locationHistoryDays] }
            }
        var deleted = 0
        var cleared = 0
        for ((householdId, days) in households) {
            val cutoff = (now - days.days).toDb()
            while (true) {
                // One short transaction per batch keeps locks and WAL bursts small.
                val batch = db.dbQuery { PositionRepository.deleteBatchBefore(householdId, cutoff, BATCH) }
                deleted += batch
                if (batch < BATCH) break
            }
            cleared += db.dbQuery { LocationStateRepository.clearLastBefore(householdId, cutoff) }
        }
        if (deleted > 0 || cleared > 0) log.info { "Location retention: deleted $deleted positions, cleared $cleared last positions" }
        return deleted
    }

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            delay(2.minutes)
            while (isActive) {
                try {
                    runOnce(clock.now())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "Location retention job failed" }
                }
                delay(1.hours)
            }
        }

    companion object {
        const val BATCH = 10_000
    }
}
