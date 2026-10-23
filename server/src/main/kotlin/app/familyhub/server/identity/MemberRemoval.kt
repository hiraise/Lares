package app.familyhub.server.identity

import app.familyhub.contract.api.ErrorCode
import app.familyhub.server.household.AccessVersionService
import app.familyhub.server.household.GroupRepository
import app.familyhub.server.household.MemberRepository
import app.familyhub.server.household.MemberRow
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.platform.sync.recordChange
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant

/**
 * Account deletion (FND-013) and member removal by an administrator (FND-025) have the same effect: the user is marked
 * deleted and all their sessions are revoked, the member becomes a tombstone and leaves all groups; the person stays
 * (history keeps the name). The last administrator cannot be removed (`LAST_ADMIN`).
 * Runs inside `dbQuery` after `lockHousehold`.
 */
internal fun JdbcTransaction.removeMember(
    member: MemberRow,
    now: Instant,
) {
    val householdId = member.householdId
    val otherAdmins = GroupRepository.countAdministrators(householdId, exceptMemberId = member.id)
    val isAdmin = GroupRepository.countAdministrators(householdId) > otherAdmins
    if (isAdmin && otherAdmins == 0L) {
        throw ApiException(ErrorCode.LAST_ADMIN, "The last administrator cannot be removed")
    }
    member.userId?.let { userId ->
        UserRepository.markDeleted(userId, now)
        SessionRepository.revokeAllOfUser(userId, now)
    }
    MemberRepository.tombstone(householdId, member.id, recordChange(householdId), now)
    val groupIds = GroupRepository.removeMemberFromAll(householdId, member.id)
    groupIds.forEach { groupId -> GroupRepository.stampSeq(householdId, groupId, recordChange(householdId), now) }
    AccessVersionService.bump(householdId, listOf(member.id))
}
