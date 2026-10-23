package app.familyhub.server.identity

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.entities.PersonData
import app.familyhub.server.household.GroupRepository
import app.familyhub.server.household.MemberRepository
import app.familyhub.server.household.PersonRepository
import app.familyhub.server.household.PersonRow
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.platform.newEntityId
import app.familyhub.server.platform.sync.recordChange
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant
import java.util.UUID

internal class NewMemberIds(
    val userId: UUID,
    val memberId: UUID,
    val personId: UUID,
)

/**
 * Creates a user account (login and password hash may be null), a member and its group memberships; used by direct member
 * creation (FND-022) and invite acceptance (FND-024). Without [linkedPerson] a new person named [displayName] is created;
 * otherwise that relative becomes the member's profile and takes [displayName] as its name (FND-032).
 * [groupIds] must already be checked to be active groups of the household. Runs inside `dbQuery` after `lockHousehold`.
 */
internal fun JdbcTransaction.createMemberWithAccount(
    householdId: UUID,
    login: String?,
    passwordHash: String?,
    displayName: String,
    linkedPerson: PersonRow?,
    groupIds: Set<UUID>,
    now: Instant,
): NewMemberIds {
    if (login != null && UserRepository.loginExists(login)) {
        throw ApiException(ErrorCode.LOGIN_TAKEN, "Login is already taken", mapOf("login" to FieldErrorCode.TAKEN))
    }
    val userId = newEntityId()
    UserRepository.create(userId, login, passwordHash, now)
    val name = displayName.trim()
    val personId =
        if (linkedPerson == null) {
            newEntityId().also {
                val data = PersonData(name = name, color = PersonRepository.leastUsedColor(householdId))
                PersonRepository.create(householdId, it, data, recordChange(householdId), now)
            }
        } else {
            if (linkedPerson.data.name != name) PersonRepository.rename(householdId, linkedPerson.id, name, recordChange(householdId), now)
            linkedPerson.id
        }
    val memberId = newEntityId()
    MemberRepository.create(householdId, memberId, userId, personId, recordChange(householdId), now)
    groupIds.forEach { groupId ->
        GroupRepository.addMembers(groupId, listOf(memberId))
        GroupRepository.stampSeq(householdId, groupId, recordChange(householdId), now)
    }
    return NewMemberIds(userId, memberId, personId)
}
