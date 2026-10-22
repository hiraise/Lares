package app.familyhub.server.testing

import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.db.HouseholdSyncStateTable
import app.familyhub.server.platform.db.HouseholdTable
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Inserts a household with its sync state (last_seq = 0) and returns its id. */
suspend fun AppDatabase.insertHousehold(name: String = "Test family"): UUID {
    val id = UUID.randomUUID()
    val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
    dbQuery {
        HouseholdTable.insert {
            it[HouseholdTable.id] = id
            it[HouseholdTable.name] = name
            it[timeZone] = "Europe/Moscow"
            it[currency] = "RUB"
            it[defaultLanguage] = "ru"
            it[seq] = 0
            it[createdAt] = now
            it[updatedAt] = now
        }
        HouseholdSyncStateTable.insert {
            it[householdId] = id
            it[syncEpoch] = UUID.randomUUID()
        }
    }
    return id
}
