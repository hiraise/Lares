package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.UploadPositionsRequest
import app.lares.server.AppDeps
import app.lares.server.testing.MEMBER_PASSWORD
import app.lares.server.testing.assertError
import app.lares.server.testing.fix
import app.lares.server.testing.jsonClient
import app.lares.server.testing.login
import app.lares.server.testing.send
import app.lares.server.testing.setSharing
import app.lares.server.testing.setSharingRaw
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upload
import io.ktor.client.call.body
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Positions upload and sharing state (LOC-001, LOC-010, LOC-019, LOC-027; architecture/location.md § Upload). */
class LocationUploadTest {
    private suspend fun AppDeps.positions(member: AuthResult): Long =
        db.dbQuery { PositionTable.selectAll().where { PositionTable.memberId eq member.memberUuid }.count() }

    @Test
    fun loc001UploadAfterSharingStoresFixesAndUpdatesLast() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val now = deps.testClock.now()
            client.setSharing(family.adult.accessToken)

            val newest = fix(LAT, LON, now - 1.minutes)
            val response = client.upload(family.adult.accessToken, listOf(newest, fix(LAT + 0.01, LON, now - 2.minutes)))
            assertEquals(2, response.accepted)
            assertTrue(response.sharingHere)
            assertFalse(response.paused)
            assertFalse(response.refreshRequested)
            assertEquals(2, deps.positions(family.adult))

            val seen = client.locationNow(family.admin.accessToken).of(family.adult)
            assertEquals(SharingStatus.SHARING, seen.status)
            assertEquals(newest, seen.last)

