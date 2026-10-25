package app.lares.server.notifications

import app.lares.contract.api.notifications.NotificationSettings
import app.lares.server.household.MemberTable
import app.lares.server.identity.SessionTable
import app.lares.server.platform.db.HouseholdTable
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `push_registration`: the UnifiedPush endpoint of a session (FND-051). Not syncable. */
object PushRegistrationTable : Table("push_registration") {
    val sessionId = javaUUID("session_id").references(SessionTable.id, onDelete = ReferenceOption.CASCADE)
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val memberId = javaUUID("member_id").references(MemberTable.id)
    val endpoint = text("endpoint")
    val p256dh = text("p256dh")
    val auth = text("auth")
    val language = text("language")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(sessionId)
}

/** `instance_secret`: secrets the server generates for itself, e.g. VAPID keys. */
object InstanceSecretTable : Table("instance_secret") {
    val name = text("name")
    val value = text("value")

    override val primaryKey = PrimaryKey(name)
}

/** `notification_setting`: muted notification types per member (FND-052). */
object NotificationSettingTable : Table("notification_setting") {
    val memberId = javaUUID("member_id").references(MemberTable.id, onDelete = ReferenceOption.CASCADE)
    val type = text("type")
    val muted = bool("muted")

    override val primaryKey = PrimaryKey(memberId, type)
}

/** `notification_preference`: a member's birthday reminder lead time in days (CAL-016); missing row = default 3. */
object NotificationPreferenceTable : Table("notification_preference") {
    val memberId = javaUUID("member_id").references(MemberTable.id, onDelete = ReferenceOption.CASCADE)
    val birthdayDaysBefore = integer("birthday_days_before").default(NotificationSettings.DEFAULT_BIRTHDAY_DAYS_BEFORE)

    override val primaryKey = PrimaryKey(memberId)
}
