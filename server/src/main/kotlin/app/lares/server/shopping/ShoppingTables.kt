package app.lares.server.shopping

import app.lares.server.household.MemberTable
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.sync.SyncColumns
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

// Tables of the shopping module (V7__shopping.sql, architecture/shopping.md § Server › Tables).

/** `shopping_list` (SHP-001). Tag ids are JSON text encoded with `LaresJson`. */
object ShoppingListTable : Table("shopping_list") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val name = text("name")
    val createdBy = javaUUID("created_by").references(MemberTable.id)
    val tagIds = text("tag_ids").default("[]")
    val groupByCategory = bool("group_by_category").default(true)
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}

/** `shopping_item` (SHP-002, ADR-0021). `status` and `category` hold wire names. */
object ShoppingItemTable : Table("shopping_item") {
    val id = javaUUID("id")
    val householdId = javaUUID("household_id").references(HouseholdTable.id)
    val listId = javaUUID("list_id").references(ShoppingListTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val status = text("status")
    val category = text("category")
    val quantity = text("quantity").nullable()
    val note = text("note").nullable()
    val tagIds = text("tag_ids").default("[]")
    val addedBy = javaUUID("added_by").references(MemberTable.id)
    val addedAt = timestamp("added_at")
    val boughtBy = javaUUID("bought_by").references(MemberTable.id).nullable()
    val boughtAt = timestamp("bought_at").nullable()
    val boughtCount = integer("bought_count").default(0)
    val seq = long("seq")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val deleted = bool("deleted").default(false)

    override val primaryKey = PrimaryKey(id)

    val sync = SyncColumns(this, householdId, seq, deleted)
}
