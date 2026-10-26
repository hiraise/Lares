package app.lares.server.location

import app.lares.contract.api.location.OffReason
import app.lares.contract.api.location.SharingStatus
import app.lares.server.identity.SessionTable
import app.lares.server.platform.db.afterCommit
import app.lares.server.platform.nowForDb
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock

/**
 * Sessions ending (architecture/location.md § Sessions ending, LOC-012): a phone that signs out stops sharing, and live
 * sockets of the session close. Called inside the transaction that revokes the sessions.
 */
class LocationSessions(
    private val clock: Clock,
    private val hub: LocationHub,
    private val notifier: LocationNotifier,
) {
    /** One session ended (logout, revoke, refresh-token expiry or reuse). */
    fun onSessionEnded(
        tx: JdbcTransaction,
        sessionId: UUID,
    ) {
        val householdId =
            SessionTable
                .select(SessionTable.householdId)
                .where { SessionTable.id eq sessionId }
                .singleOrNull()
                ?.get(SessionTable.householdId) ?: return
        ended(tx, householdId, listOf(sessionId))
    }

    /** All sessions of the user end (password reset). Call before they are revoked: only live ones are told. */
    fun onUserSessionsEnded(
        tx: JdbcTransaction,
        userId: UUID,
    ) {
        SessionTable
            .select(SessionTable.id, SessionTable.householdId)
            .where { (SessionTable.userId eq userId) and SessionTable.revokedAt.isNull() }
            .groupBy({ it[SessionTable.householdId] }, { it[SessionTable.id] })
            .forEach { (householdId, sessionIds) -> ended(tx, householdId, sessionIds) }
    }

    private fun ended(
        tx: JdbcTransaction,
        householdId: UUID,
        sessionIds: List<UUID>,
    ) {
        val now = clock.nowForDb()
        val rows =
            LocationStateTable
                .selectAll()
                .where { LocationStateTable.sharingSessionId inList sessionIds }
                .forUpdate()
                .map { it[LocationStateTable.memberId] to it[LocationStateTable.status] }
        val members = rows.map { it.first }
        // Only a phone that was sharing or paused tells administrators; one already off changes its reason silently.
        val stopped =
            rows
                .filter { (_, status) -> status == SharingStatus.SHARING.key || status == SharingStatus.PAUSED.key }
                .map { (memberId, _) -> LocationNotifier.SharingOff(householdId, memberId, OffReason.SIGNED_OUT) }
        members.forEach { memberId ->
            LocationStateTable.update({ LocationStateTable.memberId eq memberId }) {
                it[status] = SharingStatus.OFF_ON_PHONE.key
                it[offReason] = OffReason.SIGNED_OUT.key
                it[pausedUntil] = null
                it[sharingSessionId] = null
                it[statusSince] = now
                it[updatedAt] = now
            }
        }
        tx.afterCommit {
            stopped.forEach(notifier::enqueue)
            members.forEach { hub.publish(LocationHubEvent.MemberChanged(householdId, it)) }
            sessionIds.forEach { hub.publish(LocationHubEvent.SessionEnded(householdId, it)) }
        }
    }
}
