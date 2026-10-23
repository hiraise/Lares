package app.lares.server.notifications

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.api.notifications.PushPayload
import app.lares.contract.api.notifications.PushRegistrationRequest
import app.lares.contract.api.notifications.PushTestResponse
import app.lares.contract.api.notifications.PushTestResult
import app.lares.server.AppDeps
import app.lares.server.testing.ADULTS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.assertError
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.memberSession
import app.lares.server.testing.newTag
import app.lares.server.testing.pushOne
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Push registration, test push, muting, expiry and sync tickles (FND-051, FND-052, plan M0-09). */
class NotificationsTest {
    private val push = FakePushService()

    @AfterTest
    fun stop() = push.close()

    private fun pushApp(block: suspend ApplicationTestBuilder.(AppDeps) -> Unit) =
        testApp(configure = { it.copy(pushAllowedHosts = setOf("127.0.0.1")) }, block = block)

    private suspend fun HttpClient.register(
        token: String,
        device: FakeDevice,
    ) = assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/me/push", token, device.registration).status)

    private suspend fun HttpClient.testPush(token: String): PushTestResult =
        send(HttpMethod.Post, "/api/v1/me/push/test", token).body<PushTestResponse>().result

    @Test
    fun testPushIsEncryptedAndRenderedInDeviceLanguage() =
        pushApp { _ ->
            val client = jsonClient()
            val admin = client.setup()
            val device = push.device("upTest", language = "ru-RU")
            client.register(admin.accessToken, device)

            assertEquals(PushTestResult.SENT, client.testPush(admin.accessToken))

            val request = push.next()
            assertEquals("/upTest", request.path)
            assertEquals("aes128gcm", request.headers["content-encoding"])
            assertEquals("86400", request.headers["ttl"])
            assertEquals("high", request.headers["urgency"])
            assertTrue(request.headers["authorization"].orEmpty().startsWith("vapid t="))
            val payload = device.decrypt(request.body)
            assertEquals(PushPayload.KIND_NOTIFICATION, payload.kind)
            assertEquals(NotificationType.SYSTEM_TEST, payload.type)
            assertEquals("Тестовое уведомление", payload.title)
        }

    @Test
    fun mutedTypeIsNotSent() =
        pushApp { _ ->
            val client = jsonClient()
            val admin = client.setup()
            client.register(admin.accessToken, push.device("upMuted"))
            val muted = NotificationSettings(setOf(NotificationType.SYSTEM_TEST))

            val saved =
                client
                    .send(
                        HttpMethod.Put,
                        "/api/v1/me/notification-settings",
                        admin.accessToken,
                        muted,
                    ).body<NotificationSettings>()
            assertEquals(muted, saved)
            assertEquals(
                muted,
                client.send(HttpMethod.Get, "/api/v1/me/notification-settings", admin.accessToken).body<NotificationSettings>(),
            )

            assertEquals(PushTestResult.MUTED, client.testPush(admin.accessToken))
            assertNull(push.nextOrNull())

            client.send(HttpMethod.Put, "/api/v1/me/notification-settings", admin.accessToken, NotificationSettings())
            assertEquals(PushTestResult.SENT, client.testPush(admin.accessToken))
        }

    @Test
    fun goneEndpointDeletesRegistration() =
        pushApp { _ ->
            val client = jsonClient()
            val admin = client.setup()
            client.register(admin.accessToken, push.device("upGone"))
            push.status = HttpStatusCode.Gone

            assertEquals(PushTestResult.EXPIRED, client.testPush(admin.accessToken))
            assertEquals(PushTestResult.NOT_REGISTERED, client.testPush(admin.accessToken))
        }

    @Test
    fun unregisterAndLogoutRemoveRegistration() =
        pushApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            client.register(admin.accessToken, push.device("upA"))
            assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "/api/v1/me/push", admin.accessToken).status)
            assertEquals(PushTestResult.NOT_REGISTERED, client.testPush(admin.accessToken))

            client.register(admin.accessToken, push.device("upB"))
            client.send(HttpMethod.Post, "/api/v1/auth/logout", admin.accessToken)
            assertEquals(0L, deps.db.dbQuery { PushRegistrationTable.selectAll().count() })
        }

    @Test
    fun registrationIsValidated() =
        testApp { _ ->
            val client = jsonClient()
            val admin = client.setup()
            val good = push.device("upX").registration

            // Not allow-listed in this app: 127.0.0.1 is loopback.
            client.send(HttpMethod.Put, "/api/v1/me/push", admin.accessToken, good).assertError(ErrorCode.VALIDATION_FAILED)
            val private = good.copy(endpoint = "https://10.1.2.3/upX")
            client.send(HttpMethod.Put, "/api/v1/me/push", admin.accessToken, private).assertError(ErrorCode.VALIDATION_FAILED)
            val badKey = good.copy(endpoint = "https://93.184.215.14/upX", p256dh = "AAAA")
            val error = client.send(HttpMethod.Put, "/api/v1/me/push", admin.accessToken, badKey).assertError(ErrorCode.VALIDATION_FAILED)
            assertEquals(setOf("p256dh"), error.error.fields.keys)
            val noScheme: PushRegistrationRequest = good.copy(endpoint = "ftp://example.org/up")
            client.send(HttpMethod.Put, "/api/v1/me/push", admin.accessToken, noScheme).assertError(ErrorCode.VALIDATION_FAILED)
        }

    @Test
    fun householdChangeTicklesEverySessionIncludingTheOriginating() =
        pushApp { deps ->
            val client = jsonClient()
            val admin = client.setup()
            val bob = client.memberSession(admin.accessToken, "bob", setOf(deps.groupId(admin.householdUuid, ADULTS)))
            // The setup writes opened a tickle window; let its trailing tickle go out before anyone is registered.
            deps.testClock.advance(31.seconds)
            delay(1500.milliseconds)
            val adminDevice = push.device("upAdmin")
            val bobDevice = push.device("upBob")
            client.register(admin.accessToken, adminDevice)
            client.register(bob.accessToken, bobDevice)
            // The window has ended, so the next change is sent at once.
            deps.testClock.advance(31.seconds)

            val seq = client.pushOne(bob.accessToken, deps.newTag("Car")).assertApplied()!!

            val received = listOf(push.next(), push.next())
            val payloads =
                received.associate { request ->
                    val device = if (request.path == "/upAdmin") adminDevice else bobDevice
                    request.path to device.decrypt(request.body)
                }
            assertEquals(setOf("/upAdmin", "/upBob"), payloads.keys)
            payloads.values.forEach { assertEquals(PushPayload(PushPayload.KIND_SYNC, seq = seq), it) }
            assertEquals("600", received.first().headers["ttl"])

            // A second change within 30 s is deferred until the window ends.
            client.pushOne(bob.accessToken, deps.newTag("Bike")).assertApplied()
            assertNull(push.nextOrNull(1500.milliseconds))
            deps.testClock.advance(30.seconds)
            push.next()
            push.next()
        }
}
