package app.lares.contract.location

import app.lares.contract.api.location.TrackPoint
import app.lares.contract.entities.PlaceData
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A place where a member stayed (LOC-004); [placeId] is the family place containing it, if any. */
data class Stop(
    val start: Instant,
    val end: Instant,
    val latitude: Double,
    val longitude: Double,
    val placeId: String?,
    /** The member is still there. */
    val ongoing: Boolean,
)

/** Stops in a day's track (architecture/location.md § Pure logic). */
object Stops {
    const val RADIUS_M = 100.0
    const val MIN_DURATION_MINUTES = 10
    const val MAX_ACCURACY_M = 100f
    const val MERGE_GAP_MINUTES = 15

    /**
     * Clusters of accurate points within [RADIUS_M] of the cluster's first point lasting at least
     * [MIN_DURATION_MINUTES]. The last cluster is ongoing when [ongoingAfter] (now − stale time) is before its end.
     * Neighbouring stops at the same place less than [MERGE_GAP_MINUTES] apart are merged.
     */
    fun detect(
        points: List<TrackPoint>,
        places: List<Pair<String, PlaceData>>,
        ongoingAfter: Instant?,
    ): List<Stop> {
        val sorted = points.filter { it.accuracyM <= MAX_ACCURACY_M }.sortedBy { it.recordedAt }
        if (sorted.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<TrackPoint>>()
        for (point in sorted) {
            val current = clusters.lastOrNull()
            val first = current?.first()
            if (first != null && Geo.distanceM(first.latitude, first.longitude, point.latitude, point.longitude) <= RADIUS_M) {
                current.add(point)
            } else {
                clusters.add(mutableListOf(point))
            }
        }
        val last = sorted.last()
        val stops = mutableListOf<Candidate>()
        for (cluster in clusters) {
            val start = cluster.first().recordedAt
            val end = cluster.last().recordedAt
            if (end - start < MIN_DURATION_MINUTES.minutes) continue
            val ongoing = cluster.last() === last && ongoingAfter != null && ongoingAfter < end
            val merged = Candidate(cluster, ongoing, places)
            val previous = stops.lastOrNull()
            if (previous != null &&
                previous.placeId != null &&
                previous.placeId == merged.placeId &&
                start - previous.end <= MERGE_GAP_MINUTES.minutes
            ) {
                stops[stops.lastIndex] = Candidate(previous.points + cluster, ongoing, places)
            } else {
                stops.add(merged)
            }
        }
        return stops.map { it.toStop() }
    }

    private class Candidate(
        val points: List<TrackPoint>,
        val ongoing: Boolean,
        places: List<Pair<String, PlaceData>>,
    ) {
        val end: Instant = points.last().recordedAt
        val latitude: Double = points.sumOf { it.latitude } / points.size
        val longitude: Double = points.sumOf { it.longitude } / points.size
        val placeId: String? =
            places
                .filter { (_, p) -> Geo.distanceM(p.latitude, p.longitude, latitude, longitude) <= p.radiusM }
                .minByOrNull { (_, p) -> p.radiusM }
                ?.first

        fun toStop(): Stop = Stop(points.first().recordedAt, end, latitude, longitude, placeId, ongoing)
    }
}
