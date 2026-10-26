package app.lares.server.household

import app.lares.contract.entities.HouseholdData
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.platform.db.HouseholdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

// Repositories run inside `AppDatabase.dbQuery`; they never open transactions themselves.

data class HouseholdRow(
    val id: UUID,
    val data: HouseholdData,
)

object HouseholdRepository {
    fun anyExists(): Boolean =
        HouseholdTable
            .selectAll()
            .where { HouseholdTable.deleted eq false }
            .limit(1)
            .any()

    /**
     * Inserts the household (with seq 0, to be stamped by the caller after the sync state exists) and its sync state.
     * The first `recordChange` must come after this call.
     */
    fun create(
        id: UUID,
        data: HouseholdData,
        now: Instant,
    ) {
        HouseholdTable.insert {
            it[HouseholdTable.id] = id
            it[name] = data.name
            it[timeZone] = data.timeZone
            it[currency] = data.currency
            it[defaultLanguage] = data.defaultLanguage
            it[locationHistoryDays] = data.locationHistoryDays
            it[seq] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
        HouseholdSyncStateTable.insert {
            it[householdId] = id
            it[syncEpoch] = UUID.randomUUID()
        }
    }

    fun stampSeq(
        householdId: UUID,
        seq: Long,
        now: Instant,
    ) {
        HouseholdTable.update({ HouseholdTable.id eq householdId }) {
            it[HouseholdTable.seq] = seq
            it[updatedAt] = now
        }
    }

    /** Writes new settings with the caller's [seq]. */
    fun update(
        householdId: UUID,
        data: HouseholdData,
        seq: Long,
        now: Instant,
    ) {
        HouseholdTable.update({ HouseholdTable.id eq householdId }) {
            it[name] = data.name
            it[timeZone] = data.timeZone
            it[currency] = data.currency
            it[defaultLanguage] = data.defaultLanguage
            it[locationHistoryDays] = data.locationHistoryDays
            it[HouseholdTable.seq] = seq
            it[updatedAt] = now
        }
    }

    fun get(householdId: UUID): HouseholdRow? =
        HouseholdTable
            .selectAll()
            .where { HouseholdTable.id eq householdId }
            .singleOrNull()
            ?.let {
                HouseholdRow(
                    id = it[HouseholdTable.id],
                    data =
                        HouseholdData(
                            name = it[HouseholdTable.name],
                            timeZone = it[HouseholdTable.timeZone],
                            currency = it[HouseholdTable.currency],
                            defaultLanguage = it[HouseholdTable.defaultLanguage],
                            locationHistoryDays = it[HouseholdTable.locationHistoryDays],
                        ),
                )
            }
}
