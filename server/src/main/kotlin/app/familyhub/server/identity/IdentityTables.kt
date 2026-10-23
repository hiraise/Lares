package app.familyhub.server.identity

import app.familyhub.server.household.MemberTable
import app.familyhub.server.platform.db.HouseholdTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `user_account`: login identity, global to the instance. Not syncable. */
object UserAccountTable : Table("user_account") {
    val id = javaUUID("id")

    /** Lowercase. A deleted user's login becomes `deleted-<id>`, freeing the original. */
    val login = text("login")

    /** Argon2id PHC string; null when the user has no password (QR login only) or is deleted. */
    val passwordHash = text("password_hash").nullable()
    val createdAt = timestamp("created_at")
    val deletedAt = timestamp("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** `session`: one logged-in app installation (architecture/auth.md § Sessions and tokens). */
object SessionTable : Table("session") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(UserAccountTable.id)
    val memberId = javaUUID("member_id").references(MemberTable.id)
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val deviceName = text("device_name")
    val platform = text("platform")
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at")
    val accessHash = binary("access_hash")
    val accessExpiresAt = timestamp("access_expires_at")
    val refreshHash = binary("refresh_hash")
    val refreshExpiresAt = timestamp("refresh_expires_at")
    val previousRefreshHash = binary("previous_refresh_hash").nullable()
    val rotatedAt = timestamp("rotated_at").nullable()
    val accessUsedSinceRotation = bool("access_used_since_rotation").default(false)
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
