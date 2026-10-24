package app.lares.client.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Emits [Clock.now] right away and then at every minute boundary, so time-dependent views (overdue tasks, "who is
 * shopping") change without data changes.
 */
fun minuteTicks(clock: Clock): Flow<Instant> =
    flow {
        while (true) {
            val now = clock.now()
            emit(now)
            delay(MINUTE_MS - now.toEpochMilliseconds().mod(MINUTE_MS))
        }
    }

private const val MINUTE_MS = 60_000L
