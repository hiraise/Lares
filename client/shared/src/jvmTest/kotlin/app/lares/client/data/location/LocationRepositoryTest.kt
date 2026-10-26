package app.lares.client.data.location

import app.lares.client.api.ApiException
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.LiveMessage
import app.lares.contract.api.location.LocationNow
import app.lares.contract.api.location.MemberLocation
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.entities.PaletteColor
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LocationRepositoryTest {
    private val f = LocationFixture()
    private val now = f.clock.now()

    @AfterTest
    fun close() = f.close()

    private fun member(
        id: String,
        minutesAgo: Int = 1,
        status: SharingStatus = SharingStatus.SHARING,
        placeId: String? = null,
        speed: Float? = null,
    ) = MemberLocation(
        memberId = id,
        level = VisibilityLevel.HISTORY,
        status = status,
        last = fixAt(now - minutesAgo.minutes, speed = speed),
        placeId = placeId,
        placeSince = placeId?.let { now - 40.minutes },
    )

    private fun snapshot(vararg members: MemberLocation) = LiveMessage(LiveMessage.SNAPSHOT, now = LocationNow(members.toList(), now))

    private suspend fun setUp() {
        f.family()
        f.putPlaces()
        f.loginAs(OLGA, *LOC_ADULT)
    }

    private suspend fun cached(): LocationNow? =
        f.localDb
            .read { location_snapshotQueries.get("now").executeAsOneOrNull() }
            ?.let { LaresJson.decodeFromString(LocationNow.serializer(), it.json) }

    private suspend fun putCache(vararg members: MemberLocation) =
        f.localDb.transaction {
            location_snapshotQueries.put(
                "now",
                LaresJson.encodeToString(LocationNow.serializer(), LocationNow(members.toList(), now)),
                now.toString(),
            )
        }

    private fun <T> eventually(block: suspend () -> T): T = runBlocking { withTimeout(10.seconds) { block() } }

    private fun Flow<MapState>.until(predicate: (MapState) -> Boolean): MapState = eventually { first(predicate) }

    @Test
    fun `LOC-003 snapshot, member updates and gone come live, joined with names, colors and places`() {
        eventually { setUp() }
        val socket = Channel<LiveMessage>(Channel.UNLIMITED)
        f.liveFlow = { socket.receiveAsFlow() }
        val repository = f.repository()
        socket.trySend(snapshot(member(DIMA, placeId = HOME_ID), member(ANYA)))
        val first = repository.observeNow().until { it.connection == Connection.LIVE && it.members.size == 2 }
        assertEquals(listOf("Аня", "Дмитрий"), first.members.map { it.name })
        val dima = first.members.last()
        assertEquals(PaletteColor.BLUE, dima.color)
        assertEquals(P_DIMA, dima.personId)
        assertEquals("Дом", dima.place?.data?.name)
        assertEquals(2, first.places.size)
        assertEquals(MySharing.Off, first.me)

        socket.trySend(LiveMessage(LiveMessage.MEMBER, member = member(ANYA, minutesAgo = 0, speed = 3f)))
        assertTrue(
            repository
                .observeNow()
                .until { s -> s.members.any { it.memberId == ANYA && it.moving } }
                .members.size == 2,
        )

        socket.trySend(LiveMessage(LiveMessage.GONE, memberId = ANYA))
        assertEquals(
            listOf(DIMA),
            repository
                .observeNow()
                .until { it.members.size == 1 }
                .members
                .map { it.memberId },
        )
        assertEquals(listOf(DIMA), eventually { cached() }!!.members.map { it.memberId }, "gone leaves the cache at once")
    }

    @Test
    fun `LOC-003 a socket failure switches to polling`() {
        eventually { setUp() }
        f.liveFlow = { flow { throw ApiException(ErrorCode.INTERNAL, "boom") } }
        f.server = { json(LocationNow.serializer(), LocationNow(listOf(member(DIMA)), now)) }
        val state = f.repository().observeNow().until { it.connection == Connection.POLLING && it.members.isNotEmpty() }
        assertEquals(listOf(DIMA), state.members.map { it.memberId })
        assertTrue(f.liveConnects >= 1)
        assertTrue(f.requests.any { it.url.encodedPath == "/api/v1/location/now" })
    }

    @Test
    fun `LOC-009 offline shows the cached snapshot`() {
        eventually {
            setUp()
            putCache(member(DIMA, minutesAgo = 5))
        }
        f.liveFlow = { flow { throw IOException("no network") } }
        f.server = { throw IOException("no network") }
        val state = f.repository().observeNow().until { it.connection == Connection.OFFLINE }
        assertEquals(listOf(DIMA), state.members.map { it.memberId })
        assertEquals(now, state.updatedAt)
    }

    @Test
    fun `LOC-013 a sharing member becomes stale after 30 minutes by the minute ticks`() {
        eventually { setUp() }
        val socket = Channel<LiveMessage>(Channel.UNLIMITED)
        f.liveFlow = { socket.receiveAsFlow() }
        val repository = f.repository()
        socket.trySend(snapshot(member(DIMA, minutesAgo = 25), member(ANYA, minutesAgo = 25, status = SharingStatus.PAUSED)))
        val fresh = repository.observeNow().until { it.members.size == 2 }
        assertFalse(fresh.members.any { it.stale })
        f.setNow(now + 6.minutes)
        val later = repository.observeNow().until { s -> s.members.any { it.stale } }
        assertEquals(listOf(DIMA), later.members.filter { it.stale }.map { it.memberId }, "only a sharing member is stale")
    }

    @Test
    fun `LOC-013 snapshots are cached for the next offline start`() {
        eventually { setUp() }
        val socket = Channel<LiveMessage>(Channel.UNLIMITED)
        f.liveFlow = { socket.receiveAsFlow() }
        socket.trySend(snapshot(member(DIMA), member(ANYA)))
        f.repository().observeNow().until { it.members.size == 2 }
        assertEquals(setOf(DIMA, ANYA), eventually { cached() }!!.members.map { it.memberId }.toSet())
    }

    @Test
    fun `LOC-003 FORBIDDEN clears the members and the cache`() {
        eventually {
            setUp()
            putCache(member(DIMA))
        }
        f.liveFlow = { flow { throw ApiException(ErrorCode.FORBIDDEN, "no", httpStatus = 403) } }
        f.server = { apiError(HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN) }
        val state = f.repository().observeNow().until { it.members.isEmpty() && it.connection == Connection.POLLING }
        assertEquals(emptyList(), state.members)
        assertNull(eventually { cached() })
    }

    @Test
    fun `LOC-006 losing location view deletes the cache`() {
        eventually {
            f.family()
            f.loginAs(OLGA)
            putCache(member(DIMA))
        }
        f.liveFlow = { flow { throw ApiException(ErrorCode.FORBIDDEN, "no", httpStatus = 403) } }
        f.repository().observeNow().until { it.members.isEmpty() }
        assertNull(eventually { cached() })
    }

    @Test
    fun `LOC-004 history detects the stops of the day`() {
        eventually { setUp() }
        val points =
            (0..30).map { i ->
                app.lares.contract.api.location
                    .TrackPoint(now - 3.minutes * (30 - i), SCHOOL.latitude, SCHOOL.longitude, 10f)
            }
        f.server = {
            json(
                app.lares.contract.api.location.LocationHistory
                    .serializer(),
                app.lares.contract.api.location
                    .LocationHistory(ANYA, kotlinx.datetime.LocalDate(2026, 10, 21), points),
            )
        }
        val day = eventually { f.repository().history(ANYA, kotlinx.datetime.LocalDate(2026, 10, 21)).getOrThrow() }
        assertEquals(31, day.points.size)
        assertEquals(listOf(SCHOOL_ID), day.stops.map { it.placeId })
        assertTrue(day.stops.single().ongoing)
        assertTrue(
            f.requests
                .single()
                .url.parameters["memberId"] == ANYA,
        )
    }

    @Test
    fun `LOC-014 addresses are cached by rounded coordinates and off when the server has no geocoder`() {
        eventually { setUp() }
        var addressCalls = 0
        f.server = { request ->
            when (request.url.encodedPath) {
                "/api/v1/location/config" -> {
                    json(
                        app.lares.contract.api.location.LocationConfig
                            .serializer(),
                        app.lares.contract.api.location
                            .LocationConfig("s", "d", addresses = true, historyDays = 30),
                    )
                }

                else -> {
                    addressCalls++
                    json(
                        app.lares.contract.api.location.AddressLabel
                            .serializer(),
                        app.lares.contract.api.location
                            .AddressLabel("ул. Ленина, 12"),
                    )
                }
            }
        }
        val repository = f.repository()
        assertEquals("ул. Ленина, 12", eventually { repository.address(55.796301, 37.537801) })
        assertEquals("ул. Ленина, 12", eventually { repository.address(55.796304, 37.537799) })
        assertEquals(1, addressCalls)
        assertEquals(
            "55.7963",
            f.requests
                .last()
                .url.parameters["lat"],
        )

        val off = LocationFixture()
        eventually { off.family() }
        off.server = {
            json(
                app.lares.contract.api.location.LocationConfig
                    .serializer(),
                app.lares.contract.api.location
                    .LocationConfig("s", "d", addresses = false, historyDays = 30),
            )
        }
        assertNull(eventually { off.repository().address(55.7963, 37.5378) })
        assertEquals(listOf("/api/v1/location/config"), off.requests.map { it.url.encodedPath })
        off.close()
    }
}
