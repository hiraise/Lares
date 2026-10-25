package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.notifications.PushPayload
import app.lares.server.AppDeps
import app.lares.server.notifications.FakeDevice
import app.lares.server.notifications.FakePushService
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.MEMBER_PASSWORD
import app.lares.server.testing.assertError
import app.lares.server.testing.createSecondHousehold
import app.lares.server.testing.fix
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.send
import app.lares.server.testing.setSharing
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upload
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** «Обновить» (LOC-011): a push to the sharing phone, at most once a minute, and the flag in upload responses. */
class LocationRefreshTest {
    private val push = FakePushService()

    @AfterTest
    fun stop() = push.close()

    private fun pushApp(block: suspend ApplicationTestBuilder.(AppDeps) -> Unit) =
        testApp(configure = { it.copy(pushAllowedHosts = setOf("127.0.0.1")) }, block = block)

    private suspend fun HttpClient.register(
        token: String,
        device: FakeDevice,
    ) = assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/me/push", token, device.registration).status)

    private suspend fun HttpClient.refresh(
        token: String,
        memberId: String,
    ) = send(HttpMethod.Post, "/api/v1/location/members/$memberId/refresh", token)

    /** Refresh pushes received within [window] as (path, payload); sync tickles are left out. */
    private suspend fun refreshPushes(
        devices: Map<String, FakeDevice>,
        window: kotlin.time.Duration = 700.milliseconds,
    ): List<String> =
        buildList {
            while (true) {
                val request = push.nextOrNull(window) ?: break
                val payload = devices.getValue(request.path).decrypt(request.body)
                if (payload.kind == PushPayload.KIND_LOCATION_REFRESH) add(request.path)
            }
        }

    @Test
    fun loc011RefreshPushesTheSharingPhoneOnceAMinuteAndRidesOnUploads() =
        pushApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val sharing = family.adult.accessToken
            val otherPhone = client.login("dima", MEMBER_PASSWORD).body<AuthResult>().accessToken
            val sharingDevice = push.device("upSharing")
            val otherDevice = push.device("upOther")
            client.register(sharing, sharingDevice)
            client.register(otherPhone, otherDevice)
            val devices = mapOf("/upSharing" to sharingDevice, "/upOther" to otherDevice)
            client.setSharing(sharing)
            val now = deps.testClock.now()
            client.upload(sharing, listOf(fix(LAT, LON, now)))

            assertEquals(HttpStatusCode.Accepted, client.refresh(family.kid.accessToken, family.adult.memberId).status)
            assertEquals(listOf("/upSharing"), refreshPushes(devices))

            deps.testClock.advance(30.seconds)
            assertEquals(HttpStatusCode.Accepted, client.refresh(family.admin.accessToken, family.adult.memberId).status)
            assertEquals(emptyList(), refreshPushes(devices))

            // Until a fix arrives, every upload response carries the request; a re-sent fix is not new.
            assertTrue(client.upload(sharing, listOf(fix(LAT, LON, now))).refreshRequested)
            // A fix with a device time far in the past still answers it (server times only).
            val answered = client.upload(sharing, listOf(fix(LAT, LON, now - 10.hours)))
            assertEquals(1, answered.accepted)
            assertFalse(answered.refreshRequested)
            assertFalse(client.upload(sharing, listOf(fix(LAT, LON, now + 31.seconds))).refreshRequested)

            // A minute after the last push, the next refresh pushes again.
            deps.testClock.advance(31.seconds)
            client.refresh(family.kid.accessToken, family.adult.memberId)
            assertEquals(listOf("/upSharing"), refreshPushes(devices))
        }

    @Test
    fun loc011RefreshNeedsASharingVisibleMember() =
        pushApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            // Never shared.
            client.refresh(family.admin.accessToken, family.kid2.memberId).assertError(ErrorCode.CONFLICT)
            client.setSharing(family.adult.accessToken)
            client.setSharing(family.adult.accessToken, SharingStatus.PAUSED)
            client.refresh(family.kid.accessToken, family.adult.memberId).assertError(ErrorCode.CONFLICT)
            client.setSharing(family.kid2.accessToken)
            client.refresh(family.kid.accessToken, family.kid2.memberId).assertError(ErrorCode.NOT_FOUND)
            client.refresh(family.kid.accessToken, "nobody").assertError(ErrorCode.NOT_FOUND)

            deps.createSecondHousehold()
            val stranger = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            client.refresh(stranger.accessToken, family.kid2.memberId).assertError(ErrorCode.NOT_FOUND)
            // A member may refresh themselves.
            assertEquals(HttpStatusCode.Accepted, client.refresh(family.kid2.accessToken, family.kid2.memberId).status)
        }
}
