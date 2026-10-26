package app.lares.server.location

import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.api.notifications.PushPayload
import app.lares.server.AppDeps
import app.lares.server.notifications.FakeDevice
import app.lares.server.notifications.FakePushService
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.administratorsId
import app.lares.server.testing.assertApplied
import app.lares.server.testing.fix
import app.lares.server.testing.groupId
import app.lares.server.testing.householdUuid
import app.lares.server.testing.jsonClient
import app.lares.server.testing.memberSession
import app.lares.server.testing.newPlace
import app.lares.server.testing.newPlaceAlert
import app.lares.server.testing.pushOne
import app.lares.server.testing.putRules
import app.lares.server.testing.send
import app.lares.server.testing.setSharing
import app.lares.server.testing.setup
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upload
import io.ktor.client.HttpClient
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Location notifications (LOC-021, LOC-012, plan M4-04). The household is in Europe/Moscow; the test clock starts at
 * 05:55 UTC (08:55 local). The administrator and the adult Дима read Russian, the second administrator English; the
 * kids Аня and Тимур have devices too, to show they get nothing about themselves.
 */
class LocationNotificationsTest {
    private val push = FakePushService()
    private val devices = mutableMapOf<String, FakeDevice>()

    @AfterTest
    fun stop() = push.close()

    private class Family(
        val admin: AuthResult,
        val admin2: AuthResult,
        val adult: AuthResult,
        val anya: AuthResult,
        val timur: AuthResult,
        val school: String,
        val home: String,
    )

    private fun pushApp(block: suspend ApplicationTestBuilder.(AppDeps) -> Unit) =
        testApp(configure = { it.copy(pushAllowedHosts = setOf("127.0.0.1")) }, block = block)

    private suspend fun HttpClient.register(
        token: String,
        topic: String,
        language: String,
    ) {
        val device = push.device(topic, language)
        devices["/$topic"] = device
        assertEquals(HttpStatusCode.NoContent, send(HttpMethod.Put, "/api/v1/me/push", token, device.registration).status)
    }

    private suspend fun HttpClient.family(deps: AppDeps): Family {
        deps.testClock.current = Instant.parse("2026-10-22T05:55:00Z")
        val admin = setup()
        val household = admin.householdUuid
        val admins = setOf(deps.administratorsId(household))
        val adults = setOf(deps.groupId(household, ADULTS))
        val kids = setOf(deps.groupId(household, KIDS))
        val school = deps.newPlace(admin.me.memberId, name = "Школа №12")
        val home = deps.newPlace(admin.me.memberId, name = "Дом", latitude = LAT + 0.05)
        pushOne(admin.accessToken, school).assertApplied()
        pushOne(admin.accessToken, home).assertApplied()
        val family =
            Family(
                admin = admin,
                admin2 = memberSession(admin.accessToken, "mama", admins, "Мама"),
                adult = memberSession(admin.accessToken, "dima", adults, "Дима"),
                anya = memberSession(admin.accessToken, "anya", kids, "Аня"),
                timur = memberSession(admin.accessToken, "timur", kids, "Тимур"),
                school = school.id,
                home = home.id,
            )
        register(family.admin.accessToken, "admin", "ru")
        register(family.admin2.accessToken, "admin2", "en")
        register(family.adult.accessToken, "adult", "ru-RU")
        register(family.anya.accessToken, "anya", "ru")
        register(family.timur.accessToken, "timur", "ru")
        return family
    }

    /** Location notifications sent since the last call, by device path. */
    private suspend fun AppDeps.notices(): Map<String, PushPayload> {
        locationNotifier.awaitIdle()
        val notices =
            push.drain().mapNotNull { request ->
                val payload = devices.getValue(request.path).decrypt(request.body)
                if (payload.kind == PushPayload.KIND_NOTIFICATION) request.path to payload else null
            }
        assertEquals(notices.size, notices.map { it.first }.toSet().size, "one notice per device: $notices")
        return notices.toMap()
    }

    private fun assertNotice(
        payload: PushPayload?,
        type: NotificationType,
        title: String,
        body: String,
        member: AuthResult,
    ) {
        assertEquals(type, payload?.type, "$payload")
        assertEquals(title, payload?.title)
        assertEquals(body, payload?.body)
        assertEquals("location", payload?.entityType)
        assertEquals(member.me.memberId, payload?.entityId)
    }

