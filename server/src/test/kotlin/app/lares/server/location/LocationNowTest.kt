package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.admin.CreateMemberRequest
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.server.testing.MEMBER_PASSWORD
import app.lares.server.testing.assertError
import app.lares.server.testing.createMember
import app.lares.server.testing.fix
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.send
import app.lares.server.testing.setSharing
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upload
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** "Now" for viewers (LOC-003, LOC-006): who appears with which level and state. */
class LocationNowTest {
    @Test
    fun loc006DefaultFamilyLevelsAndNeverMembers() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)

            val byAdult = client.locationNow(family.adult.accessToken)
            assertEquals(
                setOf(family.admin, family.adult, family.kid, family.kid2).map { it.memberId }.toSet(),
                byAdult.members.map { it.memberId }.toSet(),
            )
            assertEquals(setOf(VisibilityLevel.HISTORY), byAdult.members.map { it.level }.toSet())
            assertEquals(setOf(SharingStatus.NEVER), byAdult.members.map { it.status }.toSet())
            assertEquals(deps.testClock.now(), byAdult.serverTime)

            // A kid sees the adult now (not the administrator, who is not in Adults), themselves with history, not the other kid.
            val byKid = client.locationNow(family.kid.accessToken)
            assertEquals(
                mapOf(family.adult.memberId to VisibilityLevel.CURRENT, family.kid.memberId to VisibilityLevel.HISTORY),
                byKid.members.associate { it.memberId to it.level },
            )
        }

    @Test
    fun loc003MemberWithoutViewIsForbidden() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            client.createMember(family.admin.accessToken, CreateMemberRequest("guest", "guest", MEMBER_PASSWORD, emptySet()))
            val guest = client.login("guest", MEMBER_PASSWORD).body<AuthResult>()
            client.send(HttpMethod.Get, "/api/v1/location/now", guest.accessToken).assertError(ErrorCode.FORBIDDEN)
            client
                .send(HttpMethod.Get, "/api/v1/location/history?memberId=${guest.memberId}&date=2026-10-22", guest.accessToken)
                .assertError(ErrorCode.FORBIDDEN)
        }

    @Test
    fun loc003PausedShowsTheLastFixBeforeThePauseAndAnExpiredPauseShowsSharing() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val token = family.adult.accessToken
            val start = deps.testClock.now()
            client.setSharing(token)
            val before = fix(LAT, LON, start)
            client.upload(token, listOf(before))
            val until = start + 30.minutes
            client.setSharing(token, SharingStatus.PAUSED, pausedUntil = until)
            client.upload(token, listOf(fix(LAT + 0.1, LON, start + 1.minutes)))

            val paused = client.locationNow(family.kid.accessToken).of(family.adult)
            assertEquals(SharingStatus.PAUSED, paused.status)
            assertEquals(until, paused.pausedUntil)
            assertEquals(before, paused.last)

            deps.testClock.advance(31.minutes)
            val resumed = client.locationNow(family.kid.accessToken).of(family.adult)
            assertEquals(SharingStatus.SHARING, resumed.status)
            assertEquals(until, resumed.statusSince)
            assertEquals(before, resumed.last)
        }
}
