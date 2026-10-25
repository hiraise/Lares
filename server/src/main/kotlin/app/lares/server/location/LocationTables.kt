package app.lares.server.location

import app.lares.server.household.GroupTable
import app.lares.server.household.MemberTable
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.sync.SyncColumns
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

// Tables of the location module (V9__location_places.sql, V10__location_positions.sql, architecture/location.md § Server › Tables).

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

/** `position`: an uploaded fix (V10, ADR-0024). Never synced; deleted after the retention period. */
object PositionTable : Table("position") {
    val id = long("id").autoIncrement()
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val memberId = javaUUID("member_id").references(MemberTable.id)
    val sessionId = javaUUID("session_id").nullable()
    val recordedAt = timestamp("recorded_at")
    val latitude = double("latitude")
    val longitude = double("longitude")
    val accuracyM = float("accuracy_m")
    val speedMps = float("speed_mps").nullable()
    val bearingDeg = float("bearing_deg").nullable()
    val altitudeM = double("altitude_m").nullable()
    val battery = short("battery").nullable()
    val charging = bool("charging").nullable()
    val receivedAt = timestamp("received_at")

    override val primaryKey = PrimaryKey(id)
}

/** `location_state`: a member's sharing state and newest fix (V10). Status and reason are wire names. */
object LocationStateTable : Table("location_state") {
    val memberId = javaUUID("member_id").references(MemberTable.id)
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val status = text("status")
    val pausedUntil = timestamp("paused_until").nullable()
    val offReason = text("off_reason").nullable()
    val statusSince = timestamp("status_since")
    val sharingSessionId = javaUUID("sharing_session_id").nullable()
    val lastRecordedAt = timestamp("last_recorded_at").nullable()
    val lastLatitude = double("last_latitude").nullable()
    val lastLongitude = double("last_longitude").nullable()
    val lastAccuracyM = float("last_accuracy_m").nullable()
    val lastSpeedMps = float("last_speed_mps").nullable()
    val lastBearingDeg = float("last_bearing_deg").nullable()
    val lastAltitudeM = double("last_altitude_m").nullable()
    val lastBattery = short("last_battery").nullable()
    val lastCharging = bool("last_charging").nullable()
    val refreshRequestedAt = timestamp("refresh_requested_at").nullable()
    val refreshSentAt = timestamp("refresh_sent_at").nullable()
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(memberId)
}
