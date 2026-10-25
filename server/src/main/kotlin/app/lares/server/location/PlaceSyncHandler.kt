package app.lares.server.location

import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.PlaceData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PlaceRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.server.platform.db.afterCommit
import app.lares.server.platform.http.ApiException
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.MutationContext
import app.lares.server.sync.StoredEntity
import app.lares.server.sync.WritableSyncHandler
import app.lares.server.sync.invalidField
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * Family places (LOC-020): written with `location.manage_places` (`PlaceRules`), seen with `location.view`. Every write
 * tells the live channel through [hub] after commit, since a place decides where members are shown.
 */
class PlaceSyncHandler(
    private val hub: LocationHub,
) : WritableSyncHandler<PlaceData>(PlaceData.serializer()) {
    override val type: String = EntityTypes.PLACE

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        PlaceTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[PlaceTable.deleted]
            ChangeRow(
                type,
                row[PlaceTable.id].toString(),
                row[PlaceTable.seq],
                deleted,
                if (deleted) null else toJson(PlaceRepository.toPlaceData(row)),
            )
        }

    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean = viewer.permissions.has(Permission.LOCATION_VIEW)

    override fun load(id: UUID): StoredEntity<PlaceData>? = PlaceRepository.load(id)

    override fun normalize(data: PlaceData): PlaceData = Validators.normalizePlace(data)

    override fun validate(
        ctx: MutationContext,
        data: PlaceData,
    ): Map<String, FieldErrorCode> = Validators.place(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: PlaceData?,
        merged: PlaceData?,
    ) {
        val allowed =
            when {
                existing != null -> PlaceRules.canManage(ctx.viewer)
                merged != null -> PlaceRules.canCreate(ctx.viewer, merged)
                else -> false
            }
        if (!allowed) throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} this place")
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: PlaceData?,
        data: PlaceData,
    ) {
        if (existing != null && existing.createdBy != data.createdBy) throw invalidField("createdBy")
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: PlaceData,
        seq: Long,
    ) {
        PlaceTable.insert {
            it[PlaceTable.id] = id
            it[householdId] = ctx.householdId
            PlaceRepository.writePlace(it, data)
            it[PlaceTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
        publishAfterCommit(ctx)
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: PlaceData,
        seq: Long,
    ) {
        PlaceTable.update({ (PlaceTable.householdId eq ctx.householdId) and (PlaceTable.id eq id) }) {
            PlaceRepository.writePlace(it, data)
            it[PlaceTable.seq] = seq
            it[updatedAt] = ctx.now
        }
        publishAfterCommit(ctx)
    }

    /** Alert rows keep the id of a deleted place; readers ignore it. */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ) {
        PlaceTable.update({ (PlaceTable.householdId eq ctx.householdId) and (PlaceTable.id eq id) }) {
            it[deleted] = true
            it[PlaceTable.seq] = seq
            it[updatedAt] = ctx.now
        }
        publishAfterCommit(ctx)
    }

    private fun publishAfterCommit(ctx: MutationContext) {
        val householdId = ctx.householdId
        TransactionManager.current().afterCommit { hub.publish(LocationHubEvent.PlacesChanged(householdId)) }
    }
}
