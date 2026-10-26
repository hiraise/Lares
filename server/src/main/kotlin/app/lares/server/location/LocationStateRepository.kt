package app.lares.server.location

import app.lares.contract.api.location.MemberLocation
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.VisibilityLevel
import app.lares.server.platform.toContract
import app.lares.server.platform.toDb
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/** A member's stored sharing state (`location_state`). Times are database instants. */
data class LocationState(
    val memberId: UUID,
    val householdId: UUID,
    val status: SharingStatus,
    val pausedUntil: Instant?,
    val offReason: OffReason?,
    val statusSince: Instant,
    val sharingSessionId: UUID?,
    val last: PositionFix?,
    val refreshRequestedAt: Instant?,
    val refreshSentAt: Instant?,
)

/** `location_state` rows. Every function runs inside `dbQuery`. */
object LocationStateRepository {
    /**
     * Locks the member's state row, inserting a NEVER row first when missing (architecture/location.md § Concurrency),
     * so the state of one member is written by one transaction at a time.
     */
    fun lockOrCreate(
        householdId: UUID,
        memberId: UUID,
        now: Instant,
    ): LocationState {
        LocationStateTable.insertIgnore {
            it[this.memberId] = memberId
            it[this.householdId] = householdId
            it[status] = SharingStatus.NEVER.key
            it[statusSince] = now
            it[updatedAt] = now
        }
        return LocationStateTable
            .selectAll()
            .where { LocationStateTable.memberId eq memberId }
            .forUpdate()
            .single()
            .let(::toState)
    }

    /** States of [memberIds] (all of the household when null), by member id. */
    fun load(
        householdId: UUID,
        memberIds: Collection<UUID>? = null,
    ): Map<UUID, LocationState> =
        LocationStateTable
            .selectAll()
            .where {
                val household = LocationStateTable.householdId eq householdId
                if (memberIds == null) household else household and (LocationStateTable.memberId inList memberIds)
            }.associate { it[LocationStateTable.memberId] to toState(it) }

    /**
     * The state as it is in effect at [now]: a pause whose end has passed is SHARING since that end
     * (architecture/location.md § Effective state).
     */
    fun effective(
        state: LocationState,
        now: Instant,
    ): LocationState {
        val until = state.pausedUntil
        return if (state.status == SharingStatus.PAUSED && until != null && until <= now) {
            state.copy(status = SharingStatus.SHARING, pausedUntil = null, statusSince = until)
        } else {
            state
        }
    }

    /** Writes the status columns of [state] (not `last_*`). */
    fun writeStatus(
        state: LocationState,
        now: Instant,
    ) {
        LocationStateTable.update({ LocationStateTable.memberId eq state.memberId }) {
            it[status] = state.status.key
            it[pausedUntil] = state.pausedUntil
            it[offReason] = state.offReason?.key
            it[statusSince] = state.statusSince
            it[sharingSessionId] = state.sharingSessionId
            it[updatedAt] = now
        }
    }

    fun writeLast(
        memberId: UUID,
        fix: PositionFix,
        now: Instant,
    ) {
        LocationStateTable.update({ LocationStateTable.memberId eq memberId }) {
            it[lastRecordedAt] = fix.recordedAt.toDb()
            it[lastLatitude] = fix.latitude
            it[lastLongitude] = fix.longitude
            it[lastAccuracyM] = fix.accuracyM
            it[lastSpeedMps] = fix.speedMps
            it[lastBearingDeg] = fix.bearingDeg
            it[lastAltitudeM] = fix.altitudeM
            it[lastBattery] = fix.battery?.toShort()
            it[lastCharging] = fix.charging
            it[updatedAt] = now
        }
    }

    fun writeRefresh(
        memberId: UUID,
        requestedAt: Instant?,
        sentAt: Instant?,
    ) {
        LocationStateTable.update({ LocationStateTable.memberId eq memberId }) {
            it[refreshRequestedAt] = requestedAt
            it[refreshSentAt] = sentAt
        }
    }

    /** Clears `last_*` of the household's states whose newest fix is older than [cutoff] (retention). */
    fun clearLastBefore(
        householdId: UUID,
        cutoff: Instant,
    ): Int =
        LocationStateTable.update({
            (LocationStateTable.householdId eq householdId) and LocationStateTable.lastRecordedAt.isNotNull() and
                (LocationStateTable.lastRecordedAt less cutoff)
        }) {
            it[lastRecordedAt] = null
            it[lastLatitude] = null
            it[lastLongitude] = null
            it[lastAccuracyM] = null
            it[lastSpeedMps] = null
            it[lastBearingDeg] = null
            it[lastAltitudeM] = null
            it[lastBattery] = null
            it[lastCharging] = null
        }

    fun deleteOfMember(memberId: UUID) {
        LocationStateTable.deleteWhere { LocationStateTable.memberId eq memberId }
    }

    /**
     * What a viewer with [level] gets of the member; members without a state row are NEVER. [place] is the place the
     * member is in and since when (`LocationGeofencing.currentPlaces`).
     */
    fun memberLocation(
        memberId: UUID,
        level: VisibilityLevel,
        state: LocationState?,
        place: Pair<UUID, kotlin.time.Instant>? = null,
    ): MemberLocation =
        if (state == null) {
            MemberLocation(memberId = memberId.toString(), level = level, status = SharingStatus.NEVER)
        } else {
            MemberLocation(
                memberId = memberId.toString(),
                level = level,
                status = state.status,
                pausedUntil = state.pausedUntil?.toContract(),
                offReason = state.offReason,
                statusSince = state.statusSince.toContract(),
                last = state.last,
                placeId = place?.first?.toString(),
                placeSince = place?.second,
            )
        }

    private fun toState(row: ResultRow): LocationState {
        val lastAt = row[LocationStateTable.lastRecordedAt]
        return LocationState(
            memberId = row[LocationStateTable.memberId],
            householdId = row[LocationStateTable.householdId],
            status = SharingStatus.entries.firstOrNull { it.key == row[LocationStateTable.status] } ?: SharingStatus.NEVER,
            pausedUntil = row[LocationStateTable.pausedUntil],
            offReason =
                row[LocationStateTable.offReason]?.let { key ->
                    OffReason.entries.firstOrNull { it.key == key } ?: OffReason.STOPPED
                },
            statusSince = row[LocationStateTable.statusSince],
            sharingSessionId = row[LocationStateTable.sharingSessionId],
            last =
                if (lastAt == null) {
                    null
                } else {
                    PositionFix(
                        recordedAt = lastAt.toContract(),
                        latitude = row[LocationStateTable.lastLatitude] ?: 0.0,
                        longitude = row[LocationStateTable.lastLongitude] ?: 0.0,
                        accuracyM = row[LocationStateTable.lastAccuracyM] ?: 0f,
                        speedMps = row[LocationStateTable.lastSpeedMps],
                        bearingDeg = row[LocationStateTable.lastBearingDeg],
                        altitudeM = row[LocationStateTable.lastAltitudeM],
                        battery = row[LocationStateTable.lastBattery]?.toInt(),
                        charging = row[LocationStateTable.lastCharging],
                    )
                },
            refreshRequestedAt = row[LocationStateTable.refreshRequestedAt],
            refreshSentAt = row[LocationStateTable.refreshSentAt],
        )
    }
}
