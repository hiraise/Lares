package app.lares.contract.location

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Distances and circles on the sphere (architecture/location.md § Pure logic). Angles in degrees, lengths in metres. */
object Geo {
    /** Mean Earth radius (IUGG). */
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance (haversine). */
    fun distanceM(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val p1 = lat1.rad()
        val p2 = lat2.rad()
        val dp = (lat2 - lat1).rad()
        val dl = (lon2 - lon1).rad()
        val h = sin(dp / 2).let { it * it } + cos(p1) * cos(p2) * sin(dl / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /**
     * A closed polygon ring approximating the circle: [points] vertices `(lat, lon)` clockwise from north, then the first
     * one again.
     */
    fun circle(
        lat: Double,
        lon: Double,
        radiusM: Double,
        points: Int = DEFAULT_CIRCLE_POINTS,
    ): List<Pair<Double, Double>> {
        require(points >= MIN_CIRCLE_POINTS) { "points must be at least $MIN_CIRCLE_POINTS" }
        val ring = (0 until points).map { i -> destination(lat, lon, 2 * PI * i / points, radiusM) }
        return ring + ring.first()
    }

    /** The point [distanceM] away from `(lat, lon)` on the initial [bearingRad]. */
    private fun destination(
        lat: Double,
        lon: Double,
        bearingRad: Double,
        distanceM: Double,
    ): Pair<Double, Double> {
        val d = distanceM / EARTH_RADIUS_M
        val p1 = lat.rad()
        val l1 = lon.rad()
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(bearingRad))
        val l2 = l1 + atan2(sin(bearingRad) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        val lonDeg = (l2.deg() + 540.0) % 360.0 - 180.0
        return p2.deg() to lonDeg
    }

    private fun Double.rad(): Double = this * PI / 180.0

    private fun Double.deg(): Double = this * 180.0 / PI

    private const val DEFAULT_CIRCLE_POINTS = 48
    private const val MIN_CIRCLE_POINTS = 3
}
