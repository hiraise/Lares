package app.lares.client.data.location

import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.UploadPositionsRequest
import app.lares.contract.api.location.UploadPositionsResponse
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PositionQueueTest {
    private val f = LocationFixture()
    private val now = f.clock.now()

    @AfterTest
    fun close() = f.close()

    @Test
    fun `LOC-027 fixes come out oldest first, also with and without fractions of a second`() =
        runTest {
            f.queue.add(fixAt(now))
            f.queue.add(fixAt(now - 1.minutes))
            f.queue.add(fixAt(now - 500.milliseconds))
            assertEquals(listOf(now - 1.minutes, now - 500.milliseconds, now), f.queue.batch(10).map { it.fix.recordedAt })
            assertEquals(now - 1.minutes, f.queue.oldest())
            assertEquals(2, f.queue.batch(2).size)
        }

    @Test
    fun `LOC-027 trim drops fixes older than 24 hours and keeps at most 5000`() =
        runTest {
            f.queue.add(fixAt(now - 25.hours))
            f.localDb.transaction {
                // 5 001 fresh fixes, one per second up to now.
                for (i in 0..PositionQueue.MAX_FIXES) {
                    val at = now - (PositionQueue.MAX_FIXES - i).seconds
                    position_outboxQueries.insert(
                        at.toEpochMilliseconds(),
                        LaresJson.encodeToString(
                            app.lares.contract.api.location.PositionFix
                                .serializer(),
                            fixAt(at),
                        ),
                    )
                }
            }
            f.queue.trim(now)
            assertEquals(PositionQueue.MAX_FIXES.toLong(), f.queue.size())
            assertEquals(now - (PositionQueue.MAX_FIXES - 1).seconds, f.queue.oldest(), "the oldest go first")
            f.queue.clear()
            assertNull(f.queue.oldest())
        }
}
