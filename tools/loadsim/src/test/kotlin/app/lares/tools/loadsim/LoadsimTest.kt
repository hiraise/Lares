package app.lares.tools.loadsim

import app.lares.contract.location.Geo
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class LoadsimTest {
    @Test
    fun `parses flags and durations`() {
        val config =
            Config.parse(
                listOf(
                    "--server",
                    "https://localhost/",
                    "--devices",
                    "4",
                    "--duration",
                    "5m",
                    "--warmup",
                    "0",
                    "--burst",
                    "30s",
                    "--insecure",
                ),
            )
        assertEquals("https://localhost", config.server)
        assertEquals(4, config.devices)
        assertEquals(2, config.members)
        assertEquals(5.minutes, config.duration)
        assertEquals(Duration.ZERO, config.warmup)
        assertEquals(30.seconds, config.burst)
        assertEquals(true, config.insecure)
        assertEquals(Duration.parse("1h30m"), Config.duration("--duration", "1h30m"))
    }

    @Test
    fun `rejects bad options`() {
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--devices", "0")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--duration", "soon")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--admin-login", "a")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--whatever")) }
    }

    @Test
    fun `burst moves to the middle when it does not fit`() {
        assertEquals(5.minutes, Config().effectiveBurstAt)
        assertEquals(2.minutes, Config(duration = 5.minutes, burst = 60.seconds).effectiveBurstAt)
        assertNull(Config(burst = Duration.ZERO).effectiveBurstAt)
        assertNull(Config(duration = 30.seconds, burst = 60.seconds).effectiveBurstAt)
    }

    @Test
    fun `nearest-rank percentiles`() {
        val values = (1..100).map { it.toDouble() }
        assertEquals(50.0, EndpointStats.percentile(values, 0.50))
        assertEquals(95.0, EndpointStats.percentile(values, 0.95))
        assertEquals(100.0, EndpointStats.percentile(values, 1.0))
        assertEquals(7.0, EndpointStats.percentile(listOf(7.0), 0.99))
        assertEquals(0.0, EndpointStats.percentile(emptyList(), 0.5))
    }

    @Test
    fun `stats count errors and leave WebSocket opens out of ALL`() {
        val samples =
            listOf(
                Sample(1, Phase.STEADY, "GET /sync/changes", 200, 10.0),
                Sample(2, Phase.STEADY, "GET /sync/changes", 500, 30.0),
                Sample(3, Phase.STEADY, DeviceApi.EVENTS, 101, 900.0),
            )
        val table = EndpointStats.table(samples).associateBy { it.endpoint }
        assertEquals(2, table.getValue("GET /sync/changes").count)
        assertEquals(1, table.getValue("GET /sync/changes").errors)
        assertEquals(2, table.getValue(EndpointStats.ALL).count)
        assertEquals(30.0, table.getValue(EndpointStats.ALL).max)
    }

    @Test
    fun `location flags and defaults`() {
        val defaults = Config.parse(listOf("--devices", "20"))
        assertEquals(10, defaults.sharing)
        assertEquals((0 until 10).map { it * 2 }, defaults.sharingIndexes)
        assertEquals(listOf(1, 3, 5), defaults.viewerIndexes)
        assertEquals(30.seconds, defaults.positionInterval)
        assertEquals(2, defaults.places)
        assertEquals(1, Config.parse(listOf("--devices", "2")).mapViewers)

        val config =
            Config.parse(
                listOf("--devices", "10", "--sharing-devices", "3", "--position-interval", "10s", "--map-viewers", "5", "--places", "0"),
            )
        assertEquals(listOf(0, 2, 4), config.sharingIndexes)
        assertEquals(listOf(1, 3, 5, 7, 9), config.viewerIndexes)
        assertEquals(10.seconds, config.positionInterval)
        assertEquals(0, config.places)

        // One sharing phone per member, viewers only on second devices, positive interval.
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--devices", "10", "--sharing-devices", "6")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--devices", "10", "--map-viewers", "6")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--position-interval", "0")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--places", "-1")) }
        assertFailsWith<IllegalArgumentException> { Config.parse(listOf("--places", "${Config.MAX_PLACES + 1}")) }
    }

    @Test
    fun `route stays within 3 km and passes through the places`() {
        val route = LoopRoute(2)
        val (cLat, cLon) = LoopRoute.CENTER_LAT to LoopRoute.CENTER_LON
        val places = route.placeAngles.map(route::point)
        assertTrue(Geo.distanceM(places[0].first, places[0].second, places[1].first, places[1].second) in 2_990.0..3_010.0)

        var angle = 0.0
        var walked = 0
        var drove = 0
        var nearPlace = 0
        repeat(500) {
            val (lat, lon) = route.point(angle)
            assertTrue(Geo.distanceM(cLat, cLon, lat, lon) in 1_490.0..1_510.0, "point on the circle")
            for ((pLat, pLon) in places) assertTrue(Geo.distanceM(pLat, pLon, lat, lon) <= 3_010.0, "within 3 km of a place")
            if (places.any { (pLat, pLon) -> Geo.distanceM(pLat, pLon, lat, lon) <= LoopRoute.PLACE_RADIUS_M }) nearPlace++
            if (route.speedMps(angle) == LoopRoute.WALK_MPS) walked++ else drove++
            val next = route.advance(angle, 30.seconds)
            val (nLat, nLon) = route.point(next)
            assertTrue(Geo.distanceM(lat, lon, nLat, nLon) <= LoopRoute.DRIVE_MPS * 30 + 1, "30 s step at most driving speed")
            angle = next
        }
        assertTrue(walked > 0 && drove > 0, "walking and driving alternate")
        // Two fixes inside a place are needed for an arrival; a walking pass gives several.
        assertTrue(nearPlace >= 10, "fixes inside the places: $nearPlace")
        assertEquals(90.0, route.bearingDeg(0.0), 1e-9)
        assertEquals(Double.POSITIVE_INFINITY, LoopRoute(0).metresToPlace(PI))
    }

    @Test
    fun `battery drops 1 percent per 10 minutes`() {
        assertEquals(100, SharingPhone.batteryAt(0))
        assertEquals(100, SharingPhone.batteryAt(9))
        assertEquals(97, SharingPhone.batteryAt(30))
        assertEquals(20, SharingPhone.batteryAt(800))
        assertEquals(100, SharingPhone.batteryAt(810))
    }

    @Test
    fun `place alerts go to another sharing member`() {
        val sharing = listOf("m0", "m1", "m2")
        assertEquals("m1", alertTarget(sharing, "m0", 0))
        assertEquals("m0", alertTarget(sharing, "m1", 0))
        assertEquals("m2", alertTarget(sharing, "m1", 1))
        assertEquals("m0", alertTarget(sharing, "m3", 3))
        assertNull(alertTarget(listOf("m0"), "m0", 0))
        assertNull(alertTarget(emptyList(), "m0", 0))
    }
}
