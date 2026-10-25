package app.lares.server.identity

import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.admin.CreateMemberRequest
import app.lares.contract.api.admin.MemberCreated
import app.lares.contract.api.admin.ResetPasswordRequest
import app.lares.contract.api.admin.SetGroupsRequest
import app.lares.contract.validation.Validators
import app.lares.server.household.HouseholdRefs
import app.lares.server.household.MemberRepository
import app.lares.server.household.setGroupsOfMember
import app.lares.server.identity.crypto.PasswordHasher
import app.lares.server.location.LocationSessions
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.http.validate
import app.lares.server.platform.notFound
import app.lares.server.platform.nowForDb
import app.lares.server.platform.sync.lockHousehold
import app.lares.server.platform.sync.recordChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Clock

/** Members managed by an administrator (FND-012, FND-022, FND-025). The caller has `household.manage` (checked by the route). */
class MemberAdminService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val hasher: PasswordHasher,
    private val locationSessions: LocationSessions,
) {
    /** A member without a login logs in by QR code only (FND-010, FND-014). */
    suspend fun create(
        householdId: UUID,
        request: CreateMemberRequest,
    ): MemberCreated {
        validate {
            add("displayName", Validators.personName(request.displayName))
            request.login?.let { add("login", Validators.login(it)) }
            request.password?.let { add("password", Validators.password(it)) }
            if (request.password != null && request.login == null) add("login", FieldErrorCode.REQUIRED)
        }
        val login = request.login?.let(Validators::normalizeLogin)
        val passwordHash = request.password?.let { withContext(Dispatchers.Default) { hasher.hash(it) } }
        return db.dbQuery {
            lockHousehold(householdId)
            val groupIds = HouseholdRefs.requireGroups(householdId, request.groupIds)
            val person = request.personId?.let { HouseholdRefs.requireLinkablePerson(householdId, it) }
            val ids = createMemberWithAccount(householdId, login, passwordHash, request.displayName, person, groupIds, clock.nowForDb())
            MemberCreated(memberId = ids.memberId.toString(), personId = ids.personId.toString())
        }
    }

    /** Same effect as account deletion (FND-013); the last administrator cannot be removed. */
    suspend fun remove(
        householdId: UUID,
        memberId: String,
    ) {
        db.dbQuery {
            lockHousehold(householdId)
            removeMember(HouseholdRefs.requireMember(householdId, memberId), clock.nowForDb())
        }
    }

    /** Sets a new password and revokes all sessions of the member (architecture/auth.md). */
    suspend fun resetPassword(
        householdId: UUID,
        memberId: String,
        request: ResetPasswordRequest,
    ) {
        validate { add("newPassword", Validators.password(request.newPassword)) }
        // Look the member up first, so unknown ids do not cost an Argon2 hash.
        db.dbQuery { HouseholdRefs.requireMember(householdId, memberId) }
        val hash = withContext(Dispatchers.Default) { hasher.hash(request.newPassword) }
        db.dbQuery {
            lockHousehold(householdId)
            val member = HouseholdRefs.requireMember(householdId, memberId)
            val userId = member.userId ?: throw notFound("Member")
            val now = clock.nowForDb()
            UserRepository.setPasswordHash(userId, hash)
            // Before the revoke: only live sessions are told.
            locationSessions.onUserSessionsEnded(this, userId)
            SessionRepository.revokeAllOfUser(userId, now)
            // `hasPassword` is part of the member's sync data.
            MemberRepository.stampSeq(householdId, member.id, recordChange(householdId), now)
        }
    }

    suspend fun setGroups(
        householdId: UUID,
        memberId: String,
        request: SetGroupsRequest,
    ) {
        db.dbQuery {
            lockHousehold(householdId)
            val member = HouseholdRefs.requireMember(householdId, memberId)
            val groupIds = HouseholdRefs.requireGroups(householdId, request.groupIds)
            setGroupsOfMember(householdId, member.id, groupIds, clock.nowForDb())
        }
    }
}
