package app.familyhub.server.identity

import app.familyhub.contract.DeepLinks
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.admin.CreateLoginCodeRequest
import app.familyhub.contract.api.admin.LoginCodeCreated
import app.familyhub.contract.api.admin.LoginWithCodeRequest
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.permissions.Permission
import app.familyhub.server.household.HouseholdRefs
import app.familyhub.server.household.MemberRepository
import app.familyhub.server.household.PermissionService
import app.familyhub.server.identity.crypto.CodeGenerator
import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.platform.http.validate
import app.familyhub.server.platform.newEntityId
import app.familyhub.server.platform.notFound
import app.familyhub.server.platform.nowForDb
import app.familyhub.server.platform.toContract
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * One-time QR login codes (FND-014; architecture/auth.md § QR login): 8 characters, valid 5 minutes, single use, stored hashed.
 * Any member creates one for themselves; a code for another member needs `household.manage`.
 */
class LoginCodeService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val codes: CodeGenerator,
    private val auth: AuthService,
    private val permissions: PermissionService,
    private val limiter: FailedAttemptLimiter,
    private val publicUrl: String,
) {
    suspend fun create(
        principal: MemberPrincipal,
        request: CreateLoginCodeRequest,
    ): LoginCodeCreated {
        val requested = request.memberId
        val forSelf = requested == null || runCatching { UUID.fromString(requested) }.getOrNull() == principal.memberId
        // The permission is checked before the id is looked up: without it, every other id is simply forbidden.
        if (!forSelf && !permissions.effective(principal.householdId, principal.memberId).has(Permission.HOUSEHOLD_MANAGE)) {
            throw ApiException(ErrorCode.FORBIDDEN, "Missing permission ${Permission.HOUSEHOLD_MANAGE.key}")
        }
        return db.dbQuery {
            val member = HouseholdRefs.requireMember(principal.householdId, requested ?: principal.memberId.toString())
            if (member.userId == null) throw notFound("Member")
            val code = codes.generate(CODE_LENGTH)
            val now = clock.nowForDb()
            val expiresAt = now + TTL.toJavaDuration()
            LoginCodeRepository.create(
                newEntityId(),
                principal.householdId,
                member.id,
                CodeGenerator.hash(code),
                principal.memberId,
                now,
                expiresAt,
            )
            LoginCodeCreated(code = code, payload = DeepLinks.login(publicUrl, code), expiresAt = expiresAt.toContract())
        }
    }

    /** Exchanges a code for a new session of its member. Wrong codes count per IP, like failed logins. */
    suspend fun exchange(
        request: LoginWithCodeRequest,
        ip: String,
    ): AuthResult {
        validate { addDevice(request.device) }
        val key = "login-code-ip:$ip"
        limiter.ensureAllowed(key)
        val result =
            db.dbQuery {
                val now = clock.nowForDb()
                val code =
                    LoginCodeRepository
                        .findByCodeHashForUpdate(CodeGenerator.hash(request.code))
                        ?.takeIf { it.usedAt == null && it.expiresAt > now }
                        ?: return@dbQuery null
                val member = MemberRepository.get(code.householdId, code.memberId)?.takeUnless { it.deleted } ?: return@dbQuery null
                val userId = member.userId ?: return@dbQuery null
                LoginCodeRepository.markUsed(code.id, now)
                val issued = auth.createSession(userId, member.id, member.householdId, request.device, now)
                auth.authResult(issued, userId, member.id, member.householdId)
            }
        if (result == null) {
            limiter.recordFailure(key)
            throw ApiException(ErrorCode.LOGIN_CODE_INVALID, "Login code unknown, used or expired")
        }
        return result
    }

    companion object {
        const val CODE_LENGTH = 8
        val TTL: Duration = 5.minutes
    }
}