    /** [metres] north of the school's centre at [utc] (`HH:mm`, the test day). */
    private fun at(
        utc: String,
        metres: Double = 0.0,
    ): PositionFix = fix(LAT + metres / 111_195.0, LON, Instant.parse("2026-10-22T$utc:00Z"))

    private suspend fun HttpClient.subscribe(
        deps: AppDeps,
        subscriber: AuthResult,
        member: AuthResult,
        vararg placeIds: String,
    ) {
        pushOne(subscriber.accessToken, deps.newPlaceAlert(member.me.memberId, subscriber.me.memberId, placeIds.toSet())).assertApplied()
    }

    /** Аня is known to be outside the school at 08:40 local, then arrives at 08:52. */
    private suspend fun HttpClient.anyaArrives(f: Family) {
        setSharing(f.anya.accessToken)
        upload(f.anya.accessToken, listOf(at("05:40", 1000.0)))
        upload(f.anya.accessToken, listOf(at("05:52"), at("05:53")))
    }

    @Test
    fun loc021SubscribersGetArrivalAndDepartureInTheirLanguage() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            client.subscribe(deps, f.adult, f.anya, f.school)
            client.subscribe(deps, f.admin2, f.anya, f.school, f.home)
            deps.notices()

            client.anyaArrives(f)
            val arrived = deps.notices()
            assertEquals(setOf("/adult", "/admin2"), arrived.keys)
            assertNotice(arrived["/adult"], NotificationType.LOCATION_PLACE, "Аня · Школа №12", "Прибытие в 08:52", f.anya)
            assertNotice(arrived["/admin2"], NotificationType.LOCATION_PLACE, "Аня · Школа №12", "Arrived at 08:52", f.anya)

