package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.api.location.AddressLabel
import app.lares.contract.api.location.AddressSearch
import app.lares.server.identity.FailedAttemptLimiter
import app.lares.server.identity.MemberPrincipal
import app.lares.server.notifications.NotificationTexts
import app.lares.server.notifications.PushRegistrationTable
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.platform.http.ApiException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * `GET /location/address` and `GET /location/search` (LOC-014, LOC-024): validation, per-member rate limits and the
 * caller's language around [AddressService]. Permissions are checked by the routes.
 */
class AddressEndpoints(
    private val db: AppDatabase,
    clock: Clock,
    private val addresses: AddressService,
) {
    private val addressLimiter = FailedAttemptLimiter(maxFailures = ADDRESS_PER_MINUTE, window = 1.minutes, clock = clock)
    private val searchLimiter = FailedAttemptLimiter(maxFailures = SEARCH_PER_MINUTE, window = 1.minutes, clock = clock)

    suspend fun address(
        principal: MemberPrincipal,
        lat: String?,
        lon: String?,
    ): AddressLabel {
        val latitude = coordinate(lat, LAT_LIMIT)
        val longitude = coordinate(lon, LON_LIMIT)
        val errors =
            buildMap {
                latitude.second?.let { put("lat", it) }
                longitude.second?.let { put("lon", it) }
            }
        if (errors.isNotEmpty()) throw invalid(errors)
        count(addressLimiter, principal)
        if (!addresses.enabled) return AddressLabel()
        val language = language(principal)
        return AddressLabel(addresses.reverse(latitude.first!!, longitude.first!!, language))
    }

    suspend fun search(
        principal: MemberPrincipal,
        q: String?,
    ): AddressSearch {
        val query = q?.trim().orEmpty()
        when {
            query.length < MIN_QUERY -> throw invalid(mapOf("q" to FieldErrorCode.TOO_SHORT))
            query.length > MAX_QUERY -> throw invalid(mapOf("q" to FieldErrorCode.TOO_LONG))
        }
        count(searchLimiter, principal)
        if (!addresses.enabled) return AddressSearch(emptyList())
        val (language, near) = db.dbQuery { languageInTransaction(principal) to searchArea(principal.householdId) }
        return AddressSearch(addresses.search(query, language, near))
    }

    /** Every request counts; the window is per member. */
    private fun count(
        limiter: FailedAttemptLimiter,
        principal: MemberPrincipal,
    ) {
        val key = principal.memberId.toString()
        limiter.ensureAllowed(key)
        limiter.recordFailure(key)
    }

    private suspend fun language(principal: MemberPrincipal): String = db.dbQuery { languageInTransaction(principal) }

    /** The caller's push registration language if any, else the household's default language. */
    private fun languageInTransaction(principal: MemberPrincipal): String {
        val registered =
            PushRegistrationTable
                .select(PushRegistrationTable.language)
                .where { PushRegistrationTable.sessionId eq principal.sessionId }
                .singleOrNull()
                ?.get(PushRegistrationTable.language)
        val language =
            registered ?: HouseholdTable
                .select(HouseholdTable.defaultLanguage)
                .where { HouseholdTable.id eq principal.householdId }
                .single()[HouseholdTable.defaultLanguage]
        return NotificationTexts.language(language)
    }

    /** Search prefers results around the household's first place (rounded to 0.1° by [AddressService]); none = no preference. */
    private fun searchArea(householdId: UUID): Pair<Double, Double>? =
        PlaceTable
            .select(PlaceTable.latitude, PlaceTable.longitude)
            .where { (PlaceTable.householdId eq householdId) and (PlaceTable.deleted eq false) }
            .orderBy(PlaceTable.createdAt to SortOrder.ASC, PlaceTable.id to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?.let { it[PlaceTable.latitude] to it[PlaceTable.longitude] }

    private fun coordinate(
        text: String?,
        limit: Double,
    ): Pair<Double?, FieldErrorCode?> {
        if (text.isNullOrBlank()) return null to FieldErrorCode.REQUIRED
        val value = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null to FieldErrorCode.INVALID_FORMAT
        return if (value < -limit || value > limit) null to FieldErrorCode.OUT_OF_RANGE else value to null
    }

    private fun invalid(errors: Map<String, FieldErrorCode>) =
        ApiException(ErrorCode.VALIDATION_FAILED, "Invalid fields: ${errors.keys.joinToString()}", errors)

    companion object {
        const val ADDRESS_PER_MINUTE = 60
        const val SEARCH_PER_MINUTE = 10
        const val MIN_QUERY = 3
        const val MAX_QUERY = 200
        private const val LAT_LIMIT = 90.0
        private const val LON_LIMIT = 180.0
    }
}
