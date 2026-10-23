package app.familyhub.server.identity

import app.familyhub.server.household.MemberTable
import app.familyhub.server.household.PersonTable
import app.familyhub.server.platform.db.HouseholdTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `user_account`: login identity, global to the instance. Not syncable. */
object UserAccountTable : Table("user_account") {
    val id = javaUUID("id")

    /**
     * Lowercase; null for members who log in by QR code only (FND-010). A deleted user's login becomes `deleted-<id>`,
     * freeing the original.
     */
    val login = text("login").nullable()

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

/** `invite`: a single-use code to join a household (FND-024). Only the SHA-256 of the normalized code is stored. */
object InviteTable : Table("invite") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val codeHash = binary("code_hash")

    /** JSON array of group ids. */
    val groupIds = text("group_ids")
    val personId = javaUUID("person_id").references(PersonTable.id).nullable()
    val createdByMemberId = javaUUID("created_by_member_id").references(MemberTable.id)
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()
    val usedByMemberId = javaUUID("used_by_member_id").references(MemberTable.id).nullable()
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** `login_code`: a single-use, short-lived code for QR login on a new device (FND-014). */
object LoginCodeTable : Table("login_code") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val memberId = javaUUID("member_id").references(MemberTable.id)
    val codeHash = binary("code_hash")
    val createdByMemberId = javaUUID("created_by_member_id").references(MemberTable.id)
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
