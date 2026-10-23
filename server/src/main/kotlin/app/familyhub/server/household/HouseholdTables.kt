package app.familyhub.server.household

import app.familyhub.server.platform.db.HouseholdTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

// Tables of the household module (ADR-0012): persons, members and groups. Syncable tables share the sync columns.

object PersonTable : Table("person") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val name = text("name")
    val birthYear = integer("birth_year").nullable()
    val birthMonth = integer("birth_month").nullable()
    val birthDay = integer("birth_day").nullable()
    val relation = text("relation").nullable()
    val notes = text("notes").nullable()

    /** JSON array of `ContactData`. */
    val contacts = text("contacts").default("[]")

    /** `PaletteColor` wire name; null for persons created before FND-034. */
    val color = text("color").nullable()
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
}

object MemberTable : Table("member") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)

    /** References `user_account(id)` (identity module); kept as a plain column to keep modules independent. */
    val userId = javaUUID("user_id").nullable()
    val personId = javaUUID("person_id").references(PersonTable.id)

    /** Bumped whenever what the member may see changes (architecture/sync.md). */
    val accessVersion = long("access_version").default(1)
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
}

object GroupTable : Table("group_entity") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val name = text("name")

    /** `BuiltInGroup` name, or null for ordinary groups. */
    val builtIn = text("built_in").nullable()
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)
}

object GroupPermissionTable : Table("group_permission") {
    val groupId = javaUUID("group_id").references(GroupTable.id)

    /** Permission catalogue key, e.g. `tasks.view`. */
    val permission = text("permission")

    override val primaryKey = PrimaryKey(groupId, permission)
}

object GroupMembershipTable : Table("group_membership") {
    val groupId = javaUUID("group_id").references(GroupTable.id)
    val memberId = javaUUID("member_id").references(MemberTable.id)

    override val primaryKey = PrimaryKey(groupId, memberId)
}
