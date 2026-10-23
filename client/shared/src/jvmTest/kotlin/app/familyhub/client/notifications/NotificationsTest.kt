package app.familyhub.client.notifications

import app.familyhub.client.api.FamilyHubApi
import app.familyhub.client.api.createHttpClient
import app.familyhub.client.auth.InMemoryTokenStore
import app.familyhub.client.auth.Tokens
import app.familyhub.client.platform.InMemoryServerStore
import app.familyhub.client.sync.testLocalDb
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.notifications.NotificationChannel
import app.familyhub.contract.api.notifications.NotificationSettings
import app.familyhub.contract.api.notifications.NotificationType
import app.familyhub.contract.api.notifications.PushPayload
import app.familyhub.contract.api.notifications.PushRegistrationRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Push payload routing, reminder storage and re-registration, endpoint upload (plan M0-09). */
class NotificationsTest {
    private val now = Instant.parse("2026-10-23T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }
    private val localDb = testLocalDb()

    private class Shown(
        val id: String,
        val channel: NotificationChannel,
        val title: String,
        val body: String,
    )

    private val shown = mutableListOf<Shown>()
    private val poster = NotificationPoster { id, channel, title, body -> shown += Shown(id, channel, title, body) }

    private class RecordingSync : BackgroundSync {
        var requests = 0

        override fun schedulePeriodic() = Unit

        override fun cancelAll() = Unit

        override fun requestNow() {
            requests++
        }
    }

    private class RecordingAlarms : AlarmScheduler {
        val set = mutableMapOf<String, Instant>()

        override fun set(
            id: String,
            at: Instant,
        ) {
            set[id] = at
        }

        override fun cancel(id: String) {
            set.remove(id)
        }
    }

    private val requests = mutableListOf<HttpRequestData>()
    private var online = true
    private var mutedOnServer = emptySet<NotificationType>()

    private val api: FamilyHubApi =
        run {
            val engine =
                MockEngine { request ->
                    requests += request
                    if (!online) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
                    when (request.url.encodedPath) {
                        "/api/v1/me/notification-settings" -> {
                            if (request.method == HttpMethod.Put) {
                                mutedOnServer = FamilyHubJson.decodeFromString(NotificationSettings.serializer(), request.bodyText()).muted
                            }
                            val body = FamilyHubJson.encodeToString(NotificationSettings.serializer(), NotificationSettings(mutedOnServer))
                            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }

                        else -> {
                            respond("", HttpStatusCode.NoContent)
                        }
                    }
                }
            val tokens = InMemoryTokenStore()
            kotlinx.coroutines.runBlocking { tokens.save(Tokens("access", now + 1.hours, "refresh", now + 100.hours, "session")) }
            FamilyHubApi(createHttpClient(engine), InMemoryServerStore("http://family.test"), tokens, clock)
        }

    private val settings = NotificationSettingsRepository(api, localDb)

    private fun HttpRequestData.bodyText(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun payload(value: PushPayload) = FamilyHubJson.encodeToString(PushPayload.serializer(), value).encodeToByteArray()

    @Test
    fun syncPayloadRequestsBackgroundSync() =
        runTest {
            val sync = RecordingSync()
            PushMessageHandler(poster, sync, settings).handle(payload(PushPayload(PushPayload.KIND_SYNC, seq = 7)))
            assertEquals(1, sync.requests)
            assertTrue(shown.isEmpty())
        }

    @Test
    fun notificationPayloadIsShownOnItsChannelUnlessMuted() =
        runTest {
            val sync = RecordingSync()
            val handler = PushMessageHandler(poster, sync, settings)
            val message =
                PushPayload(PushPayload.KIND_NOTIFICATION, type = NotificationType.TASK_ASSIGNED, title = "T", body = "B", entityId = "e1")

            handler.handle(payload(message))
            assertEquals("task.assigned:e1", shown.single().id)
            assertEquals(NotificationChannel.FAMILY_ACTIVITY, shown.single().channel)
            assertEquals("T", shown.single().title)

            settings.setMuted(NotificationType.TASK_ASSIGNED, muted = true)
            assertEquals(setOf(NotificationType.TASK_ASSIGNED), mutedOnServer)
            handler.handle(payload(message))
            assertEquals(1, shown.size)
            assertEquals(0, sync.requests)
        }

    @Test
    fun unknownTypesKindsAndGarbageAreIgnored() =
        runTest {
            val sync = RecordingSync()
            val handler = PushMessageHandler(poster, sync, settings)
            handler.handle("""{"kind":"notification","type":"future.thing","title":"x"}""".encodeToByteArray())
            handler.handle("""{"kind":"future-kind"}""".encodeToByteArray())
            handler.handle("not json".encodeToByteArray())
            assertTrue(shown.isEmpty())
            assertEquals(0, sync.requests)
        }

    @Test
    fun remindersAreStoredReRegisteredAndFiredOnce() =
        runTest {
            val alarms = RecordingAlarms()
            val reminders = Reminders(localDb, alarms, poster, clock)
            reminders.schedule("future", now + 30.minutes, "Later", "body")
            reminders.schedule("missed", now - 5.minutes, "Missed", "body", NotificationChannel.SYSTEM)
            reminders.schedule("cancelled", now + 1.hours, "Gone", "body")
            reminders.cancel("cancelled")

            // After a reboot the OS has forgotten every alarm.
            alarms.set.clear()
            reminders.rescheduleAll()
            assertEquals(mapOf("future" to now + 30.minutes, "missed" to now), alarms.set)

            assertTrue(reminders.fire("missed"))
            assertEquals(NotificationChannel.SYSTEM, shown.single().channel)
            assertFalse(reminders.fire("missed"))
            assertFalse(reminders.fire("cancelled"))
            assertEquals(1, shown.size)
        }

    @Test
    fun endpointIsUploadedAgainWhenOfflineOrLanguageChanged() =
        runTest {
            var language = "en"
            val endpoints = PushEndpoints(api, localDb) { language }
            online = false
            endpoints.onNewEndpoint("https://ntfy.example.org/upX", "key", "auth")
            online = true

            fun registrations() = requests.filter { it.url.encodedPath == "/api/v1/me/push" && it.method == HttpMethod.Put }

            endpoints.uploadIfNeeded()
            assertEquals(2, registrations().size)
            endpoints.uploadIfNeeded()
            assertEquals(2, registrations().size)

            language = "ru"
            endpoints.uploadIfNeeded()
            val sent = FamilyHubJson.decodeFromString(PushRegistrationRequest.serializer(), registrations().last().bodyText())
            assertEquals(PushRegistrationRequest("https://ntfy.example.org/upX", "key", "auth", "ru"), sent)

            endpoints.onUnregistered()
            endpoints.uploadIfNeeded()
            assertEquals(3, registrations().size)
            assertEquals(HttpMethod.Delete, requests.last().method)
        }
}
