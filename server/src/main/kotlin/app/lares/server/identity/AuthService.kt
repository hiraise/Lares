package app.lares.server.identity

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.auth.AuthResult
import app.lares.contract.api.auth.DeviceInfo
import app.lares.contract.api.auth.LoginRequest
import app.lares.contract.api.auth.RefreshRequest
import app.lares.contract.validation.ValidationErrors
import app.lares.contract.validation.Validators
import app.lares.server.household.MemberRepository
import app.lares.server.identity.crypto.PasswordHasher
import app.lares.server.identity.crypto.TokenGenerator
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.http.ApiException
import app.lares.server.platform.http.validate
import app.lares.server.platform.nowForDb
import app.lares.server.platform.toContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Login, token refresh, logout and per-request session checks (FND-010, FND-011; architecture/auth.md). */
class AuthService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val hasher: PasswordHasher,
    private val tokens: TokenGenerator,
    private val loginLimiter: FailedAttemptLimiter,
    private val meQuery: MeQuery,
) {
    /** A token pair in plain text; only returned to the client, never stored. */
    class IssuedSession(
        val sessionId: UUID,
        val accessToken: String,
        val refreshToken: String,
        val hashes: TokenHashes,
    )

    suspend fun login(
        request: LoginRequest,
        ip: String,
    ): AuthResult {
        validate {
            add("login", if (request.login.isBlank()) FieldErrorCode.REQUIRED else null)
            add("password", if (request.password.isEmpty()) FieldErrorCode.REQUIRED else null)
            addDevice(request.device)
        }
        val login = Validators.normalizeLogin(request.login)
        val ipKey = "login-ip:$ip"
        val loginKey = "login:$login"
        loginLimiter.ensureAllowed(ipKey)
        loginLimiter.ensureAllowed(loginKey)

        val found =
            db.dbQuery {
                UserRepository.findActiveByLogin(login)?.let { user ->
                    MemberRepository.findActiveByUser(user.id)?.let { member -> user to member }
                }
            }
        val passwordHash = found?.first?.passwordHash
        val ok =
            withContext(Dispatchers.Default) {
                if (passwordHash != null) {
                    hasher.verify(request.password, passwordHash)
                } else {
                    // Same work for unknown logins, so timing does not reveal which logins exist.
                    hasher.verifyDummy(request.password)
                    false
                }
            }
        if (!ok || found == null) {
            loginLimiter.recordFailure(ipKey)
            loginLimiter.recordFailure(loginKey)
            throw ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid login or password")
        }
        loginLimiter.reset(loginKey)

        val (user, member) = found
        return db.dbQuery {
            val issued = createSession(user.id, member.id, member.householdId, request.device, clock.nowForDb())
            authResult(issued, user.id, member.id, member.householdId)
        }
    }

    /**
     * Rotates the token pair. A previous refresh token presented again within [REFRESH_GRACE] of the rotation and before
     * the new access token was used means the response was lost: rotate again. Any other reuse revokes the session.
     */
    suspend fun refresh(request: RefreshRequest): AuthResult {
        val presented = TokenGenerator.sha256(request.refreshToken)
        val now = clock.nowForDb()
        val outcome =
            db.dbQuery {
                val current = SessionRepository.findByRefreshHashForUpdate(presented)
                if (current != null) {
                    if (current.revokedAt != null) return@dbQuery RefreshOutcome.Revoked
                    if (current.refreshExpiresAt <= now) {
                        SessionRepository.revoke(current.id, now)
                        return@dbQuery RefreshOutcome.Revoked
                    }
                    val issued = rotate(current, presented, rotatedAt = now, now = now)
                    return@dbQuery RefreshOutcome.Issued(authResult(issued, current.userId, current.memberId, current.householdId))
                }
                val previous =
                    SessionRepository.findByPreviousRefreshHashForUpdate(presented)
                        ?: return@dbQuery RefreshOutcome.Unknown
                if (previous.revokedAt != null) return@dbQuery RefreshOutcome.Revoked
                val rotatedAt = previous.rotatedAt
                val withinGrace = rotatedAt != null && now < rotatedAt + REFRESH_GRACE.toJavaDuration()
                if (withinGrace && !previous.accessUsedSinceRotation && previous.refreshExpiresAt > now) {
                    // The grace window keeps counting from the first rotation.
                    val issued = rotate(previous, presented, rotatedAt = rotatedAt, now = now)
                    RefreshOutcome.Issued(authResult(issued, previous.userId, previous.memberId, previous.householdId))
                } else {
                    // Token theft suspected (architecture/auth.md): end the session for everyone holding it.
                    SessionRepository.revoke(previous.id, now)
                    RefreshOutcome.Revoked
                }
            }
        return when (outcome) {
            is RefreshOutcome.Issued -> outcome.result
            RefreshOutcome.Revoked -> throw ApiException(ErrorCode.SESSION_REVOKED, "Session revoked")
            RefreshOutcome.Unknown -> throw ApiException(ErrorCode.UNAUTHENTICATED, "Unknown refresh token")
        }
    }

    suspend fun logout(principal: MemberPrincipal) {
        db.dbQuery { SessionRepository.revoke(principal.sessionId, clock.nowForDb()) }
    }

    /** Checks an access token on every authenticated request; revocation therefore takes effect immediately. */
    suspend fun authenticate(accessToken: String): MemberPrincipal {
        val hash = TokenGenerator.sha256(accessToken)
        val now = clock.nowForDb()
        val session =
            db.dbQuery {
                val session = SessionRepository.findByAccessHash(hash) ?: return@dbQuery null
                if (session.revokedAt == null && session.accessExpiresAt > now) {
                    val seenStale = session.lastSeenAt <= now - LAST_SEEN_RESOLUTION.toJavaDuration()
                    SessionRepository.touch(
                        id = session.id,
                        lastSeenAt = if (seenStale) now else null,
                        markAccessUsed = !session.accessUsedSinceRotation,
                    )
                }
                session
            } ?: throw ApiException(ErrorCode.UNAUTHENTICATED, "Unknown access token")
        if (session.revokedAt != null) throw ApiException(ErrorCode.SESSION_REVOKED, "Session revoked")
        if (session.accessExpiresAt <= now) throw ApiException(ErrorCode.SESSION_EXPIRED, "Access token expired")
        return MemberPrincipal(session.userId, session.memberId, session.householdId, session.id)
    }

    /** Creates a session with a fresh token pair. Runs inside `dbQuery`; used by login, setup and (M0-04) invites. */
    fun createSession(
        userId: UUID,
        memberId: UUID,
        householdId: UUID,
        device: DeviceInfo,
        now: Instant,
    ): IssuedSession {
        val issued = issue(UUID.randomUUID(), now)
        SessionRepository.create(
            id = issued.sessionId,
            userId = userId,
            memberId = memberId,
            householdId = householdId,
            deviceName = device.name.trim(),
            platform = device.platform.trim(),
            tokens = issued.hashes,
            now = now,
        )
        return issued
    }

    /** Builds the response for an issued session. Runs inside `dbQuery`. */
    fun authResult(
        issued: IssuedSession,
        userId: UUID,
        memberId: UUID,
        householdId: UUID,
    ): AuthResult =
        AuthResult(
            accessToken = issued.accessToken,
            accessExpiresAt = issued.hashes.accessExpiresAt.toContract(),
            refreshToken = issued.refreshToken,
            refreshExpiresAt = issued.hashes.refreshExpiresAt.toContract(),
            sessionId = issued.sessionId.toString(),
            me = meQuery.load(userId, memberId, householdId),
        )

    private fun rotate(
        session: SessionRow,
        presentedHash: ByteArray,
        rotatedAt: Instant,
        now: Instant,
    ): IssuedSession {
        val issued = issue(session.id, now)
        SessionRepository.rotate(session.id, issued.hashes, previousRefreshHash = presentedHash, rotatedAt = rotatedAt)
        return issued
    }

    private fun issue(
        sessionId: UUID,
        now: Instant,
    ): IssuedSession {
        val access = tokens.newToken()
        val refresh = tokens.newToken()
        return IssuedSession(
            sessionId = sessionId,
            accessToken = access,
            refreshToken = refresh,
            hashes =
                TokenHashes(
                    accessHash = TokenGenerator.sha256(access),
                    accessExpiresAt = now + ACCESS_TTL.toJavaDuration(),
                    refreshHash = TokenGenerator.sha256(refresh),
                    refreshExpiresAt = now + REFRESH_TTL.toJavaDuration(),
                ),
        )
    }

    private sealed interface RefreshOutcome {
        class Issued(
            val result: AuthResult,
        ) : RefreshOutcome

        data object Revoked : RefreshOutcome

        data object Unknown : RefreshOutcome
    }

    companion object {
        val ACCESS_TTL: Duration = 1.hours
        val REFRESH_TTL: Duration = 90.days
        val REFRESH_GRACE: Duration = 60.seconds
        val LAST_SEEN_RESOLUTION: Duration = 1.minutes
    }
}

/** Device fields shared by setup, login and invite acceptance. */
fun ValidationErrors.addDevice(device: DeviceInfo) {
    add("device.name", Validators.deviceName(device.name))
    add("device.platform", Validators.platform(device.platform))
}
