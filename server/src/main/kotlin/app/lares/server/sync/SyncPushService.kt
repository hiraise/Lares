package app.lares.server.sync

import app.lares.contract.api.ApiError
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.Mutation
import app.lares.contract.sync.MutationResult
import app.lares.contract.sync.MutationStatus
import app.lares.contract.sync.PushRequest
import app.lares.contract.sync.PushResponse
import app.lares.contract.sync.SyncLimits
import app.lares.server.household.MemberRepository
import app.lares.server.household.PermissionService
import app.lares.server.identity.MemberPrincipal
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.nowForDb
import app.lares.server.platform.sync.lockHousehold
import app.lares.server.platform.toDb
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Push (architecture/sync.md § Push): each mutation in its own transaction, every outcome logged. */
class SyncPushService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val handlers: SyncHandlers,
    private val permissions: PermissionService,
) {
    suspend fun push(
        principal: MemberPrincipal,
        request: PushRequest,
    ): PushResponse {
        if (request.mutations.size > SyncLimits.MAX_MUTATIONS) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "At most ${SyncLimits.MAX_MUTATIONS} mutations per request")
        }
        return PushResponse(request.mutations.map { process(principal, it) })
    }

    private suspend fun process(
        principal: MemberPrincipal,
        mutation: Mutation,
    ): MutationResult {
        val mutationId =
            runCatching { UUID.fromString(mutation.mutationId) }.getOrNull()
                // Cannot be logged without a valid id; the client will get the same answer on retry anyway.
                ?: return rejected(
                    mutation,
                    ApiException(ErrorCode.VALIDATION_FAILED, "Invalid mutationId", mapOf("mutationId" to FieldErrorCode.INVALID_FORMAT)),
                )
        val householdId = principal.householdId
        return db.dbQuery {
            // 1. Serialize all mutations of the household; 2. idempotency.
            lockHousehold(householdId)
            MutationLogRepository.find(householdId, mutationId)?.let { return@dbQuery it.copy(mutationId = mutation.mutationId) }

            val now = clock.now()
            val result =
                try {
                    // 3. Expiry.
                    if (mutation.createdAt < now - SyncLimits.MUTATION_TTL_DAYS.days) {
                        throw ApiException(ErrorCode.MUTATION_EXPIRED, "Mutation is older than ${SyncLimits.MUTATION_TTL_DAYS} days")
                    }
                    val handler =
                        handlers[mutation.type]
                            ?: throw ApiException(
                                ErrorCode.VALIDATION_FAILED,
                                "Unknown type",
                                mapOf("type" to FieldErrorCode.INVALID_FORMAT),
                            )
                    val member = MemberRepository.get(householdId, principal.memberId) ?: error("Member ${principal.memberId} not found")
                    val ctx =
                        MutationContext(
                            householdId = householdId,
                            viewer =
                                Viewer(
                                    memberId = member.id.toString(),
                                    personId = member.personId.toString(),
                                    permissions = permissions.effectiveInTransaction(householdId, member.id),
                                ),
                            now = now.toDb(),
                            currentYear = now.toDb().atZone(ZoneOffset.UTC).year,
                        )
                    // 4–7. Load, tombstone rules, permissions, validation, write.
                    MutationResult(mutation.mutationId, MutationStatus.APPLIED, seq = handler.apply(this, ctx, mutation))
                } catch (e: ApiException) {
                    rejected(mutation, e)
                }
            MutationLogRepository.record(householdId, mutationId, principal.memberId, result, clock.nowForDb())
            result
        }
    }

    private fun rejected(
        mutation: Mutation,
        e: ApiException,
    ) = MutationResult(
        mutation.mutationId,
        MutationStatus.REJECTED,
        error = ApiError(e.code, e.message ?: e.code.name, e.fields),
    )
}
