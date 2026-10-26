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

class PositionUploaderTest {
    private val f = LocationFixture()
    private val now = f.clock.now()
    private val uploads = mutableListOf<UploadPositionsRequest>()
    private var online = true

    @AfterTest
    fun close() = f.close()

    private suspend fun setUp() {
        f.family()
        f.loginAs(DIMA, *LOC_ADULT)
        f.server = { request ->
            if (!online) throw IOException("no network")
            if (request.url.encodedPath == "/api/v1/location/positions") {
                val body = LaresJson.decodeFromString(UploadPositionsRequest.serializer(), request.bodyText())
                uploads += body
                json(UploadPositionsResponse.serializer(), UploadPositionsResponse(body.positions.size, sharingHere = true))
            } else {
                respond("", HttpStatusCode.NoContent)
            }
        }
        f.controller.enable()
    }

    private suspend fun queue(count: Int) = repeat(count) { f.queue.add(fixAt(now - (count - it).seconds)) }

    @Test
    fun `LOC-027 batches of 200 in order, at most 5 per flush`() =
        runTest {
            setUp()
            queue(1_100)
            assertTrue(f.uploader.flush())
            assertEquals(listOf(200, 200, 200, 200, 200), uploads.map { it.positions.size })
            assertEquals(
                uploads.flatMap { it.positions }.map { it.recordedAt },
                uploads.flatMap { it.positions }.map { it.recordedAt }.sorted(),
            )
            assertEquals(100, f.queue.size())
            assertTrue(f.uploader.flush())
            assertEquals(0, f.queue.size())
        }

    @Test
    fun `LOC-027 a failure keeps the queue and backs off`() =
        runTest {
            setUp()
            queue(3)
            online = false
            assertFalse(f.uploader.flush())
            assertEquals(3, f.queue.size())

            online = true
            assertFalse(f.uploader.flush(), "backing off for 30 s")
            assertEquals(3, f.queue.size())
            f.clock.now = now + 31.seconds
            assertTrue(f.uploader.flush())
            assertEquals(0, f.queue.size())
        }

    @Test
    fun `LOC-019 another phone sharing stops the flush`() =
        runTest {
            setUp()
            queue(450)
            f.server = { json(UploadPositionsResponse.serializer(), UploadPositionsResponse(0, sharingHere = false)) }
            assertTrue(f.uploader.flush())
            assertEquals(1, f.requests.count { it.url.encodedPath == "/api/v1/location/positions" })
            assertFalse(f.controller.isEnabled())
            assertEquals(0, f.queue.size())
        }

    @Test
    fun `LOC-027 the queue is trimmed before uploading`() =
        runTest {
            setUp()
            f.queue.add(fixAt(now - 25.hours))
            f.queue.add(fixAt(now))
            assertTrue(f.uploader.flush())
            assertEquals(listOf(listOf(now)), uploads.map { u -> u.positions.map { it.recordedAt } })
        }
}
