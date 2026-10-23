package app.familyhub.server.household

import java.util.UUID

/**
 * The single place that bumps `member.access_version` (architecture/sync.md): whenever a member's group memberships change,
 * the permissions of one of their groups change, or the member is removed. A changed version makes the member's next pull
 * a full resync. Runs inside `dbQuery`.
 */
object AccessVersionService {
    fun bump(
        householdId: UUID,
        memberIds: Collection<UUID>,
    ) {
        MemberRepository.bumpAccessVersion(householdId, memberIds.toSet())
    }
}
