package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.server.household.MemberRepository
import app.lares.server.platform.http.ApiException
import app.lares.server.sync.MutationContext
import app.lares.server.sync.parseAll
import java.util.UUID

// Server-only checks of the location handlers that need the database. Run inside `dbQuery`.

/** Ids not yet stored must be live places of the household (`ENTITY_NOT_FOUND`); stored ones stay valid. */
internal fun requireAddedPlaces(
    ctx: MutationContext,
    added: Set<String>,
) {
    if (added.isEmpty()) return
    val ids = parseAll(added) ?: throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Place not found")
    if (PlaceRepository.countLive(ctx.householdId, ids) != ids.size.toLong()) {
        throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Place not found")
    }
}

/** [memberId] must be a live member of the household, else `ENTITY_NOT_FOUND`. */
internal fun requireLiveMember(
    ctx: MutationContext,
    memberId: String,
) {
    val id = runCatching { UUID.fromString(memberId) }.getOrNull()
    if (id == null || MemberRepository.activeIds(ctx.householdId, setOf(id)).isEmpty()) {
        throw ApiException(ErrorCode.ENTITY_NOT_FOUND, "Member not found")
    }
}
