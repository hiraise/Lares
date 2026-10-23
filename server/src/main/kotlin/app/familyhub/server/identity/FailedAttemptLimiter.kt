package app.familyhub.server.identity

import app.familyhub.contract.api.ErrorCode
import app.familyhub.server.platform.http.ApiException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Counts failed attempts per key (an IP or a login) in a sliding window, in memory.
 * When [maxFailures] failures happened within [window], further attempts get `RATE_LIMITED` until old ones expire.
 * In-memory is enough for one server process; a restart clears the counters.
 */
class FailedAttemptLimiter(
    private val maxFailures: Int,
    private val window: Duration,
    private val clock: Clock,
) {
    private val failures = HashMap<String, ArrayDeque<Instant>>()

    fun ensureAllowed(key: String) {
        val count = synchronized(failures) { prune(key, clock.now()) }
        if (count >= maxFailures) throw ApiException(ErrorCode.RATE_LIMITED, "Too many failed attempts, try again later")
    }

    fun recordFailure(key: String) {
        val now = clock.now()
        synchronized(failures) {
            prune(key, now)
            failures.getOrPut(key) { ArrayDeque() }.addLast(now)
            if (failures.size > SWEEP_THRESHOLD) sweep(now)
        }
    }

    fun reset(key: String) {
        synchronized(failures) { failures.remove(key) }
    }

    /** Drops expired failures of [key] and returns how many remain. */
    private fun prune(
        key: String,
        now: Instant,
    ): Int {
        val queue = failures[key] ?: return 0
        while (queue.isNotEmpty() && queue.first() <= now - window) queue.removeFirst()
        if (queue.isEmpty()) failures.remove(key)
        return queue.size
    }

    /** Keeps memory bounded when many different keys (e.g. IPs) fail once and never come back. */
    private fun sweep(now: Instant) {
        failures.keys.toList().forEach { prune(it, now) }
    }

    companion object {
        private const val SWEEP_THRESHOLD = 10_000

        /** Login and code exchange: 10 failures per key per 15 minutes (architecture/auth.md). */
        fun forLogin(clock: Clock) = FailedAttemptLimiter(maxFailures = 10, window = 15.minutes, clock = clock)

        /** Instance setup: 5 failures per IP per 15 minutes. */
        fun forSetup(clock: Clock) = FailedAttemptLimiter(maxFailures = 5, window = 15.minutes, clock = clock)
    }
}
