package app.lares.server.shopping

import app.lares.contract.api.LaresJson
import app.lares.contract.entities.ProductCategory
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingItemStatus
import app.lares.contract.entities.ShoppingListData
import app.lares.server.platform.toContract
import app.lares.server.platform.toDb
import app.lares.server.sync.StoredEntity
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Shared reads and row mapping of the shopping tables. All functions run inside `dbQuery`. */
object ShoppingRepository {
    private val idSet = SetSerializer(String.serializer())

    /** The list by id in any household (the sync handlers tell "other household" apart themselves). */
    fun loadList(id: UUID): StoredEntity<ShoppingListData>? =
        ShoppingListTable.selectAll().where { ShoppingListTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[ShoppingListTable.householdId], it[ShoppingListTable.deleted], toListData(it))
        }

    /** The list of [householdId] with [id], tombstoned or not; null if missing or in another household. */
    fun getList(
        householdId: UUID,
        id: UUID,
    ): StoredEntity<ShoppingListData>? = loadList(id)?.takeIf { it.householdId == householdId }

    /** The item by id in any household. */
    fun loadItem(id: UUID): StoredEntity<ShoppingItemData>? =
        ShoppingItemTable.selectAll().where { ShoppingItemTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[ShoppingItemTable.householdId], it[ShoppingItemTable.deleted], toItemData(it))
        }

    fun toListData(row: ResultRow) =
        ShoppingListData(
            name = row[ShoppingListTable.name],
            createdBy = row[ShoppingListTable.createdBy].toString(),
            tagIds = LaresJson.decodeFromString(idSet, row[ShoppingListTable.tagIds]),
            groupByCategory = row[ShoppingListTable.groupByCategory],
        )

    fun writeList(
        it: UpdateBuilder<*>,
        data: ShoppingListData,
    ) {
        it[ShoppingListTable.name] = data.name
        it[ShoppingListTable.createdBy] = UUID.fromString(data.createdBy)
        it[ShoppingListTable.tagIds] = LaresJson.encodeToString(idSet, data.tagIds)
        it[ShoppingListTable.groupByCategory] = data.groupByCategory
    }

    // Unknown wire names cannot be stored (the handler rejects them); the contract fallbacks cover them anyway.
    fun toItemData(row: ResultRow) =
        ShoppingItemData(
            listId = row[ShoppingItemTable.listId].toString(),
            name = row[ShoppingItemTable.name],
            addedBy = row[ShoppingItemTable.addedBy].toString(),
            addedAt = row[ShoppingItemTable.addedAt].toContract(),
            status = ShoppingItemStatus.fromKey(row[ShoppingItemTable.status]) ?: ShoppingItemStatus.CLEARED,
            category = ProductCategory.fromKey(row[ShoppingItemTable.category]) ?: ProductCategory.OTHER,
            quantity = row[ShoppingItemTable.quantity],
            note = row[ShoppingItemTable.note],
            tagIds = LaresJson.decodeFromString(idSet, row[ShoppingItemTable.tagIds]),
            boughtBy = row[ShoppingItemTable.boughtBy]?.toString(),
            boughtAt = row[ShoppingItemTable.boughtAt]?.toContract(),
            boughtCount = row[ShoppingItemTable.boughtCount],
        )

    fun writeItem(
        it: UpdateBuilder<*>,
        data: ShoppingItemData,
    ) {
        it[ShoppingItemTable.listId] = UUID.fromString(data.listId)
        it[ShoppingItemTable.name] = data.name
        it[ShoppingItemTable.addedBy] = UUID.fromString(data.addedBy)
        it[ShoppingItemTable.addedAt] = data.addedAt.toDb()
        it[ShoppingItemTable.status] = data.status.key
        it[ShoppingItemTable.category] = data.category.key
        it[ShoppingItemTable.quantity] = data.quantity
        it[ShoppingItemTable.note] = data.note
        it[ShoppingItemTable.tagIds] = LaresJson.encodeToString(idSet, data.tagIds)
        it[ShoppingItemTable.boughtBy] = data.boughtBy?.let(UUID::fromString)
        it[ShoppingItemTable.boughtAt] = data.boughtAt?.toDb()
        it[ShoppingItemTable.boughtCount] = data.boughtCount
    }
}
