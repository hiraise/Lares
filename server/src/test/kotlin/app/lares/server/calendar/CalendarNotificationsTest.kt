package app.lares.server.calendar

import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.api.notifications.PushPayload
import app.lares.contract.sync.EntityTypes
import app.lares.server.AppDeps
import app.lares.server.notifications.FakeDevice
import app.lares.server.notifications.FakePushService
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.assertApplied
import app.lares.server.testing.createRelative
import app.lares.server.testing.delete
import app.lares.server.testing.fields
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.jsonStrings
import app.lares.server.testing.memberSession
import app.lares.server.testing.newEvent
import app.lares.server.testing.newOccurrenceRow
import app.lares.server.testing.pushOne
import app.lares.server.testing.recurrenceJson
import app.lares.server.testing.send
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.upsert
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Event notifications on the server (CAL-007, plan M3-03), one test per group of rows of the design table
 * (architecture/calendar.md § Notifications). After each step the test waits for `eventNotifier.awaitIdle()`. The
 * household is in Europe/Moscow; the test clock is Thursday 2026-10-22 13:00 local. The adult makes every change; the
 * kid reads Russian, everyone else English.
 */
class CalendarNotificationsTest {
    private val push = FakePushService()
    private val devices = mutableMapOf<String, FakeDevice>()

    @AfterTest
    fun stop() = push.close()

    private data class Notice(
        val path: String,
        val payload: PushPayload,
    )

    private class Family(
        val admin: AuthResult,
        val adult: AuthResult,
        val kid: AuthResult,
        val kid2: AuthResult,
        val grandma: String,
    ) {
        val adultId get() = adult.me.memberId
    }

    private fun pushApp(block: suspend ApplicationTestBuilder.(AppDeps) -> Unit) =
        testApp(configure = { it.copy(pushAllowedHosts = setOf("127.0.0.1")) }, block = block)

    private suspend fun HttpClient.register(
        token: String,
        topic: String,
        language: String = "en",
    ) {
        val device = push.device(topic, language)
        devices["/$topic"] = device
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/me/push", token, device.registration).status)
    }

    /** Admin, an adult, two kids (ru, en) with devices named after them, and Grandma, a relative without an account. */
    private suspend fun HttpClient.family(deps: AppDeps): Family {
        val admin = setup()
        val adults = setOf(deps.groupId(admin.householdUuid, ADULTS))
        val kids = setOf(deps.groupId(admin.householdUuid, KIDS))
        val family =
            Family(
                admin,
                memberSession(admin.accessToken, "adult", adults),
                memberSession(admin.accessToken, "kid", kids),
                memberSession(admin.accessToken, "kid2", kids),
                deps.createRelative(admin.householdUuid, "Grandma").toString(),
            )
        register(family.admin.accessToken, "admin")
        register(family.adult.accessToken, "adult")
        register(family.kid.accessToken, "kid", "ru")
        register(family.kid2.accessToken, "kid2")
        return family
    }

    /** Notifications sent since the last call, by device path. */
    private suspend fun AppDeps.notices(): Map<String, PushPayload> {
        eventNotifier.awaitIdle()
        val notices =
            push.drain().mapNotNull { request ->
                val payload = devices.getValue(request.path).decrypt(request.body)
                if (payload.kind == PushPayload.KIND_NOTIFICATION) Notice(request.path, payload) else null
            }
        assertEquals(notices.size, notices.map { it.path }.toSet().size, "one notice per device: $notices")
        return notices.associate { it.path to it.payload }
    }

    private fun assertNotice(
        payload: PushPayload?,
        title: String,
        body: String,
        eventId: String,
    ) {
        assertEquals(NotificationType.EVENT_CHANGED, payload?.type, "$payload")
        assertEquals(title, payload?.title)
        assertEquals(body, payload?.body)
        assertEquals(EntityTypes.EVENT, payload?.entityType)
        assertEquals(eventId, payload?.entityId)
    }

    private fun weekly(day: String = "MONDAY") = recurrenceJson("fixed", "week", 1, listOf(day))

    /** CAL-007 rows "event create": member participants with a device, not the actor nor a relative; nothing for the past. */
    @Test
    fun cal007AddedOnCreateToMemberParticipants() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            deps.notices()

            val dentist = deps.newEvent(f.adultId, participantIds = listOf(f.adult.me.personId, f.kid.me.personId, f.grandma))
            client.pushOne(f.adult.accessToken, dentist).assertApplied()
            val created = deps.notices()
            assertEquals(setOf("/kid"), created.keys)
            assertNotice(created["/kid"], "Новое событие", "Dentist · пн, 2 нояб., 10:00", dentist.id)

