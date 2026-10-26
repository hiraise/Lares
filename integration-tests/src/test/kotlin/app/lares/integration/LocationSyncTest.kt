package app.lares.integration

import app.lares.client.api.ApiException
import app.lares.client.auth.SessionState
import app.lares.client.data.location.Connection
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PlaceData
import app.lares.contract.entities.PlaceIcon
import app.lares.server.testing.MEMBER_PASSWORD
import kotlinx.coroutines.flow.first
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Location on several phones against the real server (plan M4-06, ADR-0024). */
class LocationSyncTest {
    private val home = PlaceData("Home", PlaceIcon.HOME, 55.7963, 37.5378, 120, createdBy = "")

    private val TestClient.memberId: String
        get() = (session.state.value as SessionState.LoggedIn).me.memberId

    private fun fixNow(north: Double = 0.0) =
        PositionFix(Clock.System.now() - 5.seconds, home.latitude + north, home.longitude, 12f, battery = 80, charging = false)

    @Test
    fun `LOC-020 places sync to the whole family, alerts only to their subscriber`() =
        familyTest {
            val id = anna.placesWriter.create(home)
            anna.sync()
            carol.sync()
            assertEquals(
                listOf("Home"),
                carol.places
                    .observePlaces()
                    .first()
                    .map { it.data.name },
            )

            carol.placesWriter.setAlert(borisMemberId, setOf(id))
            carol.sync()
            assertConverged(all)
            assertEquals(1, carol.snapshot().placeAlerts.size)
            assertTrue(anna.snapshot().placeAlerts.isEmpty() && boris.snapshot().placeAlerts.isEmpty())
        }

    @Test
    fun `LOC-003 an upload reaches an open map live`() =
        familyTest {
            anna.placesWriter.create(home)
            anna.sync()
            anna.location.observeNow().await(10.seconds) { it.connection == Connection.LIVE }

            assertTrue(boris.sharing.enable().isSuccess)
            boris.positions.add(fixNow())
            assertTrue(boris.uploader.flush())

            val state =
                anna.location.observeNow().await(
                    10.seconds,
                ) { s -> s.members.any { it.memberId == borisMemberId && it.last != null } }
            assertEquals(Connection.LIVE, state.connection)
            val b = state.members.single { it.memberId == borisMemberId }
            assertEquals("Boris", b.name)
            assertEquals(SharingStatus.SHARING, b.status)
            assertEquals("Home", b.place?.data?.name, "the first reading inside the circle places the member")
            assertEquals(80, b.last?.battery)
        }

    @Test
    fun `LOC-006 a kid sees an adult where now but not the history`() =
        familyTest {
            assertTrue(boris.sharing.enable().isSuccess)
            boris.positions.add(fixNow())
            assertTrue(boris.uploader.flush())

            val state =
                carol.location.observeNow().await(
                    10.seconds,
                ) { s -> s.members.any { it.memberId == borisMemberId && it.last != null } }
            assertEquals(VisibilityLevel.CURRENT, state.members.single { it.memberId == borisMemberId }.level)
            val today =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.of("Europe/Moscow"))
                    .date
            val error = carol.location.history(borisMemberId, today).exceptionOrNull()
            assertEquals(ErrorCode.FORBIDDEN, (error as ApiException).code)
            assertTrue(
                anna.location
                    .history(borisMemberId, today)
                    .getOrThrow()
                    .points
                    .isNotEmpty(),
            )
        }

    @Test
    fun `LOC-019 a second phone of the member takes sharing over`() =
        familyTest {
            TestClient(server.url, "Boris's tablet").use { tablet ->
                tablet.login("boris", MEMBER_PASSWORD)
                assertTrue(boris.sharing.enable().isSuccess)
                assertTrue(tablet.sharing.enable().isSuccess)

                boris.positions.add(fixNow())
                assertTrue(boris.uploader.flush())
                assertFalse(boris.sharing.isEnabled(), "the first phone stops")
                assertEquals(0, boris.positions.size())

                tablet.positions.add(fixNow(north = 0.0001))
                assertTrue(tablet.uploader.flush())
                assertTrue(tablet.sharing.isEnabled())
                assertEquals(tablet.memberId, borisMemberId)
            }
        }
}
