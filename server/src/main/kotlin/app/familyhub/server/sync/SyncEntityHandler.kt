package app.familyhub.server.sync

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.permissions.Viewer
import app.familyhub.contract.sync.Change
import app.familyhub.contract.sync.Mutation
import app.familyhub.server.platform.http.ApiException
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.util.UUID

/** One changed row as a handler reads it; [data] is null for tombstones. */
data class ChangeRow(
    val type: String,
    val id: String,
    val seq: Long,
    val deleted: Boolean,
    val data: JsonObject?,
) {
    fun toChange() = Change(type, id, seq, deleted, data)
}

/** The member and time a mutation is applied with. [currentYear] bounds birth years. */
data class MutationContext(
    val householdId: UUID,
    val viewer: Viewer,
    val now: Instant,
    val currentYear: Int,
)

/**
 * One syncable entity type (architecture/sync.md). Every handler reads its own table; the pull service merges
 * the rows of all handlers by seq. All functions run inside `dbQuery`.
 */
interface SyncEntityHandler {
    /** Wire name, e.g. `tag` (`EntityTypes`). */
    val type: String
    val clientWritable: Boolean

    /** Rows of the household with `after < seq ≤ upTo`, ordered by seq, at most [limit]. */
    fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow>

    /** M0: every member sees every row. Filtered rows still advance the cursor. */
    fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = true

    /**
     * Applies a mutation after the household lock and the idempotency check. Returns the new seq, or null for a no-op;
     * throws [ApiException] to reject. Must not write anything before it may still reject.
     */
    fun apply(
        tx: JdbcTransaction,
        ctx: MutationContext,
        mutation: Mutation,
    ): Long?
}

/** A type that is written only through admin REST endpoints. */
abstract class ReadOnlySyncHandler : SyncEntityHandler {
    override val clientWritable: Boolean = false

    override fun apply(
        tx: JdbcTransaction,
        ctx: MutationContext,
        mutation: Mutation,
    ): Long? = throw ApiException(ErrorCode.FORBIDDEN, "Type $type cannot be changed through sync")
}

/** The sync columns every syncable table has (architecture/sync.md § Server data model). */
class SyncColumns(
    val table: Table,
    val householdId: Column<UUID>,
    val seq: Column<Long>,
    val deleted: Column<Boolean>,
) {
    /** The household's rows with `after < seq ≤ upTo` in seq order; tombstones only if [includeTombstones]. */
    fun changed(
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ResultRow> {
        var condition = (this.householdId eq householdId) and (seq greater after) and (seq lessEq upTo)
        if (!includeTombstones) condition = condition and (deleted eq false)
        return table
            .selectAll()
            .where { condition }
            .orderBy(seq, SortOrder.ASC)
            .limit(limit)
            .toList()
    }
}

/** The registered handlers by wire name. */
class SyncHandlers(
    handlers: List<SyncEntityHandler>,
) {
    val all: List<SyncEntityHandler> = handlers
    private val byType = handlers.associateBy { it.type }

    init {
        require(byType.size == handlers.size) { "Duplicate sync handler types" }
    }

    operator fun get(type: String): SyncEntityHandler? = byType[type]
}
