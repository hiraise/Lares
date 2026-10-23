package app.lares.server.platform.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `household`: the tenant root (ADR-0010). Its row is syncable like any other entity, hence `seq`. */
object HouseholdTable : Table("household") {
    val id = javaUUID("id")
    val name = text("name")
    val timeZone = text("time_zone")
    val currency = char("currency", 3)
    val defaultLanguage = text("default_language")
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
}

/** `household_sync_state`: the change sequence counter of a household (architecture/sync.md). */
object HouseholdSyncStateTable : Table("household_sync_state") {
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val lastSeq = long("last_seq").default(0)
    val purgeHorizonSeq = long("purge_horizon_seq").default(0)
    val syncEpoch = javaUUID("sync_epoch")

    override val primaryKey = PrimaryKey(householdId)
}
