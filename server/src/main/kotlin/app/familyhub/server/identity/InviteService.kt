package app.familyhub.server.identity

import app.familyhub.contract.DeepLinks
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FieldErrorCode
import app.familyhub.contract.api.admin.AcceptInviteRequest
import app.familyhub.contract.api.admin.CreateInviteRequest
import app.familyhub.contract.api.admin.InviteCreated
import app.familyhub.contract.api.admin.InviteInfo
import app.familyhub.contract.api.admin.InvitePreview
import app.familyhub.contract.api.admin.InvitePreviewRequest
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.validation.Validators
import app.familyhub.server.household.GroupRepository
import app.familyhub.server.household.HouseholdRefs
import app.familyhub.server.household.HouseholdRepository
import app.familyhub.server.household.MemberRepository
import app.familyhub.server.household.PersonRepository
import app.familyhub.server.identity.crypto.CodeGenerator
import app.familyhub.server.identity.crypto.PasswordHasher
import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.platform.http.validate
import app.familyhub.server.platform.newEntityId
import app.familyhub.server.platform.notFound
import app.familyhub.server.platform.nowForDb
import app.familyhub.server.platform.parseIdOrNotFound
import app.familyhub.server.platform.sync.lockHousehold
import app.familyhub.server.platform.toContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

/**
 * Invites to join the household (FND-024; architecture/auth.md § Invites): 12-character single-use codes, stored hashed.
 * Creating, listing and revoking needs `household.manage` (checked by the route); preview and accept are public and
 * count wrong codes per IP in [limiter].
 */
class InviteService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val hasher: PasswordHasher,
    private val codes: CodeGenerator,
    private val auth: AuthService,
    private val limiter: FailedAttemptLimiter,
    private val publicUrl: String,
) {
    suspend fun create(
        principal: MemberPrincipal,
        request: CreateInviteRequest,
    ): InviteCreated {
        validate {
            if (request.expiresInHours !in 1..CreateInviteRequest.MAX_INVITE_HOURS) add("expiresInHours", FieldErrorCode.OUT_OF_RANGE)
        }
        val householdId = principal.householdId
        return db.dbQuery {
            lockHousehold(householdId)
            val groupIds = HouseholdRefs.requireGroups(householdId, request.groupIds)
            val person = request.personId?.let { HouseholdRefs.requireLinkablePerson(householdId, it) }
            val code = codes.generate(CODE_LENGTH)
            val id = newEntityId()
            val now = clock.nowForDb()
            val expiresAt = now + request.expiresInHours.hours.toJavaDuration()
            InviteRepository.create(id, householdId, CodeGenerator.hash(code), groupIds, person?.id, principal.memberId, now, expiresAt)
            InviteCreated(id = id.toString(), code = code, payload = DeepLinks.invite(publicUrl, code), expiresAt = expiresAt.toContract())
        }
    }

    suspend fun listActive(householdId: UUID): List<InviteInfo> =
        db.dbQuery { InviteRepository.listActive(householdId, clock.nowForDb()) }.map {
            InviteInfo(
                id = it.id.toString(),
                groupIds = it.groupIds.mapTo(mutableSetOf(), UUID::toString),
                createdBy = it.createdByMemberId.toString(),
                createdAt = it.createdAt.toContract(),
                expiresAt = it.expiresAt.toContract(),
            )
        }

    suspend fun revoke(
        householdId: UUID,
        inviteId: String,
    ) {
        val id = parseIdOrNotFound(inviteId, "Invite")
        db.dbQuery {
            InviteRepository.get(householdId, id) ?: throw notFound("Invite")
            InviteRepository.revoke(id, clock.nowForDb())
        }
    }

    suspend fun preview(
        request: InvitePreviewRequest,
        ip: String,
    ): InvitePreview {
        val key = limiterKey(ip)
        limiter.ensureAllowed(key)
        val preview =
            db.dbQuery {
                val invite = InviteRepository.findByCodeHash(CodeGenerator.hash(request.code))?.takeIf { it.isUsable(clock.nowForDb()) }
                invite?.let {
                    val household = HouseholdRepository.get(it.householdId) ?: error("Household ${it.householdId} not found")
                    val inviter = MemberRepository.get(it.householdId, it.createdByMemberId) ?: error("Inviter not found")
                    val inviterPerson = PersonRepository.get(it.householdId, inviter.personId) ?: error("Inviter person not found")
                    InvitePreview(householdName = household.data.name, inviterName = inviterPerson.data.name)
                }
            }
        return preview ?: throw invalidCode(key)
    }

    /** Creates user, person (or links the invite's relative), member in the invite's groups and a session. */
    suspend fun accept(
        request: AcceptInviteRequest,
        ip: String,
    ): AuthResult {
        validate {
            add("displayName", Validators.personName(request.displayName))
            add("login", Validators.login(request.login))
            add("password", Validators.password(request.password))
            addDevice(request.device)
        }
        val key = limiterKey(ip)
        limiter.ensureAllowed(key)
        val codeHash = CodeGenerator.hash(request.code)
        // A cheap check first, so wrong codes do not cost an Argon2 hash; the transaction below checks again under lock.
        db.dbQuery { InviteRepository.findByCodeHash(codeHash)?.takeIf { it.isUsable(clock.nowForDb()) } } ?: throw invalidCode(key)
        val passwordHash = withContext(Dispatchers.Default) { hasher.hash(request.password) }
        return db.dbQuery {
            val now = clock.nowForDb()
            // The row lock makes the invite single-use even when two people accept at the same moment.
            val invite =
                InviteRepository.findByCodeHash(codeHash, forUpdate = true)?.takeIf { it.isUsable(now) }
                    ?: throw ApiException(ErrorCode.INVITE_INVALID, "Invite is no longer valid")
            val householdId = invite.householdId
            lockHousehold(householdId)
            // Groups deleted after the invite was created are skipped.
            val groupIds = GroupRepository.activeIds(householdId, invite.groupIds)
            val person =
                invite.personId?.let { personId ->
                    PersonRepository
                        .get(householdId, personId)
                        ?.takeIf { !it.deleted && !MemberRepository.isPersonLinked(householdId, it.id) }
                        ?: throw ApiException(ErrorCode.INVITE_INVALID, "The invited person cannot be linked any more")
                }
            val ids =
                createMemberWithAccount(
                    householdId,
                    Validators.normalizeLogin(request.login),
                    passwordHash,
                    request.displayName,
                    person,
                    groupIds,
                    now,
                )
            InviteRepository.markUsed(invite.id, ids.memberId, now)
            val issued = auth.createSession(ids.userId, ids.memberId, householdId, request.device, now)
            auth.authResult(issued, ids.userId, ids.memberId, householdId)
        }
    }

    private fun invalidCode(limiterKey: String): ApiException {
        limiter.recordFailure(limiterKey)
        return ApiException(ErrorCode.INVITE_INVALID, "Invite unknown, used, revoked or expired")
    }

    private fun limiterKey(ip: String) = "invite-ip:$ip"

    companion object {
        const val CODE_LENGTH = 12
    }
}