            // An older fix arriving later does not replace the newest one.
            client.upload(family.adult.accessToken, listOf(fix(LAT + 0.02, LON, now - 3.minutes)))
            assertEquals(newest, client.locationNow(family.admin.accessToken).of(family.adult).last)
        }

    @Test
    fun loc001UploadBeforeAnySharingStoresNothing() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val response = client.upload(family.adult.accessToken, listOf(fix(LAT, LON, deps.testClock.now())))
            assertEquals(0, response.accepted)
            assertFalse(response.sharingHere)
            assertEquals(0, deps.positions(family.adult))
        }

    @Test
    fun loc019SecondSessionTakesOverAndTheFirstIsToldToStop() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val now = deps.testClock.now()
            val phoneA = family.adult.accessToken
            val phoneB = client.login("dima", MEMBER_PASSWORD).body<AuthResult>().accessToken
            client.setSharing(phoneA)
            client.setSharing(phoneB)

            val fromA = client.upload(phoneA, listOf(fix(LAT, LON, now)))
            assertFalse(fromA.sharingHere)
            assertEquals(0, fromA.accepted)
            assertTrue(client.upload(phoneB, listOf(fix(LAT, LON, now))).sharingHere)
            assertEquals(1, deps.positions(family.adult))

            // "Off" from the old phone changes nothing.
            client.setSharing(phoneA, SharingStatus.OFF_ON_PHONE, reason = OffReason.STOPPED)
            assertEquals(SharingStatus.SHARING, client.locationNow(family.admin.accessToken).of(family.adult).status)
        }

    @Test
    fun loc010PausedDropsFixesAndAnExpiredPauseCountsAsSharing() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val token = family.adult.accessToken
            val start = deps.testClock.now()
            client.setSharing(token)
            client.upload(token, listOf(fix(LAT, LON, start)))
            val until = start + 10.minutes
            client.setSharing(token, SharingStatus.PAUSED, pausedUntil = until)

            val paused = client.upload(token, listOf(fix(LAT + 0.01, LON, start + 1.minutes)))
            assertEquals(0, paused.accepted)
            assertTrue(paused.paused)
            assertEquals(until, paused.pausedUntil)
            assertEquals(1, deps.positions(family.adult))

            deps.testClock.advance(11.minutes)
            val after = client.upload(token, listOf(fix(LAT + 0.02, LON, start + 11.minutes)))
            assertEquals(1, after.accepted)
            assertFalse(after.paused)
            val seen = client.locationNow(family.admin.accessToken).of(family.adult)
            assertEquals(SharingStatus.SHARING, seen.status)
            assertEquals(until, seen.statusSince)
            assertNull(seen.pausedUntil)
        }

    @Test
    fun loc010KidCannotPauseAndPauseIsValidated() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val now = deps.testClock.now()
            client.setSharing(family.kid.accessToken)
            client.setSharingRaw(family.kid.accessToken, SharingStatus.PAUSED, now + 1.hours).assertError(ErrorCode.FORBIDDEN)
            client.setSharingRaw(family.adult.accessToken, SharingStatus.PAUSED, now - 1.minutes).assertError(ErrorCode.VALIDATION_FAILED)
            client.setSharingRaw(family.adult.accessToken, SharingStatus.OFF_ON_PHONE).assertError(ErrorCode.VALIDATION_FAILED)
            client.setSharingRaw(family.adult.accessToken, SharingStatus.NEVER).assertError(ErrorCode.VALIDATION_FAILED)
        }

    @Test
    fun loc027BatchOfOldFixesIsStoredOnceAndOutOfRangeFixesAreDropped() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val token = family.adult.accessToken
            val now = deps.testClock.now()
            client.setSharing(token)

            val batch = List(120) { fix(LAT + it * 0.0001, LON, now - 23.hours + it.minutes) }
            assertEquals(120, client.upload(token, batch).accepted)
            val again = client.upload(token, batch)
            assertEquals(0, again.accepted)
            assertTrue(again.sharingHere)
            assertEquals(120, deps.positions(family.adult))

            val mixed =
                listOf(
                    fix(LAT, LON, now - 25.hours),
                    fix(LAT, LON, now + 10.minutes),
                    fix(LAT, 200.0, now - 30.seconds),
                    fix(LAT, LON, now),
                )
            assertEquals(1, client.upload(token, mixed).accepted)
            assertEquals(121, deps.positions(family.adult))

            client
                .send(HttpMethod.Post, "/api/v1/location/positions", token, UploadPositionsRequest(emptyList()))
                .assertError(ErrorCode.VALIDATION_FAILED)
            val tooMany = List(201) { fix(LAT, LON, now - it.seconds) }
            val response = client.send(HttpMethod.Post, "/api/v1/location/positions", token, UploadPositionsRequest(tooMany))
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(
                "OUT_OF_RANGE",
                response
                    .assertError(ErrorCode.VALIDATION_FAILED)
                    .error.fields["positions"]
                    ?.name,
            )
        }

    @Test
    fun loc012OffOnPhoneThenAFixMeansSharingAgain() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val token = family.adult.accessToken
            client.setSharing(token)
            client.setSharing(token, SharingStatus.OFF_ON_PHONE, reason = OffReason.LOCATION_DISABLED)
            val off = client.locationNow(family.admin.accessToken).of(family.adult)
            assertEquals(SharingStatus.OFF_ON_PHONE, off.status)
            assertEquals(OffReason.LOCATION_DISABLED, off.offReason)

            // Another reason while off: the reason changes, the time stays.
            deps.testClock.advance(1.minutes)
            client.setSharing(token, SharingStatus.OFF_ON_PHONE, reason = OffReason.PERMISSION_REVOKED)
            val stillOff = client.locationNow(family.admin.accessToken).of(family.adult)
            assertEquals(OffReason.PERMISSION_REVOKED, stillOff.offReason)
            assertEquals(off.statusSince, stillOff.statusSince)

            val response = client.upload(token, listOf(fix(LAT, LON, deps.testClock.now())))
            assertEquals(1, response.accepted)
            val back = client.locationNow(family.admin.accessToken).of(family.adult)
            assertEquals(SharingStatus.SHARING, back.status)
            assertNull(back.offReason)
        }

    @Test
    fun concurrentUploadsAndFirstSharingOfOneMemberSucceed() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val token = family.adult.accessToken
            val now = deps.testClock.now()
            coroutineScope {
                List(4) { async { client.setSharingRaw(token, SharingStatus.SHARING).status } }
                    .awaitAll()
                    .forEach { assertEquals(HttpStatusCode.NoContent, it) }
            }
            val accepted =
                coroutineScope {
                    List(4) { i -> async { client.upload(token, List(5) { fix(LAT, LON, now - (i + it).seconds) }).accepted } }.awaitAll()
                }
            // Overlapping fixes are stored once.
            assertEquals(8, accepted.sum())
            assertEquals(8, deps.positions(family.adult))
        }
}