            deps.testClock.current = Instant.parse("2026-10-22T06:10:00Z")
            client.upload(f.anya.accessToken, listOf(at("06:05", 1000.0), at("06:06", 1000.0)))
            val left = deps.notices()
            assertNotice(left["/adult"], NotificationType.LOCATION_PLACE, "Аня · Школа №12", "Уход в 09:05", f.anya)
            assertNotice(left["/admin2"], NotificationType.LOCATION_PLACE, "Аня · Школа №12", "Left at 09:05", f.anya)
        }

    @Test
    fun loc021NothingForAnotherPlaceAMutedTypeOrAMemberNoLongerSeen() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            // Дима wants only «Дом»; Мама muted place notifications; the administrator subscribes to the school.
            client.subscribe(deps, f.adult, f.anya, f.home)
            client.subscribe(deps, f.admin2, f.anya, f.school)
            client.subscribe(deps, f.admin, f.anya, f.school)
            client.send(
                HttpMethod.Put,
                "/api/v1/me/notification-settings",
                f.admin2.accessToken,
                NotificationSettings(setOf(NotificationType.LOCATION_PLACE)),
            )
            deps.notices()
            client.anyaArrives(f)
            assertEquals(setOf("/admin"), deps.notices().keys)

            // Without rules Дима no longer sees Аня; the administrator always does.
            client.subscribe(deps, f.adult, f.anya, f.home, f.school)
            assertEquals(HttpStatusCode.NoContent, client.putRules(f.admin.accessToken, emptyList()).status)
            deps.notices()
            deps.testClock.current = Instant.parse("2026-10-22T06:05:00Z")
            client.upload(f.anya.accessToken, listOf(at("06:00", 1000.0), at("06:01", 1000.0)))
            assertEquals(setOf("/admin"), deps.notices().keys)
        }

    @Test
    fun loc021AnOldBacklogUpdatesPresenceSilently() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            client.subscribe(deps, f.adult, f.anya, f.school)
            deps.notices()
            client.anyaArrives(f)
            deps.notices()

            // Sent at 06:12 after a network gap: the departure at 05:54 is older than 15 minutes.
            deps.testClock.current = Instant.parse("2026-10-22T06:12:00Z")
            client.upload(f.anya.accessToken, listOf(at("05:54", 1000.0), at("05:55", 1000.0)))
            assertEquals(emptyMap(), deps.notices())
            val presence = deps.db.dbQuery { LocationGeofencing.load(f.anya.memberUuid) }
            assertEquals(false, presence.getValue(java.util.UUID.fromString(f.school)).inside)
        }

    @Test
    fun loc012AdministratorsHearWhenAKidTurnsLocationOffAndBack() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            client.setSharing(f.timur.accessToken)
            client.upload(f.timur.accessToken, listOf(at("05:50", 2000.0)))
            deps.notices()

            client.setSharing(f.timur.accessToken, SharingStatus.OFF_ON_PHONE, reason = OffReason.LOCATION_DISABLED)
            val off = deps.notices()
            assertEquals(setOf("/admin", "/admin2"), off.keys)
            assertNotice(
                off["/admin"],
                NotificationType.LOCATION_SHARING,
                "Геопозиция выключена",
                "Тимур · геопозиция выключена на телефоне",
                f.timur,
            )
            assertNotice(off["/admin2"], NotificationType.LOCATION_SHARING, "Location off", "Тимур · location is off on the phone", f.timur)

            // The same again, then another reason: the reason changes silently.
            client.setSharing(f.timur.accessToken, SharingStatus.OFF_ON_PHONE, reason = OffReason.LOCATION_DISABLED)
            client.setSharing(f.timur.accessToken, SharingStatus.OFF_ON_PHONE, reason = OffReason.PERMISSION_REVOKED)
            assertEquals(emptyMap(), deps.notices())
            assertEquals(OffReason.PERMISSION_REVOKED, client.locationNow(f.admin.accessToken).of(f.timur).offReason)

            deps.testClock.advance(5.minutes)
            client.upload(f.timur.accessToken, listOf(at("05:59", 2000.0)))
            val back = deps.notices()
            assertEquals(setOf("/admin", "/admin2"), back.keys)
            assertNotice(back["/admin"], NotificationType.LOCATION_SHARING, "Геопозиция снова передаётся", "Тимур", f.timur)
            assertNotice(back["/admin2"], NotificationType.LOCATION_SHARING, "Location sharing is back", "Тимур", f.timur)

            // Off with a reason the app gives when sharing is stopped, then back by turning it on.
            client.setSharing(f.timur.accessToken, SharingStatus.OFF_ON_PHONE, reason = OffReason.STOPPED)
            assertEquals("Тимур · передача остановлена", deps.notices()["/admin"]?.body)
            client.setSharing(f.timur.accessToken)
            assertEquals("Тимур", deps.notices()["/admin"]?.body)
        }

    @Test
    fun loc012NothingAboutMembersWhoMayPauseThemselves() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            client.setSharing(f.adult.accessToken)
            client.setSharing(f.admin2.accessToken)
            deps.notices()
            client.setSharing(f.adult.accessToken, SharingStatus.OFF_ON_PHONE, reason = OffReason.LOCATION_DISABLED)
            client.setSharing(f.admin2.accessToken, SharingStatus.OFF_ON_PHONE, reason = OffReason.STOPPED)
            assertEquals(emptyMap(), deps.notices())
        }

    @Test
    fun loc012SigningOutTheSharingPhoneTellsAdministratorsAndRemovalDoesNot() =
        pushApp { deps ->
            val client = jsonClient()
            val f = client.family(deps)
            client.setSharing(f.timur.accessToken)
            client.setSharing(f.anya.accessToken)
            client.upload(f.anya.accessToken, listOf(at("05:50")))
            deps.notices()

            assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Post, "/api/v1/auth/logout", f.timur.accessToken).status)
            val signedOut = deps.notices()
            assertEquals(setOf("/admin", "/admin2"), signedOut.keys)
            assertEquals("Тимур · выход из приложения", signedOut["/admin"]?.body)
            assertEquals("Тимур · signed out of the app", signedOut["/admin2"]?.body)

            val removed = client.send(HttpMethod.Delete, "/api/v1/members/${f.anya.me.memberId}", f.admin.accessToken)
            assertEquals(HttpStatusCode.NoContent, removed.status)
            assertEquals(emptyMap(), deps.notices())
            val rows = deps.db.dbQuery { PlacePresenceTable.selectAll().where { PlacePresenceTable.memberId eq f.anya.memberUuid }.count() }
            assertEquals(0L, rows)
        }
}
