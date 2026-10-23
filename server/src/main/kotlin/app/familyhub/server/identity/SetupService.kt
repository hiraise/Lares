package app.familyhub.server.identity

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.auth.AuthResult
import app.familyhub.contract.api.auth.SetupRequest
import app.familyhub.contract.entities.HouseholdData
import app.familyhub.contract.entities.PersonData
import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.contract.permissions.PermissionCatalogue
import app.familyhub.contract.validation.Validators
import app.familyhub.server.household.GroupRepository
import app.familyhub.server.household.HouseholdRepository
import app.familyhub.server.household.MemberRepository
import app.familyhub.server.household.PersonRepository
import app.familyhub.server.identity.crypto.PasswordHasher
import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.platform.http.validate
import app.familyhub.server.platform.nowForDb
import app.familyhub.server.platform.sync.recordChange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.util.UUID
import kotlin.time.Clock

/** First-run setup of an instance (FND-001, FND-023; architecture/auth.md § Instance setup). */
class SetupService(
    private val db: AppDatabase,
    private val clock: Clock,
    private val hasher: PasswordHasher,
    private val limiter: FailedAttemptLimiter,
    private val setupCode: SetupCodeProvider,
    private val auth: AuthService,
) {
    /** Called on server start: prints the setup code while the instance has no household. */
    suspend fun announceSetupCodeIfNeeded() {
        if (db.dbQuery { HouseholdRepository.anyExists() }) setupCode.forget() else setupCode.announce()
    }

    suspend fun setup(
        request: SetupRequest,
        ip: String,
    ): AuthResult {
        val limiterKey = "setup-ip:$ip"
        limiter.ensureAllowed(limiterKey)
        if (db.dbQuery { HouseholdRepository.anyExists() }) {
            throw ApiException(ErrorCode.SETUP_ALREADY_DONE, "The instance is already set up")
        }
        if (!setupCode.matches(request.setupCode)) {
            limiter.recordFailure(limiterKey)
            throw ApiException(ErrorCode.SETUP_CODE_INVALID, "Wrong setup code")
        }
        validate {
            add("householdName", Validators.householdName(request.householdName))
            add("timeZone", Validators.timeZone(request.timeZone))
            add("currency", Validators.currency(request.currency))
            add("defaultLanguage", Validators.language(request.defaultLanguage))
            add("displayName", Validators.personName(request.displayName))
            add("login", Validators.login(request.login))
            add("password", Validators.password(request.password))
            addDevice(request.device)
        }
        val passwordHash = withContext(Dispatchers.Default) { hasher.hash(request.password) }

        val result =
            db.dbQuery {
                // Two concurrent setups must not both pass the "no household yet" check.
                exec("SELECT pg_advisory_xact_lock($SETUP_LOCK_KEY)")
                if (HouseholdRepository.anyExists()) {
                    throw ApiException(ErrorCode.SETUP_ALREADY_DONE, "The instance is already set up")
                }
                createHousehold(request, passwordHash)
            }
        setupCode.forget()
        return result
    }

    /** Also used by tests to create additional households, bypassing the one-household rule of [setup]. */
    internal fun JdbcTransaction.createHousehold(
        request: SetupRequest,
        passwordHash: String,
    ): AuthResult {
        val now = clock.nowForDb()
        val householdId = UUID.randomUUID()
        HouseholdRepository.create(
            id = householdId,
            data =
                HouseholdData(
                    name = request.householdName.trim(),
                    timeZone = request.timeZone,
                    currency = request.currency,
                    defaultLanguage = request.defaultLanguage,
                ),
            now = now,
        )
        HouseholdRepository.stampSeq(householdId, recordChange(householdId), now)

        val names = groupNames(request.defaultLanguage)
        val adminsId = UUID.randomUUID()
        // Administrators store no permission rows: the evaluator grants them everything by the built-in flag.
        GroupRepository.create(
            householdId,
            adminsId,
            names.administrators,
            BuiltInGroup.ADMINISTRATORS,
            emptySet(),
            recordChange(householdId),
            now,
        )
        val adultsTemplate = PermissionCatalogue.adultsTemplate
        GroupRepository.create(householdId, UUID.randomUUID(), names.adults, null, adultsTemplate, recordChange(householdId), now)
        val kidsTemplate = PermissionCatalogue.kidsTemplate
        GroupRepository.create(householdId, UUID.randomUUID(), names.kids, null, kidsTemplate, recordChange(householdId), now)

        val userId = UUID.randomUUID()
        UserRepository.create(userId, Validators.normalizeLogin(request.login), passwordHash, now)
        val personId = UUID.randomUUID()
        val personData = PersonData(name = request.displayName.trim(), color = PersonRepository.leastUsedColor(householdId))
        PersonRepository.create(householdId, personId, personData, recordChange(householdId), now)
        val memberId = UUID.randomUUID()
        MemberRepository.create(householdId, memberId, userId, personId, recordChange(householdId), now)
        GroupRepository.addMember(adminsId, memberId)
        GroupRepository.stampSeq(householdId, adminsId, recordChange(householdId), now)

        val issued = auth.createSession(userId, memberId, householdId, request.device, now)
        return auth.authResult(issued, userId, memberId, householdId)
    }

    private data class GroupNames(
        val administrators: String,
        val adults: String,
        val kids: String,
    )

    /** Template names in the household default language; clients show the built-in group by its flag instead. */
    private fun groupNames(language: String): GroupNames =
        when (language) {
            "ru" -> GroupNames("Администраторы", "Взрослые", "Дети")
            else -> GroupNames("Administrators", "Adults", "Kids")
        }

    private companion object {
        /** Arbitrary constant identifying the setup advisory lock. */
        const val SETUP_LOCK_KEY = 4_242_001L
    }
}
