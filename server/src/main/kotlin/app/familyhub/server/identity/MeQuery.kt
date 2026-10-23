package app.familyhub.server.identity

import app.familyhub.contract.api.auth.Me
import app.familyhub.server.household.HouseholdRepository
import app.familyhub.server.household.MemberRepository
import app.familyhub.server.household.PermissionService
import app.familyhub.server.household.PersonRepository
import java.util.UUID

/** Builds the `Me` summary (FND-020). Runs inside `dbQuery`. */
class MeQuery(
    private val permissions: PermissionService,
) {
    fun load(
        userId: UUID,
        memberId: UUID,
        householdId: UUID,
    ): Me {
        val user = UserRepository.get(userId) ?: error("User $userId not found")
        val member = MemberRepository.get(householdId, memberId) ?: error("Member $memberId not found")
        val person = PersonRepository.get(householdId, member.personId) ?: error("Person ${member.personId} not found")
        val household = HouseholdRepository.get(householdId) ?: error("Household $householdId not found")
        val effective = permissions.effectiveInTransaction(householdId, memberId)
        return Me(
            userId = user.id.toString(),
            memberId = member.id.toString(),
            householdId = householdId.toString(),
            householdName = household.data.name,
            personId = person.id.toString(),
            displayName = person.data.name,
            login = user.login?.takeUnless { user.deleted },
            hasPassword = user.passwordHash != null,
            isAdmin = effective.isAdmin,
            permissions = effective.permissions,
        )
    }
}
