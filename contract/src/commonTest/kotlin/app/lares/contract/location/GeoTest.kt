package app.lares.contract.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun `LOC-022 Moscow to St Petersburg is about 634 km`() {
        val d = Geo.distanceM(55.7558, 37.6173, 59.9343, 30.3351)
        assertTrue(abs(d - 634_000) < 1_000, "distance $d")
        assertEquals(d, Geo.distanceM(59.9343, 30.3351, 55.7558, 37.6173), 1e-6)
        assertEquals(0.0, Geo.distanceM(55.0, 37.0, 55.0, 37.0))
    }

    @Test
    fun `LOC-022 100 m north is 100 m`() {
        val north = 55.0 + 100.0 / Geo.EARTH_RADIUS_M * 180.0 / PI
        assertEquals(100.0, Geo.distanceM(55.0, 37.0, north, 37.0), 0.01)
    }

    @Test
    fun `LOC-020 circle ring is closed and every vertex lies on the radius`() {
        for ((lat, lon) in listOf(55.75 to 37.61, -33.9 to 151.2, 10.0 to 179.999)) {
            val ring = Geo.circle(lat, lon, 150.0)
            assertEquals(49, ring.size)
            assertEquals(ring.first(), ring.last())
            for ((vLat, vLon) in ring) assertEquals(150.0, Geo.distanceM(lat, lon, vLat, vLon), 0.5, "vertex $vLat,$vLon")
            assertTrue(ring.all { it.second in -180.0..180.0 })
        }
        assertEquals(9, Geo.circle(0.0, 0.0, 1000.0, points = 8).size)
    }
}
