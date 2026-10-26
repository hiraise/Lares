package app.lares.client.data.location

import app.lares.client.api.LaresApi
import app.lares.contract.api.location.UploadPositionsRequest
import app.lares.contract.location.LocationLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Uploads the queued fixes (LOC-027, ADR-0028), called by the tracking service: the pending sharing request first,
 * then up to [MAX_BATCHES] batches of 200, oldest first. A failure keeps the queue and waits 30 s → 5 min before the
 * next try (unless forced, e.g. when the network is back).
 */
class PositionUploader(
    private val api: LaresApi,
    private val queue: PositionQueue,
    private val controller: SharingController,
    private val clock: Clock,
) {
    private val lock = Mutex()
    private var backoff: Duration = BACKOFF_INITIAL
    private var nextTryAt: Instant? = null

    /** True when everything that could be sent was sent; false on a failure (the queue stays) or while backing off. */
    suspend fun flush(force: Boolean = false): Boolean =
        lock.withLock {
            val now = clock.now()
            if (!force && nextTryAt?.let { now < it } == true) return@withLock false
            if (!controller.sendPending()) return@withLock failed(now)
            queue.trim(now)
            repeat(MAX_BATCHES) {
                val batch = queue.batch(LocationLimits.MAX_UPLOAD)
                if (batch.isEmpty()) return@withLock succeeded()
                val response =
                    try {
                        api.uploadPositions(UploadPositionsRequest(batch.map { it.fix }))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@withLock failed(now)
                    }
                // Stored, or dropped by the server for good (paused, another phone): never sent again.
                queue.remove(batch.map { it.id })
                controller.onUpload(response)
                if (!response.sharingHere || response.paused) return@withLock succeeded()
            }
            succeeded()
        }

    private fun failed(now: Instant): Boolean {
        nextTryAt = now + backoff
        backoff = minOf(backoff * 2, BACKOFF_MAX)
        return false
    }

    private fun succeeded(): Boolean {
        backoff = BACKOFF_INITIAL
        nextTryAt = null
        return true
    }

    companion object {
        const val MAX_BATCHES = 5
        private val BACKOFF_INITIAL = 30.seconds
        private val BACKOFF_MAX = 5.minutes
    }
}
