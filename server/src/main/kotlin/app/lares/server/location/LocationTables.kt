package app.lares.server.location

import app.lares.server.household.GroupTable
import app.lares.server.household.MemberTable
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.sync.SyncColumns
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

// Tables of the location module (V9__location_places.sql, architecture/location.md § Server › Tables).

/** `place`: a family place (LOC-020). */
object PlaceTable : Table("place") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val name = text("name")

    /** `PlaceIcon.key`. */
    val icon = text("icon").default("place")
    val latitude = double("latitude")
    val longitude = double("longitude")
    val radiusM = integer("radius_m")
    val address = text("address").nullable()
    val createdBy = javaUUID("created_by").references(MemberTable.id)
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `place_alert`: one subscriber's arrive / leave alerts about one member (LOC-021); id derived from both. */
object PlaceAlertTable : Table("place_alert") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val memberId = javaUUID("member_id").references(MemberTable.id)
    val subscriberId = javaUUID("subscriber_id").references(MemberTable.id)

    /** JSON array of place ids. */
    val placeIds = text("place_ids").default("[]")
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `location_rule`: who sees whose location (ADR-0025). Server only; kinds and levels are wire names. */
object LocationRuleTable : Table("location_rule") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val subjectKind = text("subject_kind")
    val subjectGroupId = javaUUID("subject_group_id").references(GroupTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val subjectMemberId = javaUUID("subject_member_id").references(MemberTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val targetKind = text("target_kind")
    val targetGroupId = javaUUID("target_group_id").references(GroupTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val targetMemberId = javaUUID("target_member_id").references(MemberTable.id, onDelete = ReferenceOption.CASCADE).nullable()
    val level = text("level")
    val position = integer("position")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
