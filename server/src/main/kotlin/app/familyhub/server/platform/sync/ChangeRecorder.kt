package app.familyhub.server.platform.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.util.UUID

/** A household's data changed; [seq] is the newest committed change sequence. */
data class HouseholdChanged(
    val householdId: UUID,
    val seq: Long,
)

/** In-process notifications about committed household changes (consumed by realtime sync events, M0-05). */
class HouseholdChangeBus {
    private val flow = MutableSharedFlow<HouseholdChanged>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<HouseholdChanged> = flow.asSharedFlow()

    fun publish(event: HouseholdChanged) {
        flow.tryEmit(event)
    }
}

/** Changes recorded in the current transaction, published by `AppDatabase.dbQuery` after commit. */
class PendingChanges {
    private val byHousehold = LinkedHashMap<UUID, Long>()

    /** One event per household with its last seq; that is all a listener needs to pull. */
    val events: List<HouseholdChanged>
        get() = byHousehold.map { (householdId, seq) -> HouseholdChanged(householdId, seq) }

    fun add(
        householdId: UUID,
        seq: Long,
    ) {
        byHousehold[householdId] = seq
    }

    companion object {
        val Key = Key<PendingChanges>()
    }
}

/**
 * Locks the household's sync state row until commit. Called first in every sync mutation transaction (M0-05),
 * so that concurrent writers in one household are serialized before they read anything.
 */
fun JdbcTransaction.lockHousehold(householdId: UUID) {
    val found =
        exec(
            "SELECT 1 FROM household_sync_state WHERE household_id = ? FOR UPDATE",
            listOf(UUIDColumnType() to householdId),
        ) { rs -> rs.next() } ?: false
    check(found) { "No sync state for household $householdId" }
}

/**
 * Allocates the next change sequence of the household. The row stays locked until commit,
 * so commit order equals seq order (architecture/sync.md § Allocating seq).
 */
fun JdbcTransaction.nextSeq(householdId: UUID): Long =
    exec(
        "UPDATE household_sync_state SET last_seq = last_seq + 1 WHERE household_id = ? RETURNING last_seq",
        listOf(UUIDColumnType() to householdId),
        // RETURNING produces a result set, so it must be executed as a query.
        explicitStatementType = StatementType.SELECT,
    ) { rs -> if (rs.next()) rs.getLong(1) else null }
        ?: error("No sync state for household $householdId")

/**
 * Allocates a seq for a write to a syncable row and schedules a [HouseholdChanged] event after commit.
 * Every write to a syncable table goes through this function. Must run inside `AppDatabase.dbQuery`.
 */
fun JdbcTransaction.recordChange(householdId: UUID): Long {
    val seq = nextSeq(householdId)
    val pending = getUserData(PendingChanges.Key) ?: error("recordChange must run inside AppDatabase.dbQuery")
    pending.add(householdId, seq)
    return seq
}
