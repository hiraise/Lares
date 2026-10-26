package app.lares.server.location

import app.lares.contract.api.location.PositionFix
import app.lares.contract.api.location.TrackPoint
import app.lares.server.platform.toContract
import app.lares.server.platform.toDb
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import java.time.Instant
import java.util.UUID

/** `position` rows (ADR-0024). Every function runs inside `dbQuery`. Coordinates never go to logs. */
object PositionRepository {
    /** Stores a fix unless the member already has one at the same time; true if it was stored now. */
    fun insert(
        householdId: UUID,
        memberId: UUID,
        sessionId: UUID,
        fix: PositionFix,
        receivedAt: Instant,
    ): Boolean =
        PositionTable
            .insertIgnore {
                it[this.householdId] = householdId
                it[this.memberId] = memberId
                it[this.sessionId] = sessionId
                it[recordedAt] = fix.recordedAt.toDb()
                it[latitude] = fix.latitude
                it[longitude] = fix.longitude
                it[accuracyM] = fix.accuracyM
                it[speedMps] = fix.speedMps
                it[bearingDeg] = fix.bearingDeg
                it[altitudeM] = fix.altitudeM
                it[battery] = fix.battery?.toShort()
                it[charging] = fix.charging
                it[this.receivedAt] = receivedAt
            }.insertedCount > 0

    /** The member's points in `[from, to)` with accuracy at most [maxAccuracyM], oldest first. */
    fun track(
        memberId: UUID,
        from: Instant,
        to: Instant,
        maxAccuracyM: Float,
    ): List<TrackPoint> =
        PositionTable
            .select(PositionTable.recordedAt, PositionTable.latitude, PositionTable.longitude, PositionTable.accuracyM)
            .where {
                (PositionTable.memberId eq memberId) and (PositionTable.recordedAt greaterEq from) and
                    (PositionTable.recordedAt less to) and (PositionTable.accuracyM lessEq maxAccuracyM)
            }.orderBy(PositionTable.recordedAt to SortOrder.ASC)
            .map {
                TrackPoint(
                    recordedAt = it[PositionTable.recordedAt].toContract(),
                    latitude = it[PositionTable.latitude],
                    longitude = it[PositionTable.longitude],
                    accuracyM = it[PositionTable.accuracyM],
                )
            }

    /** Deletes at most [limit] of the household's positions recorded before [cutoff]; returns how many. */
    fun deleteBatchBefore(
        householdId: UUID,
        cutoff: Instant,
        limit: Int,
    ): Int =
        PositionTable.deleteWhere {
            PositionTable.id inSubQuery
                PositionTable
                    .select(PositionTable.id)
                    .where { (PositionTable.householdId eq householdId) and (PositionTable.recordedAt less cutoff) }
                    .limit(limit)
        }

    fun deleteOfMember(memberId: UUID) {
        PositionTable.deleteWhere { PositionTable.memberId eq memberId }
    }

    /**
     * At most [max] points, the first and the last included, evenly spaced by index (LOC-004). A day of 6 000 points
     * gives exactly [max].
     */
    fun <T> thin(
        points: List<T>,
        max: Int,
    ): List<T> {
        if (points.size <= max) return points
        val n = points.size
        return List(max) { i -> points[((i.toLong() * (n - 1) + (max - 1) / 2) / (max - 1)).toInt()] }
    }
}
