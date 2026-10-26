package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.location.AlertWatcher
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.LocationHistory
import app.lares.contract.api.location.LocationNow
import app.lares.contract.api.location.LocationWatchers
import app.lares.contract.api.location.MemberLocation
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.SetSharingRequest
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.api.location.UploadPositionsRequest
import app.lares.contract.api.location.UploadPositionsResponse
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.api.location.Watcher
import app.lares.contract.api.notifications.PushPayload
import app.lares.contract.location.LocationLimits
import app.lares.contract.permissions.Permission
import app.lares.contract.validation.Validators
import app.lares.server.household.PermissionService
import app.lares.server.identity.MemberPrincipal
import app.lares.server.notifications.NotificationService
import app.lares.server.platform.LocationConfigValues
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.platform.db.afterCommit
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.nowForDb
import app.lares.server.platform.toContract
import app.lares.server.platform.toDb
import app.lares.server.tasks.TaskRepository
import com.interaso.webpush.WebPush
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val log = KotlinLogging.logger {}

/**
 * Positions, sharing state, "now", day histories, «Обновить» and watchers (architecture/location.md § Positions, state,
 * live channel; ADR-0024). Positions are never logged.
 */
class LocationService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val config: LocationConfigValues,
    private val visibility: LocationVisibilityService,
    private val hub: LocationHub,
    private val notifications: NotificationService,
    private val permissions: PermissionService,
    private val notifier: LocationNotifier,
) {
    /** `PUT /location/me/sharing` (LOC-010, LOC-012, LOC-019). */
    suspend fun setSharing(
        principal: MemberPrincipal,
        request: SetSharingRequest,
    ) {
        val errors = Validators.setSharing(request, clock.now())
        if (errors.isNotEmpty()) throw invalid(errors)
        if (request.status == SharingStatus.PAUSED &&
            !permissions.effective(principal.householdId, principal.memberId).has(Permission.LOCATION_PAUSE_OWN)
        ) {
            throw ApiException(ErrorCode.FORBIDDEN, "Missing permission ${Permission.LOCATION_PAUSE_OWN.key}")
        }
        db.dbQuery {
            val now = clock.nowForDb()
            val stored = LocationStateRepository.lockOrCreate(principal.householdId, principal.memberId, now)
            val current = LocationStateRepository.effective(stored, now)
            val next =
                when (request.status) {
                    SharingStatus.SHARING -> {
                        current.copy(
                            status = SharingStatus.SHARING,
                            pausedUntil = null,
                            offReason = null,
                            sharingSessionId = principal.sessionId,
                            statusSince = if (current.status == SharingStatus.SHARING) current.statusSince else now,
                        )
                    }

                    SharingStatus.PAUSED -> {
                        current.copy(
                            status = SharingStatus.PAUSED,
                            pausedUntil = request.pausedUntil?.toDb(),
                            offReason = null,
                            statusSince = if (current.status == SharingStatus.PAUSED) current.statusSince else now,
                        )
                    }

                    SharingStatus.OFF_ON_PHONE -> {
                        // Only the sharing phone can say it stopped; an old phone saying so changes nothing.
                        if (current.sharingSessionId != principal.sessionId) {
                            current
                        } else {
                            current.copy(
                                status = SharingStatus.OFF_ON_PHONE,
                                pausedUntil = null,
                                offReason = request.reason,
                                statusSince = if (current.status == SharingStatus.OFF_ON_PHONE) current.statusSince else now,
                            )
                        }
                    }

                    SharingStatus.NEVER -> {
                        current
                    }
                }
            if (next != stored) {
                LocationStateRepository.writeStatus(next, now)
                afterCommit { hub.publish(LocationHubEvent.MemberChanged(principal.householdId, principal.memberId)) }
            }
            sharingEvent(principal, current.status, next)?.let { event -> afterCommit { notifier.enqueue(event) } }
        }
    }

    /** LOC-012: one notice per change into OFF_ON_PHONE (from SHARING or PAUSED) and back to SHARING. */
    private fun sharingEvent(
        principal: MemberPrincipal,
        before: SharingStatus,
        after: LocationState,
    ): LocationNotifier.Event? =
        when {
            after.status == SharingStatus.OFF_ON_PHONE && (before == SharingStatus.SHARING || before == SharingStatus.PAUSED) -> {
                LocationNotifier.SharingOff(principal.householdId, principal.memberId, after.offReason ?: OffReason.STOPPED)
            }

            after.status == SharingStatus.SHARING && before == SharingStatus.OFF_ON_PHONE -> {
                LocationNotifier.SharingBack(principal.householdId, principal.memberId)
            }

            else -> {
                null
            }
        }

    /** `POST /location/positions` (LOC-001, LOC-010, LOC-011, LOC-019, LOC-027); the numbered steps of the design. */
    suspend fun upload(
        principal: MemberPrincipal,
        request: UploadPositionsRequest,
    ): UploadPositionsResponse {
        if (request.positions.isEmpty() || request.positions.size > LocationLimits.MAX_UPLOAD) {
            throw invalid(mapOf("positions" to FieldErrorCode.OUT_OF_RANGE))
        }
        val result =
            db.dbQuery {
                val now = clock.nowForDb()
                val stored = LocationStateRepository.lockOrCreate(principal.householdId, principal.memberId, now)
                var state = LocationStateRepository.effective(stored, now)
                if (state.sharingSessionId != principal.sessionId || state.status == SharingStatus.NEVER) {
                    if (state != stored) LocationStateRepository.writeStatus(state, now)
                    return@dbQuery UploadPositionsResponse(accepted = 0, sharingHere = false)
                }
                if (state.status == SharingStatus.PAUSED) {
                    return@dbQuery UploadPositionsResponse(
                        accepted = 0,
                        sharingHere = true,
                        paused = true,
                        pausedUntil = state.pausedUntil?.toContract(),
                    )
                }
                val nowInstant = now.toContract()
                val oldest = nowInstant - LocationLimits.MAX_FIX_AGE_HOURS.hours
                val newest = nowInstant + LocationLimits.MAX_FUTURE_SKEW_MINUTES.minutes
                val valid =
                    request.positions
                        .filter { it.recordedAt in oldest..newest && Validators.positionFix(it).isEmpty() }
                        .sortedBy { it.recordedAt }
                val inserted =
                    valid.filter {
                        PositionRepository.insert(
                            principal.householdId,
                            principal.memberId,
                            principal.sessionId,
                            it,
                            now,
                        )
                    }
                val newestFix = inserted.lastOrNull()
                val lastAt = state.last?.recordedAt
                val lastChanged = newestFix != null && (lastAt == null || newestFix.recordedAt.toDb() > lastAt.toDb())
                if (lastChanged) LocationStateRepository.writeLast(principal.memberId, newestFix, now)
                if (inserted.isNotEmpty() && state.status == SharingStatus.OFF_ON_PHONE) {
                    // The phone shares again.
                    val back = state.copy(status = SharingStatus.SHARING, offReason = null, statusSince = now)
                    sharingEvent(principal, state.status, back)?.let { event -> afterCommit { notifier.enqueue(event) } }
                    state = back
                }
                if (state != stored) LocationStateRepository.writeStatus(state, now)
                // Arrivals and departures; a late backlog (older than 15 minutes) only updates presence (LOC-021).
                val recent = nowInstant - NOTIFY_TRANSITIONS_WITHIN
                val transitions =
                    LocationGeofencing
                        .apply(this, principal.householdId, principal.memberId, inserted)
                        .filter { it.at >= recent }
                        .map {
                            LocationNotifier.PlaceTransition(
                                principal.householdId,
                                principal.memberId,
                                it.placeId,
                                it.transition,
                                it.at,
                            )
                        }
                if (transitions.isNotEmpty()) afterCommit { transitions.forEach(notifier::enqueue) }
                // Server times only: a fix stored now arrived after the request, whatever the device clock says.
                val refreshRequested = state.refreshRequestedAt != null && inserted.isEmpty()
                if (state.refreshRequestedAt != null && inserted.isNotEmpty()) {
                    LocationStateRepository.writeRefresh(principal.memberId, requestedAt = null, sentAt = state.refreshSentAt)
                }
                if (inserted.isNotEmpty() || state != stored) {
                    afterCommit { hub.publish(LocationHubEvent.MemberChanged(principal.householdId, principal.memberId)) }
                }
                UploadPositionsResponse(accepted = inserted.size, sharingHere = true, refreshRequested = refreshRequested)
            }
        log.debug { "Upload: ${request.positions.size} fixes, ${result.accepted} stored" }
        return result
    }

    /** `GET /location/now` (LOC-003); the caller has `location.view`. */
    suspend fun now(principal: MemberPrincipal): LocationNow =
        db.dbQuery { snapshot(this, principal.householdId, principal.memberId).second }

    /**
     * The viewer's levels for all live members and what they see now: every member with level ≥ CURRENT (the viewer
     * included), members without a state as NEVER, an expired pause as SHARING. Shared by `GET /now` and the live channel.
     */
    fun snapshot(
        tx: JdbcTransaction,
        householdId: UUID,
        viewerMemberId: UUID,
    ): Pair<Map<UUID, VisibilityLevel>, LocationNow> {
        val now = clock.nowForDb()
        val levels = visibility.levels(tx, householdId, viewerMemberId)
        val states = LocationStateRepository.load(householdId)
        val visible = levels.filterValues { it >= VisibilityLevel.CURRENT }
        val places = LocationGeofencing.currentPlaces(householdId, visible.keys)
        val members =
            visible.map { (memberId, level) ->
                LocationStateRepository.memberLocation(
                    memberId,
                    level,
                    states[memberId]?.let { LocationStateRepository.effective(it, now) },
                    places[memberId],
                )
            }
        return levels to LocationNow(members, now.toContract())
    }

    /** One member as the viewer sees them, with a fresh level; null when the viewer may not see them. */
    fun memberFor(
        tx: JdbcTransaction,
        householdId: UUID,
        viewerMemberId: UUID,
        memberId: UUID,
    ): MemberLocation? {
        val level = visibility.level(tx, householdId, viewerMemberId, memberId)
        if (level < VisibilityLevel.CURRENT) return null
        val state = LocationStateRepository.load(householdId, listOf(memberId))[memberId]
        return LocationStateRepository.memberLocation(
            memberId,
            level,
            state?.let { LocationStateRepository.effective(it, clock.nowForDb()) },
            LocationGeofencing.currentPlaces(householdId, listOf(memberId))[memberId],
        )
    }

    /** `GET /location/history` (LOC-004): the member's household-local day, thinned; the caller has `location.view`. */
    suspend fun history(
        principal: MemberPrincipal,
        memberId: String?,
        date: String?,
    ): LocationHistory {
        val day =
            date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: throw invalid(mapOf("date" to if (date == null) FieldErrorCode.REQUIRED else FieldErrorCode.INVALID_FORMAT))
        val target = memberId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw notFound()
        return db.dbQuery {
            when (visibility.level(this, principal.householdId, principal.memberId, target)) {
                VisibilityLevel.NONE -> throw notFound()
                VisibilityLevel.CURRENT -> throw ApiException(ErrorCode.FORBIDDEN, "No history access")
                VisibilityLevel.HISTORY -> Unit
            }
            val zone = TaskRepository.householdTimeZone(principal.householdId)
            val cutoff = clock.now() - historyDays(principal.householdId).days
            val from = maxOf(day.atStartOfDayIn(zone), cutoff)
            val to = day.plus(DatePeriod(days = 1)).atStartOfDayIn(zone)
            val points =
                if (from >= to) {
                    emptyList()
                } else {
                    PositionRepository.track(target, from.toDb(), to.toDb(), MAX_HISTORY_ACCURACY_M)
                }
            LocationHistory(target.toString(), day, PositionRepository.thin(points, LocationLimits.MAX_HISTORY_POINTS))
        }
    }

    /** `POST /location/members/{id}/refresh` (LOC-011): marks the request and pushes the sharing phone at most once a minute. */
    suspend fun refresh(
        principal: MemberPrincipal,
        memberId: String,
    ) {
        val target = runCatching { UUID.fromString(memberId) }.getOrNull() ?: throw notFound()
        val pushTo =
            db.dbQuery {
                if (visibility.level(this, principal.householdId, principal.memberId, target) < VisibilityLevel.CURRENT) throw notFound()
                val now = clock.nowForDb()
                val stored = LocationStateRepository.lockOrCreate(principal.householdId, target, now)
                val state = LocationStateRepository.effective(stored, now)
                if (state.status != SharingStatus.SHARING) throw ApiException(ErrorCode.CONFLICT, "The member does not share now")
                if (state != stored) LocationStateRepository.writeStatus(state, now)
                val sentAt = state.refreshSentAt
                val push = sentAt == null || !sentAt.isAfter(now.minus(REFRESH_PUSH_INTERVAL))
                LocationStateRepository.writeRefresh(target, requestedAt = now, sentAt = if (push) now else sentAt)
                if (push) state.sharingSessionId else null
            }
        if (pushTo != null) {
            notifications.sendToSession(pushTo, PushPayload(PushPayload.KIND_LOCATION_REFRESH), REFRESH_TTL, WebPush.Urgency.High)
        }
    }

    /** `GET /location/me/watchers` (LOC-015): who sees me and who gets my arrive / leave alerts. */
    suspend fun watchers(principal: MemberPrincipal): LocationWatchers =
        db.dbQuery {
            val viewers = visibility.viewersOf(this, principal.householdId, principal.memberId)
            val seeing = viewers.filter { (id, level) -> id != principal.memberId && level >= VisibilityLevel.CURRENT }
            val alerts =
                PlaceRepository
                    .liveAlertsAbout(principal.householdId, principal.memberId)
                    .filter { (subscriberId, _) -> subscriberId in seeing }
                    .mapNotNull { (subscriberId, placeIds) ->
                        val ids = placeIds.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
                        val live = PlaceRepository.liveIds(principal.householdId, ids)
                        if (live.isEmpty()) null else AlertWatcher(subscriberId.toString(), live.mapTo(sortedSetOf()) { it.toString() })
                    }.sortedBy { it.memberId }
            LocationWatchers(
                viewers = seeing.map { (id, level) -> Watcher(id.toString(), level) }.sortedBy { it.memberId },
                alerts = alerts,
            )
        }

    /** `GET /location/config`. */
    suspend fun config(principal: MemberPrincipal): LocationConfig =
        LocationConfig(
            mapStyleUrl = config.clientUrl(config.mapStyleUrl),
            mapDarkStyleUrl = config.clientUrl(config.mapDarkStyleUrl),
            addresses = config.geocoderUrl != null,
            historyDays = db.dbQuery { historyDays(principal.householdId) },
            labelFont = config.labelFont,
        )

    private fun historyDays(householdId: UUID): Int =
        HouseholdTable
            .select(HouseholdTable.locationHistoryDays)
            .where { HouseholdTable.id eq householdId }
            .single()[HouseholdTable.locationHistoryDays]

    private fun notFound() = ApiException(ErrorCode.NOT_FOUND, "Member not found")

    private fun invalid(errors: Map<String, FieldErrorCode>) =
        ApiException(ErrorCode.VALIDATION_FAILED, "Invalid fields: ${errors.keys.joinToString()}", errors)

    companion object {
        /** History shows only fixes at least this accurate (LOC-004). */
        const val MAX_HISTORY_ACCURACY_M = 200f
        val REFRESH_PUSH_INTERVAL: java.time.Duration = LocationLimits.REFRESH_COOLDOWN_SECONDS.seconds.toJavaDuration()
        val REFRESH_TTL = 2.minutes

        /** Transitions found from older positions update presence without a notice (LOC-021 assumption). */
        val NOTIFY_TRANSITIONS_WITHIN = 15.minutes
    }
}
