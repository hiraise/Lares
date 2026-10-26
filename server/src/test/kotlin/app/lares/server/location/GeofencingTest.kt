package app.lares.server.location

import app.lares.contract.api.location.PositionFix
import app.lares.contract.location.Transition
import app.lares.contract.sync.EntityTypes
import app.lares.server.AppDeps
import app.lares.server.testing.assertApplied
import app.lares.server.testing.delete
import app.lares.server.testing.fix
import app.lares.server.testing.jsonClient
import app.lares.server.testing.newPlace
import app.lares.server.testing.pushOne
import app.lares.server.testing.setSharing
import app.lares.server.testing.testApp
import app.lares.server.testing.testClock
import app.lares.server.testing.upload
import io.ktor.client.HttpClient
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Arrivals and departures on the server (LOC-022, plan M4-04): the hysteresis of `Geofence` applied per member and
 * place through `LocationGeofencing`, the presence reset and delete with the place, and «где сейчас» in `GET /now`.
 * The place is a 150 m circle around [LAT], [LON]; the kid's positions are fed directly or uploaded.
 */
class GeofencingTest {
    /** [metres] north of the centre. */
    private fun north(
        metres: Double,
        at: Instant,
        accuracyM: Float = 10f,
    ): PositionFix = fix(LAT + metres / M_PER_DEG, LON, at, accuracyM)

    private suspend fun HttpClient.place(
        deps: AppDeps,
        family: LocationFamily,
        name: String = "School",
        radiusM: Int = 150,
        latitude: Double = LAT,
    ): UUID {
        val mutation = deps.newPlace(family.admin.memberId, name = name, radiusM = radiusM, latitude = latitude)
        pushOne(family.admin.accessToken, mutation).assertApplied()
        return UUID.fromString(mutation.id)
    }

    private suspend fun AppDeps.feed(
        family: LocationFamily,
        vararg fixes: PositionFix,
    ): List<PlaceEvent> = db.dbQuery { LocationGeofencing.apply(this, family.householdId, family.kid.memberUuid, fixes.toList()) }

    private suspend fun AppDeps.presence(family: LocationFamily) = db.dbQuery { LocationGeofencing.load(family.kid.memberUuid) }

    private fun at(minutes: Int): Instant = START + minutes.minutes

    @Test
    fun loc022ArrivalNeedsTwoConsecutiveInsideReadingsAndDatesBackToTheFirst() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val school = client.place(deps, family)

            assertEquals(emptyList(), deps.feed(family, north(1000.0, at(0))), "first knowledge: outside")
            assertEquals(emptyList(), deps.feed(family, north(0.0, at(1))), "one inside reading is a candidate")
            assertEquals(false, deps.presence(family).getValue(school).inside)
            assertEquals(true, deps.presence(family).getValue(school).candidate)

            assertEquals(listOf(PlaceEvent(school, Transition.ARRIVED, at(1))), deps.feed(family, north(20.0, at(2))))
            val arrived = deps.presence(family).getValue(school)
            assertEquals(true, arrived.inside)
            assertEquals(at(1), arrived.since)
            assertNull(arrived.candidate)

