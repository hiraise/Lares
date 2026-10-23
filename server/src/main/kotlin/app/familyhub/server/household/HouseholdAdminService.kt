package app.familyhub.server.household

import app.familyhub.contract.api.admin.UpdateHouseholdRequest
import app.familyhub.contract.validation.Validators
import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.http.validate
import app.familyhub.server.platform.nowForDb
import app.familyhub.server.platform.sync.lockHousehold
import app.familyhub.server.platform.sync.recordChange
import java.util.UUID
import kotlin.time.Clock

/** Household settings (FND-003). The caller has `household.manage` (checked by the route). */
class HouseholdAdminService(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    suspend fun update(
        householdId: UUID,
        request: UpdateHouseholdRequest,
    ) {
        validate {
            request.name?.let { add("name", Validators.householdName(it)) }
            request.timeZone?.let { add("timeZone", Validators.timeZone(it)) }
            request.currency?.let { add("currency", Validators.currency(it)) }
            request.defaultLanguage?.let { add("defaultLanguage", Validators.language(it)) }
        }
        db.dbQuery {
            lockHousehold(householdId)
            val current = HouseholdRepository.get(householdId)?.data ?: error("Household $householdId not found")
            val updated =
                current.copy(
                    name = request.name?.trim() ?: current.name,
                    timeZone = request.timeZone ?: current.timeZone,
                    currency = request.currency ?: current.currency,
                    defaultLanguage = request.defaultLanguage ?: current.defaultLanguage,
                )
            if (updated != current) HouseholdRepository.update(householdId, updated, recordChange(householdId), clock.nowForDb())
        }
    }
}
