package app.lares.server.location

import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.VisibilityLevel
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.location.Transition
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PermissionEvaluator
import app.lares.server.household.GroupRepository
import app.lares.server.household.MemberRepository
import app.lares.server.household.MemberTable
import app.lares.server.household.PersonTable
import app.lares.server.notifications.NotificationService
import app.lares.server.notifications.NotificationTexts
import app.lares.server.platform.db.AppDatabase
import app.lares.server.tasks.TaskRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.time.Instant

/**
 * Location notifications (LOC-012, LOC-021; architecture/location.md § Geofences, notifications, addresses), the
 * `TaskNotifier` pattern: the upload, `PUT /location/me/sharing` and ended sessions [enqueue] events through
 * `afterCommit`; the consumer ([start]) resolves recipients with fresh visibility inside one `dbQuery` and sends.
 * Positions never go into a notification or a log; only the place name and a time do.
 */
class LocationNotifier(
    private val db: AppDatabase,
    private val visibility: LocationVisibilityService,
    private val notifications: NotificationService,
) {
    sealed interface Event {
        val householdId: UUID
        val memberId: UUID
    }

    /** The member arrived at or left the place at [at] (the first agreeing reading). */
    data class PlaceTransition(
        override val householdId: UUID,
        override val memberId: UUID,
        val placeId: UUID,
        val transition: Transition,
        val at: Instant,
    ) : Event

    /** The member's sharing became OFF_ON_PHONE from SHARING or PAUSED. */
    data class SharingOff(
        override val householdId: UUID,
        override val memberId: UUID,
        val reason: OffReason,
    ) : Event

    /** The member's sharing became SHARING from OFF_ON_PHONE. */
    data class SharingBack(
        override val householdId: UUID,
        override val memberId: UUID,
    ) : Event

    private val log = KotlinLogging.logger {}
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val pending = MutableStateFlow(0)

    /** Never blocks; call it from `afterCommit`. */
    fun enqueue(event: Event) {
        pending.update { it + 1 }
        if (events.trySend(event).isFailure) pending.update { it - 1 }
    }

    /** The consumer: processes events one by one; failures are logged and the loop goes on. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            for (event in events) {
                try {
                    process(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "Location notification ${event::class.simpleName} for member ${event.memberId} failed" }
                } finally {
                    pending.update { it - 1 }
                }
            }
        }

    /** Suspends until every enqueued event has been processed (tests use it instead of waiting). */
    suspend fun awaitIdle() {
        pending.first { it == 0 }
    }

    private class Prepared(
        val type: NotificationType,
        val recipients: List<UUID>,
        val render: (language: String) -> NotificationTexts.Text,
    )

    suspend fun process(event: Event) {
        val prepared = db.dbQuery { prepare(this, event) } ?: return
        if (prepared.recipients.isEmpty()) return
        val results =
            notifications.notify(
                event.householdId,
                prepared.recipients,
                prepared.type,
                entityType = ENTITY_TYPE,
                entityId = event.memberId.toString(),
            ) { language -> prepared.render(language).let { it.title to it.body } }
        log.debug { "${prepared.type.key} about member ${event.memberId}: ${prepared.recipients.size} members, ${results.size} sessions" }
    }

    /** Runs inside `dbQuery`. Null when the member (or the place) is gone. */
    private fun prepare(
        tx: JdbcTransaction,
        event: Event,
    ): Prepared? {
        if (MemberRepository.get(event.householdId, event.memberId)?.deleted != false) return null
        val name = personName(event.householdId, event.memberId).orEmpty()
        return when (event) {
            is PlaceTransition -> preparePlace(tx, event, name)
            is SharingOff -> prepareSharing(event) { NotificationTexts.sharingOff(it, name, event.reason) }
            is SharingBack -> prepareSharing(event) { NotificationTexts.sharingBack(it, name) }
        }
    }

    /** Subscribers with an alert row about the member naming the place, who may still see the member (LOC-021). */
    private fun preparePlace(
        tx: JdbcTransaction,
        event: PlaceTransition,
        memberName: String,
    ): Prepared? {
        val place =
            PlaceTable
                .select(PlaceTable.name)
                .where {
                    (PlaceTable.id eq event.placeId) and (PlaceTable.householdId eq event.householdId) and
                        (PlaceTable.deleted eq false)
                }.singleOrNull()
                ?.get(PlaceTable.name) ?: return null
        val placeKey = event.placeId.toString()
        val subscribers =
            PlaceRepository
                .liveAlertsAbout(event.householdId, event.memberId)
                .filter { (subscriberId, placeIds) -> subscriberId != event.memberId && placeKey in placeIds }
                .map { it.first }
        if (subscribers.isEmpty()) return null
        // Level NONE covers a subscriber who is gone, lacks `location.view` or may no longer see the member.
        val levels = visibility.viewersOf(tx, event.householdId, event.memberId)
        val recipients = subscribers.filter { (levels[it] ?: VisibilityLevel.NONE) >= VisibilityLevel.CURRENT }
        val local = event.at.toLocalDateTime(TaskRepository.householdTimeZone(event.householdId)).time
        val time = "%02d:%02d".format(local.hour, local.minute)
        return Prepared(NotificationType.LOCATION_PLACE, recipients) { language ->
            when (event.transition) {
                Transition.ARRIVED -> NotificationTexts.placeArrived(language, memberName, place, time)
                Transition.LEFT -> NotificationTexts.placeLeft(language, memberName, place, time)
            }
        }
    }

    /** Administrators other than the member, only about members who may not pause themselves (LOC-012). */
    private fun prepareSharing(
        event: Event,
        render: (String) -> NotificationTexts.Text,
    ): Prepared? {
        val groups = GroupRepository.loadViews(event.householdId)
        if (PermissionEvaluator.evaluate(event.memberId.toString(), groups).has(Permission.LOCATION_PAUSE_OWN)) return null
        val admins =
            MemberTable
                .select(MemberTable.id)
                .where { (MemberTable.householdId eq event.householdId) and (MemberTable.deleted eq false) }
                .map { it[MemberTable.id] }
                .filter { it != event.memberId && PermissionEvaluator.evaluate(it.toString(), groups).isAdmin }
        return Prepared(NotificationType.LOCATION_SHARING, admins, render)
    }

    private fun personName(
        householdId: UUID,
        memberId: UUID,
    ): String? {
        val member = MemberRepository.get(householdId, memberId) ?: return null
        return PersonTable
            .select(PersonTable.name)
            .where { PersonTable.id eq member.personId }
            .singleOrNull()
            ?.get(PersonTable.name)
    }

    companion object {
        /** `entityType` of location notifications; `entityId` is the member id (the phone opens the member on the map). */
        const val ENTITY_TYPE = "location"
    }
}
