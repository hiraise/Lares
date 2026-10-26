package app.lares.server.location

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.FieldErrorCode
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.PlaceRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.server.platform.http.ApiException
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.MutationContext
import app.lares.server.sync.StoredEntity
import app.lares.server.sync.WritableSyncHandler
import app.lares.server.sync.invalidField
import app.lares.server.sync.requireDerivedId
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

/**
 * A subscriber's arrive / leave alerts about one member (LOC-021, ADR-0023 pattern). Only the subscriber writes and pulls
 * the row; its id is derived from member and subscriber. Clients never delete these rows (`WriteRules`): "no alerts" is
 * an empty `placeIds`; only member removal tombstones them.
 */
class PlaceAlertSyncHandler : WritableSyncHandler<PlaceAlertData>(PlaceAlertData.serializer()) {
    override val type: String = EntityTypes.PLACE_ALERT

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        PlaceAlertTable.sync.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[PlaceAlertTable.deleted]
            ChangeRow(
                type,
                row[PlaceAlertTable.id].toString(),
                row[PlaceAlertTable.seq],
                deleted,
                if (deleted) null else toJson(PlaceRepository.toAlertData(row)),
            )
        }

    /** Live rows only for their subscriber; a tombstone carries no data and is visible to everybody with `location.view`. */
    override fun canSee(
        viewer: Viewer,
        row: ChangeRow,
    ): Boolean {
        if (!viewer.permissions.has(Permission.LOCATION_VIEW)) return false
        val data = row.data ?: return true
        return data["subscriberId"]?.jsonPrimitive?.content == viewer.memberId
    }

    override fun load(id: UUID): StoredEntity<PlaceAlertData>? = PlaceRepository.loadAlert(id)

    override fun validate(
        ctx: MutationContext,
        data: PlaceAlertData,
    ): Map<String, FieldErrorCode> = Validators.placeAlert(data)

    override fun authorize(
        ctx: MutationContext,
        op: MutationOp,
        id: UUID,
        existing: PlaceAlertData?,
        merged: PlaceAlertData?,
    ) {
        // Both the stored row and the result must be the writer's own.
        val allowed =
            (existing == null || PlaceRules.canSetAlert(ctx.viewer, existing)) &&
                (merged == null || PlaceRules.canSetAlert(ctx.viewer, merged))
        if (!allowed) throw ApiException(ErrorCode.FORBIDDEN, "Not allowed to ${op.name.lowercase()} these alerts")
    }

    override fun checkUpsert(
        ctx: MutationContext,
        id: UUID,
        existing: PlaceAlertData?,
        data: PlaceAlertData,
    ) {
        if (existing != null) {
            if (existing.memberId != data.memberId) throw invalidField("memberId")
            if (existing.subscriberId != data.subscriberId) throw invalidField("subscriberId")
        }
        requireDerivedId(id) { Ids.placeAlertRowId(data.memberId, data.subscriberId) }
        requireLiveMember(ctx, data.memberId)
        requireAddedPlaces(ctx, data.placeIds - existing?.placeIds.orEmpty())
    }

    override fun insert(
        ctx: MutationContext,
        id: UUID,
        data: PlaceAlertData,
        seq: Long,
    ) {
        PlaceAlertTable.insert {
            it[PlaceAlertTable.id] = id
            it[householdId] = ctx.householdId
            PlaceRepository.writeAlert(it, data)
            it[PlaceAlertTable.seq] = seq
            it[createdAt] = ctx.now
            it[updatedAt] = ctx.now
        }
    }

    override fun update(
        ctx: MutationContext,
        id: UUID,
        data: PlaceAlertData,
        seq: Long,
    ) {
        PlaceAlertTable.update({ (PlaceAlertTable.householdId eq ctx.householdId) and (PlaceAlertTable.id eq id) }) {
            PlaceRepository.writeAlert(it, data)
            it[PlaceAlertTable.seq] = seq
            it[updatedAt] = ctx.now
        }
    }

    /** Unreachable from a push (`WriteRules` rejects deletes); only member removal tombstones these rows. */
    override fun tombstone(
        ctx: MutationContext,
        id: UUID,
        seq: Long,
    ): Unit = throw ApiException(ErrorCode.FORBIDDEN, "Alert rows are removed only with their members")
}
