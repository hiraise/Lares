package app.lares.server.location

import app.lares.contract.api.admin.ResetPasswordRequest
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.auth.RefreshRequest
import app.lares.contract.api.location.MemberLocation
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.SharingStatus
import app.lares.server.AppDeps
import app.lares.server.identity.AuthService
import app.lares.server.testing.MEMBER_PASSWORD
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** A phone that signs out stops sharing (LOC-012); member removal deletes the location rows. */
class LocationSessionsTest {
    private suspend fun HttpClient.adultSeenByAdmin(family: LocationFamily): MemberLocation =
        locationNow(family.admin.accessToken).of(family.adult)

    private fun assertSignedOut(seen: MemberLocation) {
        assertEquals(SharingStatus.OFF_ON_PHONE, seen.status)
        assertEquals(OffReason.SIGNED_OUT, seen.offReason)
    }

    private suspend fun HttpClient.sharingFamily(deps: AppDeps): LocationFamily {
        val family = locationFamily(deps)
        setSharing(family.adult.accessToken)
        upload(family.adult.accessToken, listOf(fix(LAT, LON, deps.testClock.now())))
        return family
    }

    @Test
    fun loc012LogoutOfTheSharingSessionStopsSharingAndOfAnotherDoesNot() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.sharingFamily(deps)
            val other = client.login("dima", MEMBER_PASSWORD).body<AuthResult>()
            assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Post, "/api/v1/auth/logout", other.accessToken).status)
            assertEquals(SharingStatus.SHARING, client.adultSeenByAdmin(family).status)

            deps.testClock.advance(1.minutes)
            assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Post, "/api/v1/auth/logout", family.adult.accessToken).status)
            val seen = client.adultSeenByAdmin(family)
            assertSignedOut(seen)
            assertEquals(deps.testClock.now(), seen.statusSince)
            // The last position stays visible.
            assertEquals(LAT, seen.last?.latitude)
        }

    @Test
    fun loc012RevokeSessionStopsSharing() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.sharingFamily(deps)
            val other = client.login("dima", MEMBER_PASSWORD).body<AuthResult>()
            val sharingSessionId = family.adult.sessionId
            val response = client.send(HttpMethod.Delete, "/api/v1/me/sessions/$sharingSessionId", other.accessToken)
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertSignedOut(client.adultSeenByAdmin(family))
        }

    @Test
    fun loc012ResetPasswordStopsSharing() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.sharingFamily(deps)
            val response =
                client.send(
                    HttpMethod.Post,
                    "/api/v1/members/${family.adult.memberId}/password",
                    family.admin.accessToken,
                    ResetPasswordRequest("a brand new password"),
                )
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertSignedOut(client.adultSeenByAdmin(family))
        }

    @Test
    fun loc012ExpiredRefreshTokenStopsSharing() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.sharingFamily(deps)
            deps.testClock.advance(AuthService.REFRESH_TTL + 1.days)
            val response = client.send(HttpMethod.Post, "/api/v1/auth/refresh", null, RefreshRequest(family.adult.refreshToken))
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val state = deps.db.dbQuery { LocationStateRepository.load(family.householdId, listOf(family.adult.memberUuid)) }
            assertEquals(SharingStatus.OFF_ON_PHONE, state.getValue(family.adult.memberUuid).status)
            assertEquals(OffReason.SIGNED_OUT, state.getValue(family.adult.memberUuid).offReason)
        }

    @Test
    fun memberRemovalDeletesPositionsAndStateWithoutAStateChange() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.sharingFamily(deps)
            val response = client.send(HttpMethod.Delete, "/api/v1/members/${family.adult.memberId}", family.admin.accessToken)
            assertEquals(HttpStatusCode.NoContent, response.status)
            val (positions, states) =
                deps.db.dbQuery {
                    PositionTable.selectAll().where { PositionTable.memberId eq family.adult.memberUuid }.count() to
                        LocationStateTable.selectAll().where { LocationStateTable.memberId eq family.adult.memberUuid }.count()
                }
            assertEquals(0L to 0L, positions to states)
            assertNull(client.locationNow(family.admin.accessToken).members.firstOrNull { it.memberId == family.adult.memberId })
        }
}
