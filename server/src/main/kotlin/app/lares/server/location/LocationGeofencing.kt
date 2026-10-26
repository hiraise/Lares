package app.lares.server.location

import app.lares.contract.api.location.PositionFix
import app.lares.contract.location.Geo
import app.lares.contract.location.Geofence
import app.lares.contract.location.PresenceState
import app.lares.contract.location.Transition
import app.lares.server.platform.toContract
import app.lares.server.platform.toDb
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID
import kotlin.time.Instant

/** A confirmed arrival or departure; [at] is the time of the first agreeing reading. */
data class PlaceEvent(
    val placeId: UUID,
    val transition: Transition,
    val at: Instant,
)

/**
 * Arrivals and departures decided on the server from positions (LOC-022, architecture/location.md § Geofences): the
 * contract's [Geofence] hysteresis applied per member and place, kept in `place_presence`. Runs inside the upload's
 * transaction, after the member's state row is locked, so one member's rows are written by one transaction at a time.
 */
object LocationGeofencing {
    private class LivePlace(
        val id: UUID,
        val latitude: Double,
        val longitude: Double,
        val radiusM: Int,
    )

    /** Feeds [fixes] (stored by this upload) in `recordedAt` order to every live place; writes only changed rows. */
    @Suppress("UNUSED_PARAMETER")
    fun apply(
        tx: JdbcTransaction,
        householdId: UUID,
        memberId: UUID,
        fixes: List<PositionFix>,
    ): List<PlaceEvent> {
        if (fixes.isEmpty()) return emptyList()
        val places =
            PlaceTable
                .select(PlaceTable.id, PlaceTable.latitude, PlaceTable.longitude, PlaceTable.radiusM)
                .where { (PlaceTable.householdId eq householdId) and (PlaceTable.deleted eq false) }
                .map { LivePlace(it[PlaceTable.id], it[PlaceTable.latitude], it[PlaceTable.longitude], it[PlaceTable.radiusM]) }
        if (places.isEmpty()) return emptyList()
        val stored = load(memberId)
        val events = mutableListOf<PlaceEvent>()
        val ordered = fixes.sortedBy { it.recordedAt }
        places.forEach { place ->
            val before = stored[place.id]
            var state = before ?: UNKNOWN
            ordered.forEach { fix ->
                if (Geofence.usable(fix.accuracyM, place.radiusM)) {
                    val distance = Geo.distanceM(fix.latitude, fix.longitude, place.latitude, place.longitude)
                    val (next, transition) = Geofence.step(state, Geofence.reading(distance, fix.accuracyM, place.radiusM), fix.recordedAt)
                    state = next
                    if (transition != null) events += PlaceEvent(place.id, transition, next.since ?: fix.recordedAt)
                }
            }
            if (state != (before ?: UNKNOWN)) write(householdId, memberId, place.id, state)
        }
        return events.sortedBy { it.at }
    }

    /** The member's presence by place id (rows of deleted places are deleted with them). */
    fun load(memberId: UUID): Map<UUID, PresenceState> =
        PlacePresenceTable
            .selectAll()
            .where { PlacePresenceTable.memberId eq memberId }
            .associate {
                it[PlacePresenceTable.placeId] to
                    PresenceState(
                        inside = it[PlacePresenceTable.inside],
                        since = it[PlacePresenceTable.since]?.toContract(),
                        candidate = it[PlacePresenceTable.candidate],
                        candidateSince = it[PlacePresenceTable.candidateSince]?.toContract(),
                    )
            }

    /**
     * Where each of [memberIds] is now: the live place with `inside = true` and the smallest radius, with its `since`
     * (LOC-003); members outside every place are missing.
     */
    fun currentPlaces(
        householdId: UUID,
        memberIds: Collection<UUID>,
    ): Map<UUID, Pair<UUID, Instant>> {
        if (memberIds.isEmpty()) return emptyMap()
        return PlacePresenceTable
            .join(PlaceTable, JoinType.INNER, PlacePresenceTable.placeId, PlaceTable.id)
            .select(PlacePresenceTable.memberId, PlacePresenceTable.placeId, PlacePresenceTable.since, PlaceTable.radiusM)
            .where {
                (PlacePresenceTable.householdId eq householdId) and (PlacePresenceTable.memberId inList memberIds) and
                    (PlacePresenceTable.inside eq true) and (PlaceTable.deleted eq false)
            }.groupBy { it[PlacePresenceTable.memberId] }
            .mapValues { (_, rows) ->
                val row = rows.minWith(compareBy({ it[PlaceTable.radiusM] }, { it[PlacePresenceTable.placeId] }))
                row[PlacePresenceTable.placeId] to (row[PlacePresenceTable.since]?.toContract() ?: Instant.DISTANT_PAST)
            }
    }

    /** The place moved or changed its radius: everybody's presence there is unknown again (no notice on the next reading). */
    fun resetPlace(placeId: UUID) {
        PlacePresenceTable.update({ PlacePresenceTable.placeId eq placeId }) {
            it[inside] = null
            it[candidate] = null
            it[candidateSince] = null
        }
    }

    fun deleteOfPlace(placeId: UUID) {
        PlacePresenceTable.deleteWhere { PlacePresenceTable.placeId eq placeId }
    }

    fun deleteOfMember(memberId: UUID) {
        PlacePresenceTable.deleteWhere { PlacePresenceTable.memberId eq memberId }
    }

    private fun write(
        householdId: UUID,
        memberId: UUID,
        placeId: UUID,
        state: PresenceState,
    ) {
        PlacePresenceTable.upsert {
            it[this.memberId] = memberId
            it[this.placeId] = placeId
            it[this.householdId] = householdId
            it[inside] = state.inside
            it[since] = state.since?.toDb()
            it[candidate] = state.candidate
            it[candidateSince] = state.candidateSince?.toDb()
        }
    }

    private val UNKNOWN = PresenceState(inside = null, since = null)
}
