package app.lares.client.notifications

import app.lares.client.api.LaresApi
import app.lares.client.api.createHttpClient
import app.lares.client.auth.InMemoryTokenStore
import app.lares.client.auth.Tokens
import app.lares.client.data.tasks.TaskReminderPlanner
import app.lares.client.platform.InMemoryServerStore
import app.lares.client.platform.NavTarget
import app.lares.client.platform.PendingNavigation
import app.lares.client.sync.testLocalDb
import app.lares.contract.api.LaresJson
import app.lares.contract.api.notifications.NotificationChannel
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.api.notifications.PushPayload
import app.lares.contract.api.notifications.PushRegistrationRequest
import app.lares.contract.sync.EntityTypes
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
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Push payload routing, reminder storage and re-registration, endpoint upload (plan M0-09), tap targets (M1-07). */
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
        val target: NotificationTarget?,
    )

    private val shown = mutableListOf<Shown>()
    private val poster = NotificationPoster { id, channel, title, body, target -> shown += Shown(id, channel, title, body, target) }

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

    private val api: LaresApi =
        run {
            val engine =
                MockEngine { request ->
                    requests += request
                    if (!online) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
                    when (request.url.encodedPath) {
                        "/api/v1/me/notification-settings" -> {
                            if (request.method == HttpMethod.Put) {
                                mutedOnServer = LaresJson.decodeFromString(NotificationSettings.serializer(), request.bodyText()).muted
                            }
                            val body = LaresJson.encodeToString(NotificationSettings.serializer(), NotificationSettings(mutedOnServer))
                            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        }

                        else -> {
                            respond("", HttpStatusCode.NoContent)
                        }
                    }
                }
            val tokens = InMemoryTokenStore()
            kotlinx.coroutines.runBlocking { tokens.save(Tokens("access", now + 1.hours, "refresh", now + 100.hours, "session")) }
            LaresApi(createHttpClient(engine), InMemoryServerStore("http://family.test"), tokens, clock)
        }

    private val settings = NotificationSettingsRepository(api, localDb)

    private fun HttpRequestData.bodyText(): String = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private fun payload(value: PushPayload) = LaresJson.encodeToString(PushPayload.serializer(), value).encodeToByteArray()

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
    fun taskNotificationsAndRemindersOpenTheirTask() =
        runTest {
            val handler = PushMessageHandler(poster, RecordingSync(), settings)
            handler.handle(
                payload(
                    PushPayload(
                        PushPayload.KIND_NOTIFICATION,
                        type = NotificationType.TASK_OVERDUE,
                        title = "T",
                        entityType = EntityTypes.TASK,
                        entityId = "t9",
                    ),
                ),
            )
            handler.handle(payload(PushPayload(PushPayload.KIND_NOTIFICATION, type = NotificationType.SYSTEM_TEST, title = "Test")))
            assertEquals(listOf(NotificationTarget(EntityTypes.TASK, "t9"), null), shown.map { it.target })

            val reminders = Reminders(localDb, RecordingAlarms(), poster, clock)
            reminders.schedule("${TaskReminderPlanner.ID_PREFIX}t7", now + 5.minutes, "Trash", "Due today at 20:00")
            reminders.schedule("diagnostics.test", now + 5.minutes, "Test", "body")
            reminders.fire("${TaskReminderPlanner.ID_PREFIX}t7")
            reminders.fire("diagnostics.test")
            assertEquals(listOf(NotificationTarget(EntityTypes.TASK, "t7"), null), shown.drop(2).map { it.target })
            assertEquals("reminder:task:t7", shown[2].id)
        }

    @Test
    fun pendingNavigationKeepsATaskTargetUntilConsumed() {
        val pending = PendingNavigation()
        assertFalse(pending.submit("widget", "l1"), "no screen for that type yet")
        assertNull(pending.target.value)
        assertTrue(pending.submit(EntityTypes.TASK, "t1"))
        val target = assertIs<NavTarget.Task>(pending.target.value)
        assertEquals("t1", target.id)
        assertTrue(pending.consume(target))
        assertFalse(pending.consume(target), "handled once")
        assertNull(pending.target.value)
    }

    @Test
    fun pendingNavigationMapsLocationToTheMapAtTheMember() {
        val pending = PendingNavigation()
        assertTrue(pending.submit(PendingNavigation.LOCATION, "m2"))
        assertEquals(NavTarget.MapMember("m2"), pending.target.value)
        assertEquals("location", PendingNavigation.LOCATION, "the server's entityType of location notifications")
    }

    @Test
    fun pendingNavigationMapsEventsAndPersons() {
        val pending = PendingNavigation()
        val id = "01928c4e-0000-7000-8000-00000000e001"
        assertTrue(pending.submit(EntityTypes.EVENT, id))
        assertEquals(NavTarget.Event(id, null), pending.target.value, "from push: the next occurrence")
        assertTrue(pending.submit(EntityTypes.EVENT, "$id:2026-10-28"))
        assertEquals(NavTarget.Event(id, LocalDate(2026, 10, 28)), pending.target.value, "a reminder names its occurrence")
        assertTrue(pending.submit(EntityTypes.EVENT, "$id:garbage"))
        assertEquals(NavTarget.Event(id, null), pending.target.value)
        assertTrue(pending.submit(EntityTypes.PERSON, "p4"))
        assertEquals(NavTarget.Person("p4"), pending.target.value)
        pending.submit(NavTarget.EventGone)
        assertTrue(pending.consume(NavTarget.EventGone))
        assertNull(pending.target.value)
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
            val sent = LaresJson.decodeFromString(PushRegistrationRequest.serializer(), registrations().last().bodyText())
            assertEquals(PushRegistrationRequest("https://ntfy.example.org/upX", "key", "auth", "ru"), sent)

            endpoints.onUnregistered()
            endpoints.uploadIfNeeded()
            assertEquals(3, registrations().size)
            assertEquals(HttpMethod.Delete, requests.last().method)
        }
}
