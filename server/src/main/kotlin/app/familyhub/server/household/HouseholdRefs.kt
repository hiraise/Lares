package app.familyhub.server.household

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.platform.notFound
import app.familyhub.server.platform.parseIdOrNotFound
import app.familyhub.server.platform.sync.recordChange
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.time.Instant
import java.util.UUID

// Resolving ids from requests and changing group memberships. Every referenced id must belong to the caller's household;
// `group_membership` has no household_id, so these checks are explicit. Violations answer NOT_FOUND (architecture/api.md).
// All functions run inside `dbQuery`, after `lockHousehold`.

object HouseholdRefs {
    fun requireMember(
        householdId: UUID,
        id: String?,
    ): MemberRow {
        val memberId = parseIdOrNotFound(id, "Member")
        return MemberRepository.get(householdId, memberId)?.takeUnless { it.deleted } ?: throw notFound("Member")
    }

    fun requireGroup(
        householdId: UUID,
        id: String?,
    ): GroupRow {
        val groupId = parseIdOrNotFound(id, "Group")
        return GroupRepository.get(householdId, groupId)?.takeUnless { it.deleted } ?: throw notFound("Group")
    }

    /** Non-deleted members of the household; any other id fails the whole request. */
    fun requireMembers(
        householdId: UUID,
        ids: Collection<String>,
    ): Set<UUID> {
        val parsed = ids.mapTo(mutableSetOf()) { parseIdOrNotFound(it, "Member") }
        if (MemberRepository.activeIds(householdId, parsed).size != parsed.size) throw notFound("Member")
        return parsed
    }

    /** Non-deleted groups of the household; any other id fails the whole request. */
    fun requireGroups(
        householdId: UUID,
        ids: Collection<String>,
    ): Set<UUID> {
        val parsed = ids.mapTo(mutableSetOf()) { parseIdOrNotFound(it, "Group") }
        if (GroupRepository.activeIds(householdId, parsed).size != parsed.size) throw notFound("Group")
        return parsed
    }

    /**
     * A relative that may become a member's profile (FND-032): a person of this household (else `NOT_FOUND`)
     * that is not deleted and not yet linked to a member (else `VALIDATION_FAILED` on `personId`).
     */
    fun requireLinkablePerson(
        householdId: UUID,
        id: String,
    ): PersonRow {
        val person = PersonRepository.get(householdId, parseIdOrNotFound(id, "Person")) ?: throw notFound("Person")
        val problem =
            when {
                person.deleted -> FieldErrorCode.INVALID_FORMAT
                MemberRepository.isPersonLinked(householdId, person.id) -> FieldErrorCode.TAKEN
                else -> null
            }
        if (problem != null) {
            throw ApiException(ErrorCode.VALIDATION_FAILED, "Person cannot be linked", mapOf("personId" to problem))
        }
        return person
    }
}

/**
 * Sets the complete group set of a member (FND-022). Each changed group gets a new seq (memberships are part of the group);
 * the member's access version is bumped. Leaving no administrator answers `LAST_ADMIN`.
 */
fun JdbcTransaction.setGroupsOfMember(
    householdId: UUID,
    memberId: UUID,
    groupIds: Set<UUID>,
    now: Instant,
) {
    val current = GroupRepository.groupIdsOfMember(householdId, memberId).toSet()
    val removed = current - groupIds
    val added = groupIds - current
    if (removed.isEmpty() && added.isEmpty()) return
    val administratorsId = GroupRepository.administratorsId(householdId)
    if (administratorsId in removed && GroupRepository.countAdministrators(householdId, exceptMemberId = memberId) == 0L) {
        throw ApiException(ErrorCode.LAST_ADMIN, "Administrators must keep at least one member")
    }
    removed.forEach { GroupRepository.removeMembers(it, listOf(memberId)) }
    added.forEach { GroupRepository.addMembers(it, listOf(memberId)) }
    (removed + added).forEach { GroupRepository.stampSeq(householdId, it, recordChange(householdId), now) }
    AccessVersionService.bump(householdId, listOf(memberId))
}

/**
 * Replaces the members of [group] and returns the members whose access changed. The caller stamps the group's seq.
 * Administrators must keep at least one member (`LAST_ADMIN`); [memberIds] are already checked to be active.
 */
fun setMembersOfGroup(
    group: GroupRow,
    memberIds: Set<UUID>,
): Set<UUID> {
    if (group.builtIn == BuiltInGroup.ADMINISTRATORS && memberIds.isEmpty()) {
        throw ApiException(ErrorCode.LAST_ADMIN, "Administrators must keep at least one member")
    }
    val current = GroupRepository.memberIdsOf(group.id)
    val removed = current - memberIds
    val added = memberIds - current
    GroupRepository.removeMembers(group.id, removed)
    GroupRepository.addMembers(group.id, added)
    return removed + added
}
