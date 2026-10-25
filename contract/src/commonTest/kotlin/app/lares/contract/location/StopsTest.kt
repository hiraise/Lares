package app.lares.contract.location

import app.lares.contract.api.location.TrackPoint
import app.lares.contract.entities.PlaceData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StopsTest {
    private val day = Instant.parse("2026-10-25T00:00:00Z")

    private fun at(
        hour: Int,
        minute: Int,
    ) = day + (hour * 60 + minute).minutes

    private val home = PlaceData("Дом", latitude = 55.7000, longitude = 37.6000, radiusM = 100, createdBy = "m1")
    private val school = PlaceData("Школа", latitude = 55.7100, longitude = 37.6200, radiusM = 150, createdBy = "m1")
    private val schoolYard = PlaceData("Двор школы", latitude = 55.7100, longitude = 37.6200, radiusM = 300, createdBy = "m1")
    private val places = listOf("home" to home, "yard" to schoolYard, "school" to school)

    /** A point every 5 minutes from [from] to [to] (inclusive) jittering a few metres around the place. */
    private fun stay(
        place: PlaceData,
        from: Instant,
        to: Instant,
        accuracy: Float = 15f,
    ): List<TrackPoint> =
        generateSequence(from) { it + 5.minutes }
            .takeWhile { it <= to }
            .mapIndexed { i, t -> TrackPoint(t, place.latitude + (i % 3) * 0.00005, place.longitude, accuracy) }
            .toList()

    /** Points along the way from home to school, 5 minutes apart. */
    private fun walk(
        from: Instant,
        steps: Int,
    ): List<TrackPoint> =
        (1..steps).map { i ->
            val f = i.toDouble() / (steps + 1)
            TrackPoint(
                from + (i * 5).minutes,
                home.latitude + (school.latitude - home.latitude) * f,
                home.longitude + (school.longitude - home.longitude) * f,
                20f,
            )
        }

    @Test
    fun `LOC-004 home then walk then school gives two stops at the right places`() {
        val points = stay(home, at(7, 0), at(8, 10)) + walk(at(8, 10), 2) + stay(school, at(8, 25), at(14, 40))
        val stops = Stops.detect(points.shuffled(), places, ongoingAfter = at(23, 0))
        assertEquals(2, stops.size, stops.toString())
        assertEquals(Triple("home", at(7, 0), at(8, 10)), Triple(stops[0].placeId, stops[0].start, stops[0].end))
        assertEquals(Triple("school", at(8, 25), at(14, 40)), Triple(stops[1].placeId, stops[1].start, stops[1].end))
        assertFalse(stops.any { it.ongoing })
    }

    @Test
    fun `LOC-004 a five minute pause is not a stop and a stop away from places has no place`() {
        val far = home.copy(latitude = 55.8)
        val points = stay(home, at(7, 0), at(7, 5)) + walk(at(7, 5), 2) + stay(far, at(7, 20), at(7, 40))
        val stops = Stops.detect(points, places, ongoingAfter = null)
        assertEquals(1, stops.size, stops.toString())
        assertEquals(null, stops[0].placeId)
        assertEquals(at(7, 20), stops[0].start)
    }

    @Test
    fun `LOC-004 inaccurate points are ignored`() {
        val noisy = stay(home, at(7, 0), at(8, 0)).mapIndexed { i, p -> if (i % 2 == 1) p.copy(latitude = 56.0, accuracyM = 500f) else p }
        val stops = Stops.detect(noisy, places, ongoingAfter = null)
        assertEquals(1, stops.size, stops.toString())
        assertEquals(at(7, 0) to at(8, 0), stops[0].start to stops[0].end)
        assertTrue(Stops.detect(stay(home, at(7, 0), at(8, 0), accuracy = 150f), places, null).isEmpty())
    }

    @Test
    fun `LOC-004 the last stop is ongoing while fresh`() {
        val points = stay(home, at(7, 0), at(7, 30)) + walk(at(7, 30), 2) + stay(school, at(7, 45), at(9, 0))
        val fresh = Stops.detect(points, places, ongoingAfter = at(8, 45))
        assertEquals(listOf(false, true), fresh.map { it.ongoing })
        assertEquals(listOf(false, false), Stops.detect(points, places, ongoingAfter = at(9, 0)).map { it.ongoing })
        assertEquals(listOf(false, false), Stops.detect(points, places, ongoingAfter = null).map { it.ongoing })
    }

    @Test
    fun `LOC-004 two stays at the same place within 15 minutes merge`() {
        val shop = home.copy(latitude = 55.73)
        val points =
            stay(school, at(9, 0), at(10, 0)) + listOf(TrackPoint(at(10, 5), shop.latitude, shop.longitude, 10f)) +
                stay(school, at(10, 10), at(11, 0))
        val stops = Stops.detect(points, places, ongoingAfter = null)
        assertEquals(1, stops.size, stops.toString())
        assertEquals(Triple("school", at(9, 0), at(11, 0)), Triple(stops[0].placeId, stops[0].start, stops[0].end))
        val later =
            stay(school, at(9, 0), at(10, 0)) + listOf(TrackPoint(at(10, 10), shop.latitude, shop.longitude, 10f)) +
                stay(school, at(10, 30), at(11, 0))
        assertEquals(2, Stops.detect(later, places, ongoingAfter = null).size)
    }
}
