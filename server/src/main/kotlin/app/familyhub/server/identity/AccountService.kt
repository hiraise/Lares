package app.familyhub.server.identity

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.api.auth.ChangePasswordRequest
import app.familyhub.contract.api.auth.DeleteAccountRequest
import app.familyhub.contract.api.auth.Me
import app.familyhub.contract.api.auth.SessionInfo
import app.familyhub.contract.validation.Validators
import app.familyhub.server.household.GroupRepository
import app.familyhub.server.household.MemberRepository
import app.familyhub.server.identity.crypto.PasswordHasher
import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.platform.http.validate
import app.familyhub.server.platform.nowForDb
import app.familyhub.server.platform.sync.lockHousehold
import app.familyhub.server.platform.sync.recordChange
import app.familyhub.server.platform.toContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Clock

/** The caller's own account: profile summary, password, sessions, deletion (FND-010, FND-011, FND-013, FND-020). */
class AccountService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val hasher: PasswordHasher,
    /** Guards password checks of logged-in users against guessing with a stolen access token. */
    private val limiter: FailedAttemptLimiter,
    private val meQuery: MeQuery,
) {
    suspend fun me(principal: MemberPrincipal): Me =
        db.dbQuery { meQuery.load(principal.userId, principal.memberId, principal.householdId) }

    /** Other sessions stay valid (architecture/auth.md § Account and member management). */
    suspend fun changePassword(
        principal: MemberPrincipal,
        request: ChangePasswordRequest,
    ) {
        validate { add("newPassword", Validators.password(request.newPassword)) }
        checkCurrentPassword(principal, request.currentPassword, field = "currentPassword")
        val newHash = withContext(Dispatchers.Default) { hasher.hash(request.newPassword) }
        db.dbQuery { UserRepository.setPasswordHash(principal.userId, newHash) }
    }

    suspend fun sessions(principal: MemberPrincipal): List<SessionInfo> =
        db.dbQuery { SessionRepository.listActive(principal.userId, clock.nowForDb()) }.map { session ->
            SessionInfo(
                id = session.id.toString(),
                deviceName = session.deviceName,
                platform = session.platform,
                createdAt = session.createdAt.toContract(),
                lastSeenAt = session.lastSeenAt.toContract(),
                current = session.id == principal.sessionId,
            )
        }

    /** Revokes one of the caller's sessions (possibly the current one). Other users' sessions are `NOT_FOUND`. */
    suspend fun revokeSession(
        principal: MemberPrincipal,
        sessionId: String,
    ) {
        val id = runCatching { UUID.fromString(sessionId) }.getOrNull() ?: throw notFound()
        db.dbQuery {
            val session = SessionRepository.get(id)
            if (session == null || session.userId != principal.userId) throw notFound()
            SessionRepository.revoke(id, clock.nowForDb())
        }
    }

    /**
     * FND-013: marks the user deleted, revokes all their sessions and turns the member into a tombstone.
     * The person stays (history keeps the name). The last administrator cannot leave.
     */
    suspend fun deleteAccount(
        principal: MemberPrincipal,
        request: DeleteAccountRequest,
    ) {
        checkCurrentPassword(principal, request.password, field = "password")
        val householdId = principal.householdId
        db.dbQuery {
            // Serializes with other writers of the household before the last-admin check.
            lockHousehold(householdId)
            val member = MemberRepository.get(householdId, principal.memberId)
            check(member != null && !member.deleted) { "Member ${principal.memberId} not active" }
            val otherAdmins = GroupRepository.countAdministrators(householdId, exceptMemberId = member.id)
            val isAdmin = GroupRepository.countAdministrators(householdId) > otherAdmins
            if (isAdmin && otherAdmins == 0L) {
                throw ApiException(ErrorCode.LAST_ADMIN, "The last administrator cannot delete their account")
            }
            val now = clock.nowForDb()
            UserRepository.markDeleted(principal.userId, now)
            SessionRepository.revokeAllOfUser(principal.userId, now)
            MemberRepository.tombstone(householdId, member.id, recordChange(householdId), now)
            val groupIds = GroupRepository.removeMemberFromAll(householdId, member.id)
            groupIds.forEach { groupId -> GroupRepository.stampSeq(householdId, groupId, recordChange(householdId), now) }
            // What the member may see changed; M0-04 extends this to every affected member.
            MemberRepository.bumpAccessVersion(householdId, listOf(member.id))
        }
    }

    /** Requires and checks the current password if the user has one. */
    private suspend fun checkCurrentPassword(
        principal: MemberPrincipal,
        password: String?,
        field: String,
    ) {
        val user = db.dbQuery { UserRepository.get(principal.userId) } ?: error("User ${principal.userId} not found")
        val hash = user.passwordHash ?: return
        if (password.isNullOrEmpty()) validate { add(field, FieldErrorCode.REQUIRED) }
        val key = "account:${principal.userId}"
        limiter.ensureAllowed(key)
        val ok = withContext(Dispatchers.Default) { hasher.verify(password!!, hash) }
        if (!ok) {
            limiter.recordFailure(key)
            throw ApiException(ErrorCode.INVALID_CREDENTIALS, "Wrong password")
        }
        limiter.reset(key)
    }

    private fun notFound() = ApiException(ErrorCode.NOT_FOUND, "Session not found")
}