            val past = deps.newEvent(f.adultId, startDate = "2026-10-01", participantIds = listOf(f.kid.me.personId))
            client.pushOne(f.adult.accessToken, past).assertApplied()
            assertEquals(emptyMap(), deps.notices(), "past one-off event")

            // No participants = the whole family.
            val picnic = deps.newEvent(f.adultId, "Picnic", "2026-11-05", allDay = true)
            client.pushOne(f.adult.accessToken, picnic).assertApplied()
            val everyone = deps.notices()
            assertEquals(setOf("/admin", "/kid", "/kid2"), everyone.keys)
            assertNotice(everyone["/kid2"], "New event", "Picnic · Thu, Nov 5", picnic.id)
            assertNotice(everyone["/kid"], "Новое событие", "Picnic · чт, 5 нояб.", picnic.id)
        }

    /** CAL-007 row "event update": added to new participants; changed only when the date, time or place changes. */
    @Test
    fun cal007AddedOnUpdateAndChangedOnScheduleOnly() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val event = deps.newEvent(f.adultId, participantIds = listOf(f.kid.me.personId))
            client.pushOne(f.adult.accessToken, event).assertApplied()
            deps.notices()

            val addKid2 = fields("participantIds" to jsonStrings(f.kid.me.personId, f.kid2.me.personId))
            client.pushOne(f.adult.accessToken, deps.upsert(EntityTypes.EVENT, event.id, addKid2)).assertApplied()
            val added = deps.notices()
            assertEquals(setOf("/kid2"), added.keys)
            assertNotice(added["/kid2"], "New event", "Dentist · Mon, Nov 2, 10:00", event.id)

            val rename = fields("title" to "Orthodontist", "notes" to "Bring the card")
            client.pushOne(f.adult.accessToken, deps.upsert(EntityTypes.EVENT, event.id, rename)).assertApplied()
            assertEquals(emptyMap(), deps.notices(), "title and notes only")

            val later = fields("startTime" to "12:00", "endTime" to "13:00")
            client.pushOne(f.adult.accessToken, deps.upsert(EntityTypes.EVENT, event.id, later)).assertApplied()
            val changed = deps.notices()
            assertEquals(setOf("/kid", "/kid2"), changed.keys)
            assertNotice(changed["/kid"], "Событие изменено", "Orthodontist · пн, 2 нояб., 12:00", event.id)
            assertNotice(changed["/kid2"], "Event changed", "Orthodontist · Mon, Nov 2, 12:00", event.id)
        }

    /** CAL-007 rows "create with splitFrom", the split's second half, and "series cut" (cancelled «from»). */
    @Test
    fun cal007SplitIsOneNoticeAndCutIsCancelledFrom() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val series =
                deps.newEvent(f.adultId, "Swimming", participantIds = listOf(f.kid.me.personId), extra = mapOf("recurrence" to weekly()))
            client.pushOne(f.adult.accessToken, series).assertApplied()
            deps.notices()

            // "This and following" from Nov 16 at 18:00, kid2 joins: the client pushes the new half first.
            val split =
                deps.newEvent(
                    f.adultId,
                    "Swimming",
                    "2026-11-16",
                    participantIds = listOf(f.kid.me.personId, f.kid2.me.personId),
                    extra = mapOf("recurrence" to weekly(), "splitFrom" to series.id, "startTime" to "18:00", "endTime" to "19:00"),
                )
            client.pushOne(f.adult.accessToken, split).assertApplied()
            client
                .pushOne(f.adult.accessToken, deps.upsert(EntityTypes.EVENT, series.id, fields("repeatUntil" to "2026-11-15")))
                .assertApplied()
            val splitNotices = deps.notices()
            assertEquals(setOf("/kid", "/kid2"), splitNotices.keys)
            assertNotice(splitNotices["/kid"], "Событие изменено", "Swimming · пн, 16 нояб., 18:00", split.id)
            assertNotice(splitNotices["/kid2"], "New event", "Swimming · Mon, Nov 16, 18:00", split.id)

            // The series ends earlier (no split): cancelled from the first occurrence it loses.
            client
                .pushOne(f.adult.accessToken, deps.upsert(EntityTypes.EVENT, split.id, fields("repeatUntil" to "2026-11-30")))
                .assertApplied()
            val cut = deps.notices()
            assertEquals(setOf("/kid", "/kid2"), cut.keys)
            assertNotice(cut["/kid"], "Событие отменено", "Swimming · с пн, 7 дек., 18:00", split.id)
            assertNotice(cut["/kid2"], "Event cancelled", "Swimming · from Mon, Dec 7, 18:00", split.id)
        }

    /** CAL-007 rows "occurrence row" (cancelled, moved, participants, reset) and "event delete". */
    @Test
    fun cal007OccurrenceRowsAndDelete() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val piano =
                deps.newEvent(f.adultId, "Piano", participantIds = listOf(f.kid2.me.personId), extra = mapOf("recurrence" to weekly()))
            client.pushOne(f.adult.accessToken, piano).assertApplied()
            deps.notices()

            client.pushOne(f.adult.accessToken, deps.newOccurrenceRow(piano.id, "2026-11-09", mapOf("cancelled" to true))).assertApplied()
            val cancelled = deps.notices()
            assertEquals(setOf("/kid2"), cancelled.keys)
            assertNotice(cancelled["/kid2"], "Event cancelled", "Piano · Mon, Nov 9, 10:00", piano.id)

            val moved =
                mapOf("startDate" to "2026-11-17", "endDate" to "2026-11-17", "startTime" to "12:00", "endTime" to "13:00")
            client.pushOne(f.adult.accessToken, deps.newOccurrenceRow(piano.id, "2026-11-16", moved)).assertApplied()
            val changed = deps.notices()
            assertEquals(setOf("/kid2"), changed.keys)
            assertNotice(changed["/kid2"], "Event changed", "Piano · Tue, Nov 17, 12:00", piano.id)

            // Resets (back to the series) tell nobody.
            val reset = mapOf("startDate" to null, "endDate" to null, "startTime" to null, "endTime" to null)
            client.pushOne(f.adult.accessToken, deps.newOccurrenceRow(piano.id, "2026-11-16", reset)).assertApplied()
            client.pushOne(f.adult.accessToken, deps.newOccurrenceRow(piano.id, "2026-11-09", mapOf("cancelled" to false))).assertApplied()
            assertEquals(emptyMap(), deps.notices(), "resets")

            val withKid = mapOf("participantIds" to jsonStrings(f.kid2.me.personId, f.kid.me.personId))
            client.pushOne(f.adult.accessToken, deps.newOccurrenceRow(piano.id, "2026-11-23", withKid)).assertApplied()
            val added = deps.notices()
            assertEquals(setOf("/kid"), added.keys)
            assertNotice(added["/kid"], "Новое событие", "Piano · пн, 23 нояб., 10:00", piano.id)

            client.pushOne(f.adult.accessToken, deps.delete(EntityTypes.EVENT, piano.id)).assertApplied()
            val deleted = deps.notices()
            assertEquals(setOf("/kid2"), deleted.keys)
            assertNotice(deleted["/kid2"], "Event cancelled", "Piano · Mon, Nov 2, 10:00", piano.id)
        }

    /** CAL-007: a muted `event.changed`, a member without `calendar.view` and memorable dates get nothing. */
    @Test
    fun cal007MutedNoViewAndMemorableDatesSendNothing() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            val guest = client.memberSession(f.admin.accessToken, "guest", emptySet())
            client.register(guest.accessToken, "guest")
            client.send(
                HttpMethod.Put,
                "/api/v1/me/notification-settings",
                f.kid.accessToken,
                NotificationSettings(setOf(NotificationType.EVENT_CHANGED)),
            )
            deps.notices()

            val event = deps.newEvent(f.adultId, participantIds = listOf(f.kid.me.personId, guest.me.personId))
            client.pushOne(f.adult.accessToken, event).assertApplied()
            assertEquals(emptyMap(), deps.notices(), "muted and no calendar.view")

            val wedding =
                deps.newEvent(
                    f.adultId,
                    "Wedding",
                    "2001-06-16",
                    allDay = true,
                    participantIds = listOf(f.kid2.me.personId),
                    extra = mapOf("kind" to "memorable_date", "recurrence" to recurrenceJson("fixed", "year")),
                )
            client.pushOne(f.adult.accessToken, wedding).assertApplied()
            client
                .pushOne(
                    f.adult.accessToken,
                    deps.upsert(
                        EntityTypes.EVENT,
                        wedding.id,
                        fields(
                            "startDate" to "2001-06-17",
                            "endDate" to "2001-06-17",
                        ),
                    ),
                ).assertApplied()
            client.pushOne(f.adult.accessToken, deps.delete(EntityTypes.EVENT, wedding.id)).assertApplied()
            assertEquals(emptyMap(), deps.notices(), "memorable date")
        }
}