            // An outside reading followed by an inside one cancels the candidate: no departure.
            assertEquals(emptyList(), deps.feed(family, north(1000.0, at(3)), north(0.0, at(4)), north(1000.0, at(5))))
            assertEquals(listOf(PlaceEvent(school, Transition.LEFT, at(5))), deps.feed(family, north(1200.0, at(6))))
        }

    @Test
    fun loc022TheFirstReadingAfterCreatingAPlaceNeverNotifies() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            assertEquals(emptyList(), deps.feed(family, north(0.0, at(0))), "no places, nothing stored")
            val school = client.place(deps, family)
            assertEquals(emptyList(), deps.feed(family, north(0.0, at(1)), north(0.0, at(2))))
            val presence = deps.presence(family).getValue(school)
            assertEquals(true, presence.inside)
            assertEquals(at(1), presence.since)
        }

    @Test
    fun loc022EdgeZoneReadingsKeepTheState() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val school = client.place(deps, family)
            deps.feed(family, north(0.0, at(0)))
            // 170 m from the centre with 10 m accuracy: beyond 150 m but within the 30 m margin.
            assertEquals(emptyList(), deps.feed(family, north(170.0, at(1)), north(175.0, at(2)), north(170.0, at(3))))
            val presence = deps.presence(family).getValue(school)
            assertEquals(true, presence.inside)
            assertEquals(at(0), presence.since)
            assertNull(presence.candidate)
        }

    @Test
    fun loc022InaccurateFixesAreSkipped() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val school = client.place(deps, family)
            deps.feed(family, north(0.0, at(0)))
            val before = deps.presence(family)
            // Worse than max(radius, 100 m) = 150 m: no information, even far away.
            assertEquals(emptyList(), deps.feed(family, north(3000.0, at(1), 500f), north(3000.0, at(2), 151f)))
            assertEquals(before, deps.presence(family))
            assertEquals(true, deps.presence(family).getValue(school).inside)
        }

    @Test
    fun loc022MovingThePlaceResetsPresenceAndDeletingItDeletesTheRows() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            val mutation = deps.newPlace(family.admin.memberId)
            client.pushOne(family.admin.accessToken, mutation).assertApplied()
            val school = UUID.fromString(mutation.id)
            deps.feed(family, north(0.0, at(0)))

            // A renamed place keeps presence; a moved one forgets it.
            client
                .pushOne(
                    family.admin.accessToken,
                    deps.newPlace(family.admin.memberId, name = "School 12", id = mutation.id),
                ).assertApplied()
            assertEquals(true, deps.presence(family).getValue(school).inside)
            val moved = deps.newPlace(family.admin.memberId, latitude = LAT + 2000 / M_PER_DEG, id = mutation.id)
            client.pushOne(family.admin.accessToken, moved).assertApplied()
            assertNull(deps.presence(family).getValue(school).inside)
            // After the reset the first reading is knowledge again, not a departure.
            assertEquals(emptyList(), deps.feed(family, north(0.0, at(1)), north(0.0, at(2))))
            assertEquals(false, deps.presence(family).getValue(school).inside)

            client.pushOne(family.admin.accessToken, deps.delete(EntityTypes.PLACE, mutation.id)).assertApplied()
            val rows = deps.db.dbQuery { PlacePresenceTable.selectAll().where { PlacePresenceTable.placeId eq school }.count() }
            assertEquals(0L, rows)
            assertEquals(emptyList(), deps.feed(family, north(2000.0, at(3)), north(2000.0, at(4))), "deleted places are ignored")
        }

    @Test
    fun loc003NowShowsThePlaceWithTheSmallestRadius() =
        testApp { deps ->
            val client = jsonClient()
            val family = client.locationFamily(deps)
            deps.testClock.current = at(10)
            val district = client.place(deps, family, name = "District", radiusM = 1000)
            val school = client.place(deps, family, name = "School", radiusM = 100)
            client.place(deps, family, name = "Home", radiusM = 100, latitude = LAT + 5000 / M_PER_DEG)

            client.setSharing(family.kid.accessToken)
            client.upload(family.kid.accessToken, listOf(north(0.0, at(5)), north(10.0, at(6))))
            val seen = client.locationNow(family.admin.accessToken).of(family.kid)
            assertEquals(school.toString(), seen.placeId)
            assertEquals(at(5), seen.placeSince)
            assertNull(client.locationNow(family.admin.accessToken).of(family.adult).placeId)

            // Left the school (two clear readings), still in the district.
            client.upload(family.kid.accessToken, listOf(north(600.0, at(7)), north(600.0, at(8))))
            val later = client.locationNow(family.admin.accessToken).of(family.kid)
            assertEquals(district.toString(), later.placeId)
            assertEquals(at(5), later.placeSince)
        }

    companion object {
        private const val M_PER_DEG = 111_195.0
        private val START = Instant.parse("2026-10-22T09:00:00Z")
    }
}
