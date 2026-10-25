package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.location.LocationHistory
import app.lares.contract.api.location.PositionFix
import app.lares.contract.location.LocationLimits
import app.lares.server.AppDeps
import app.lares.server.platform.toDb
import app.lares.server.testing.ADMIN_PASSWORD
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
import kotlinx.datetime.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Day histories (LOC-004): household-local days, access by level, accuracy filter, thinning, retention. */
class LocationHistoryTest {
    private suspend fun HttpClient.history(
        token: String,
        member: String,
        date: String,
    ) = send(HttpMethod.Get, "/api/v1/location/history?memberId=$member&date=$date", token)

    private suspend fun HttpClient.historyOk(
        token: String,
        member: AuthResult,
        date: String,
    ): LocationHistory {
        val response = history(token, member.memberId, date)
        assertEquals(HttpStatusCode.OK, response.status, "history")
        return response.body()
    }

    private suspend fun AppDeps.insertDirectly(
        householdId: UUID,
        memberId: UUID,
        fixes: List<PositionFix>,
    ) = db.dbQuery { fixes.forEach { PositionRepository.insert(householdId, memberId, UUID.randomUUID(), it, clock.now().toDb()) } }

    @Test
    fun loc004DayBoundsAreHouseholdLocalAndInaccurateFixesAreLeftOut() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val token = family.adult.accessToken
            client.setSharing(token)
            // Europe/Moscow is UTC+3: 23:30 local on the 21st is 20:30Z, 00:30 local on the 22nd is 21:30Z.
            val lateEvening = fix(LAT, LON, Instant.parse("2026-10-21T20:30:00Z"))
            val afterMidnight = fix(LAT, LON, Instant.parse("2026-10-21T21:30:00Z"))
            val inaccurate = fix(LAT, LON, Instant.parse("2026-10-21T21:40:00Z"), accuracyM = 500f)
            client.upload(token, listOf(lateEvening, afterMidnight, inaccurate))

            val day21 = client.historyOk(family.admin.accessToken, family.adult, "2026-10-21")
            assertEquals(LocalDate(2026, 10, 21), day21.date)
            assertEquals(family.adult.memberId, day21.memberId)
            assertEquals(listOf(lateEvening.recordedAt), day21.points.map { it.recordedAt })
            assertEquals(listOf(afterMidnight.recordedAt), client.historyOk(token, family.adult, "2026-10-22").points.map { it.recordedAt })
        }

    @Test
    fun loc004AccessFollowsTheLevel() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val date = "2026-10-22"
            client.history(family.kid.accessToken, family.adult.memberId, date).assertError(ErrorCode.FORBIDDEN)
            client.history(family.kid.accessToken, family.kid2.memberId, date).assertError(ErrorCode.NOT_FOUND)
            client.history(family.kid.accessToken, "not-a-uuid", date).assertError(ErrorCode.NOT_FOUND)
            client.history(family.kid.accessToken, family.kid.memberId, "22.10.2026").assertError(ErrorCode.VALIDATION_FAILED)
            assertEquals(HttpStatusCode.OK, client.history(family.kid.accessToken, family.kid.memberId, date).status)

            deps.createSecondHousehold()
            val stranger = client.login("admin-b", ADMIN_PASSWORD).body<AuthResult>()
            client.history(stranger.accessToken, family.adult.memberId, date).assertError(ErrorCode.NOT_FOUND)
            client.history(family.admin.accessToken, stranger.memberId, date).assertError(ErrorCode.NOT_FOUND)
        }

    @Test
    fun loc004ThinningKeepsExactlyTheMaximumWithTheLastPoint() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            // From 00:00 local on the 22nd, one point every 5 s.
            val start = Instant.parse("2026-10-21T21:00:00Z")
            val fixes = List(6000) { fix(LAT, LON, start + (it * 5).seconds) }
            deps.insertDirectly(family.householdId, family.adult.memberUuid, fixes)

            val points = client.historyOk(family.admin.accessToken, family.adult, "2026-10-22").points
            assertEquals(LocationLimits.MAX_HISTORY_POINTS, points.size)
            assertEquals(fixes.first().recordedAt, points.first().recordedAt)
            assertEquals(fixes.last().recordedAt, points.last().recordedAt)
            assertTrue(points.zipWithNext().all { (a, b) -> a.recordedAt < b.recordedAt })
        }

    @Test
    fun loc005DaysOutsideTheRetentionAreEmpty() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val old = deps.testClock.now() - 40.days
            deps.insertDirectly(family.householdId, family.adult.memberUuid, listOf(fix(LAT, LON, old)))
            assertTrue(client.historyOk(family.admin.accessToken, family.adult, "2026-09-12").points.isEmpty())
        }
}
