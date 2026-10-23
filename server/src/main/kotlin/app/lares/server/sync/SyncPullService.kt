package app.lares.server.sync

import app.lares.contract.api.ErrorCode
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.PullResponse
import app.lares.contract.sync.SyncLimits
import app.lares.server.household.MemberRepository
import app.lares.server.household.PermissionService
import app.lares.server.identity.MemberPrincipal
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.platform.http.ApiException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.Connection

/** Parameters of `GET /api/v1/sync/changes`; [accessVersion] and [epoch] are what the client's data belongs to. */
data class PullRequest(
    val after: Long,
    val limit: Int = SyncLimits.DEFAULT_PULL_LIMIT,
    val accessVersion: Long? = null,
    val epoch: String? = null,
)

/** Pull (architecture/sync.md § Pull): one snapshot, reset rules, merge of all handlers by seq. */
class SyncPullService(
    private val db: AppDatabase,
    private val handlers: SyncHandlers,
    private val permissions: PermissionService,
) {
    /** Test seam: called inside the pull transaction after each handler's query, with the handler's type. */
    @Volatile
    var afterHandlerQuery: ((String) -> Unit)? = null

    suspend fun pull(
        principal: MemberPrincipal,
        request: PullRequest,
    ): PullResponse {
        if (request.after < 0) throw ApiException(ErrorCode.VALIDATION_FAILED, "after must be >= 0")
        if (request.limit !in 1..SyncLimits.MAX_PULL_LIMIT) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "limit must be in 1..${SyncLimits.MAX_PULL_LIMIT}")
        }
        val householdId = principal.householdId
        // One REPEATABLE READ snapshot for every query: a change committed between two table queries is either
        // visible to all of them or to none (invariant 9).
        return db.dbQuery(isolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
            val state = HouseholdSyncStateTable.selectAll().where { HouseholdSyncStateTable.householdId eq householdId }.single()
            val lastSeq = state[HouseholdSyncStateTable.lastSeq]
            val purgeHorizon = state[HouseholdSyncStateTable.purgeHorizonSeq]
            val epoch = state[HouseholdSyncStateTable.syncEpoch].toString()
            val member = MemberRepository.get(householdId, principal.memberId) ?: error("Member ${principal.memberId} not found")
            val accessVersion = member.accessVersion

            val after = request.after
            // Reset rules 1–4 in order. Missing accessVersion or epoch on an incremental pull also resets:
            // the client cannot prove its data is current.
            val reset =
                after > 0 &&
                    (
                        after < purgeHorizon ||
                            request.accessVersion != accessVersion ||
                            after > lastSeq ||
                            request.epoch != epoch
                    )
            if (reset) {
                return@dbQuery PullResponse(true, accessVersion, epoch, emptyList(), cursor = 0, hasMore = false)
            }

            val viewer =
                Viewer(
                    memberId = member.id.toString(),
                    personId = member.personId.toString(),
                    permissions = permissions.effectiveInTransaction(householdId, member.id),
                )
            val includeTombstones = after > 0
            // limit + 1 rows per handler: the first `limit` of the merge are then exactly the first `limit` overall,
            // and more than `limit` merged rows means there is more.
            val rows =
                handlers.all
                    .flatMap { handler ->
                        handler
                            .changesSince(this, householdId, after, lastSeq, request.limit + 1, includeTombstones)
                            .also { afterHandlerQuery?.invoke(handler.type) }
                    }.sortedBy { it.seq }
            val hasMore = rows.size > request.limit
            val page = rows.take(request.limit)
            val cursor = if (hasMore) page.last().seq else lastSeq
            val visible = page.filter { row -> handlers[row.type]!!.canSee(viewer, row) }
            PullResponse(false, accessVersion, epoch, visible.map(ChangeRow::toChange), cursor, hasMore)
        }
    }
}
