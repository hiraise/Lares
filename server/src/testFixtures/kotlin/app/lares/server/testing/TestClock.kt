package app.lares.server.testing

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** A clock that only moves when a test says so. */
class TestClock(
    @Volatile var current: Instant = Instant.parse("2026-10-22T10:00:00Z"),
) : Clock {
    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current += duration
    }
}
