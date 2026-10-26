package app.lares.client.data.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.SetSharingRequest
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.UploadPositionsResponse
import app.lares.contract.api.notifications.NotificationChannel
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SharingControllerTest {
    private val f = LocationFixture()
    private var online = true

    @AfterTest
    fun close() = f.close()

    private suspend fun setUp() {
        f.family()
        f.loginAs(DIMA, *LOC_ADULT)
        f.server = { request ->
            if (!online) throw IOException("no network")
            when (request.url.encodedPath) {
                "/api/v1/location/positions" -> json(UploadPositionsResponse.serializer(), UploadPositionsResponse(1, sharingHere = true))
                else -> respond("", HttpStatusCode.NoContent)
            }
        }
    }

    private fun sharingRequests(): List<SetSharingRequest> =
        f.requests
            .filter { it.method == HttpMethod.Put && it.url.encodedPath == "/api/v1/location/me/sharing" }
            .map { LaresJson.decodeFromString(SetSharingRequest.serializer(), it.bodyText()) }

    private fun paths(): List<String> = f.requests.map(HttpRequestData::url).map { it.encodedPath }

    @Test
    fun `LOC-001 enable online starts tracking, offline it needs the network`() =
        runTest {
            setUp()
            online = false
            assertIs<NeedsNetworkException>(f.controller.enable().exceptionOrNull())
            assertFalse(f.controller.isEnabled())
            assertEquals(emptyList(), f.tracking.calls)
            f.requests.clear()

            online = true
            assertTrue(f.controller.enable().isSuccess)
            assertTrue(f.controller.isEnabled())
            assertEquals(listOf("start"), f.tracking.calls)
            assertEquals(listOf(SetSharingRequest(SharingStatus.SHARING)), sharingRequests())
            assertEquals(MySharing.Sharing, f.controller.observe().first())
        }

    @Test
    fun `LOC-010 pause choices, «До утра» across midnight`() =
        runTest {
            setUp()
            f.controller.enable()
            f.controller.pause(PauseChoice.HOUR)
            assertEquals(f.clock.now() + 1.hours, sharingRequests().last().pausedUntil)
            assertEquals(MySharing.Paused(f.clock.now() + 1.hours), f.controller.observe().first())
            assertTrue(f.tracking.calls.contains("pauseChanged"))

            f.controller.pause(PauseChoice.MORNING)
            assertEquals(Instant.parse("2026-10-22T05:00:00Z"), sharingRequests().last().pausedUntil, "13:00 → tomorrow 08:00")

            f.controller.pause(PauseChoice.FOREVER)
            assertEquals(SetSharingRequest(SharingStatus.PAUSED), sharingRequests().last())
            assertEquals(MySharing.Paused(null), f.controller.observe().first())
            assertTrue(f.controller.isPausedLocally())

            val moscow = TimeZone.of("Europe/Moscow")
            assertEquals(
                Instant.parse("2026-10-22T05:00:00Z"),
                SharingController.nextMorning(Instant.parse("2026-10-21T20:30:00Z"), moscow),
                "23:30 → 08:00 next day",
            )
            assertEquals(
                Instant.parse("2026-10-22T05:00:00Z"),
                SharingController.nextMorning(Instant.parse("2026-10-21T21:30:00Z"), moscow),
                "00:30 → 08:00 the same day",
            )
            assertEquals(
                Instant.parse("2026-10-23T05:00:00Z"),
                SharingController.nextMorning(Instant.parse("2026-10-22T05:00:00Z"), moscow),
                "exactly 08:00 → the next morning",
            )
        }

    @Test
    fun `LOC-010 resume ends the pause`() =
        runTest {
            setUp()
            f.controller.enable()
            f.controller.pause(PauseChoice.FOREVER)
            f.controller.resume()
            assertFalse(f.controller.isPausedLocally())
            assertEquals(SetSharingRequest(SharingStatus.SHARING), sharingRequests().last())
            assertEquals(MySharing.Sharing, f.controller.observe().first())
        }

    @Test
    fun `LOC-010 a refused pause keeps sharing`() =
        runTest {
            setUp()
            f.controller.enable()
            f.server = { apiError(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN) }
            assertTrue(f.controller.pause(PauseChoice.HOUR).isFailure)
            assertFalse(f.controller.isPausedLocally())
            assertNull(f.pref("pending"))
        }

    @Test
    fun `LOC-027 a pending sharing request is sent before positions`() =
        runTest {
            setUp()
            f.controller.enable()
            online = false
            f.controller.pause(PauseChoice.FOREVER)
            f.controller.resume()
            assertEquals(
                SetSharingRequest(SharingStatus.SHARING),
                LaresJson.decodeFromString(SetSharingRequest.serializer(), f.pref("pending")!!),
            )
            f.queue.add(fixAt(f.clock.now()))
            assertFalse(f.uploader.flush(force = true), "offline keeps both")
            f.requests.clear()

            online = true
            assertTrue(f.uploader.flush(force = true))
            assertEquals(listOf("/api/v1/location/me/sharing", "/api/v1/location/positions"), paths())
            assertNull(f.pref("pending"))
            assertEquals(0, f.queue.size())
        }

    @Test
    fun `LOC-010 an expired pause resumes by itself`() =
        runTest {
            setUp()
            f.controller.enable()
            online = false
            f.controller.pause(PauseChoice.HOUR)
            assertTrue(f.controller.isPausedLocally())
            f.tracking.calls.clear()

            f.setNow(f.clock.now() + 61.minutes)
            f.controller.checkPause()
            assertFalse(f.controller.isPausedLocally())
            assertNull(f.pref("pending"), "a pause that never left the phone is dropped")
            assertEquals(listOf("pauseChanged"), f.tracking.calls)
            assertEquals(MySharing.Sharing, f.controller.observe().first())
        }

    @Test
    fun `LOC-019 another phone took over - disable and tell`() =
        runTest {
            setUp()
            f.controller.enable()
            f.queue.add(fixAt(f.clock.now()))
            f.controller.onUpload(UploadPositionsResponse(0, sharingHere = false))
            assertFalse(f.controller.isEnabled())
            assertEquals(listOf("start", "stop"), f.tracking.calls)
            assertEquals(listOf(NotificationChannel.SYSTEM to "Another phone now shares your location"), f.posted)
            assertEquals(0, f.queue.size())
            assertEquals(MySharing.Off, f.controller.observe().first())

            f.controller.onUpload(UploadPositionsResponse(0, sharingHere = false))
            assertEquals(1, f.posted.size, "told once")
        }

    @Test
    fun `LOC-010 a pause from another phone of mine arrives with the upload`() =
        runTest {
            setUp()
            f.controller.enable()
            val until = f.clock.now() + 2.hours
            f.controller.onUpload(UploadPositionsResponse(0, sharingHere = true, paused = true, pausedUntil = until))
            assertEquals(MySharing.Paused(until), f.controller.observe().first())
        }

    @Test
    fun `LOC-011 refresh requests ask for a fresh fix only while sharing here and not paused`() =
        runTest {
            setUp()
            f.controller.onRefreshRequest()
            assertEquals(emptyList(), f.tracking.calls, "not enabled here")

            f.controller.enable()
            f.controller.onUpload(UploadPositionsResponse(1, sharingHere = true, refreshRequested = true))
            assertEquals(listOf("start", "freshFix"), f.tracking.calls)

            online = false
            f.controller.pause(PauseChoice.HOUR)
            f.tracking.calls.clear()
            f.controller.onRefreshRequest()
            assertEquals(emptyList(), f.tracking.calls, "a pause not yet sent never leaks a fix")
        }

    @Test
    fun `LOC-012 off is reported once per reason until sharing works again`() =
        runTest {
            setUp()
            f.controller.enable()
            f.controller.reportOff(OffReason.LOCATION_DISABLED)
            f.controller.reportOff(OffReason.LOCATION_DISABLED)
            f.controller.reportOff(OffReason.PERMISSION_REVOKED)
            assertEquals(
                listOf(OffReason.LOCATION_DISABLED, OffReason.PERMISSION_REVOKED),
                sharingRequests().filter { it.status == SharingStatus.OFF_ON_PHONE }.map { it.reason },
            )
            f.controller.onUpload(UploadPositionsResponse(1, sharingHere = true))
            f.controller.reportOff(OffReason.PERMISSION_REVOKED)
            assertEquals(3, sharingRequests().count { it.status == SharingStatus.OFF_ON_PHONE })
        }
}
