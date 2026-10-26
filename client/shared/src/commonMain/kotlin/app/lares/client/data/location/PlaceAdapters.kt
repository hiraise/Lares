package app.lares.client.data.location

import app.lares.client.db.LaresDb
import app.lares.client.sync.EntityAdapter
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalWriteException
import app.lares.client.sync.forbidden
import app.lares.client.sync.invalid
import app.lares.client.sync.requireDerivedId
import app.lares.client.sync.stringList
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData
import app.lares.contract.entities.PlaceIcon
import app.lares.contract.permissions.PlaceRules
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.MutationOp
import app.lares.contract.validation.Validators
import app.lares.client.db.Place as PlaceRow
import app.lares.client.db.Place_alert as PlaceAlertRow

// Location adapters (architecture/location.md § Core): the same PlaceRules checks, immutable fields and derived-id
// check as the server's handlers (M4-02); references are checked against the local tables.

internal fun PlaceRow.toData() =
    PlaceData(
        name = name,
        icon = PlaceIcon.fromKey(icon) ?: PlaceIcon.PLACE,
        latitude = latitude,
        longitude = longitude,
        radiusM = radius_m.toInt(),
        address = address,
        createdBy = created_by,
    )

internal fun PlaceAlertRow.toData() = PlaceAlertData(member_id, subscriber_id, LaresJson.decodeFromString(stringList, place_ids).toSet())

class PlaceAdapter : EntityAdapter<PlaceData>(EntityTypes.PLACE, PlaceData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.placeQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: PlaceData,
    ) {
        db.placeQueries.upsert(
            id = id,
            name = data.name,
            icon = data.icon.key,
            latitude = data.latitude,
            longitude = data.longitude,
            radius_m = data.radiusM.toLong(),
            address = data.address,
            created_by = data.createdBy,
        )
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.placeQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.placeQueries.deleteAll()
    }

    override fun normalize(data: PlaceData) = Validators.normalizePlace(data)

    override fun validate(data: PlaceData) = Validators.place(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: PlaceData?,
        merged: PlaceData?,
    ) {
        val allowed =
            when {
                current != null -> PlaceRules.canManage(viewer)
                merged != null -> PlaceRules.canCreate(viewer, merged)
                else -> false
            }
        if (!allowed) forbidden(op, "place")
        if (current != null && merged != null && current.createdBy != merged.createdBy) invalid("createdBy")
    }
}

class PlaceAlertAdapter : EntityAdapter<PlaceAlertData>(EntityTypes.PLACE_ALERT, PlaceAlertData.serializer(), writable = true) {
    override fun selectTyped(
        db: LaresDb,
        id: String,
    ) = db.place_alertQueries
        .selectById(id)
        .executeAsOneOrNull()
        ?.toData()

    override fun upsertTyped(
        db: LaresDb,
        id: String,
        data: PlaceAlertData,
    ) {
        db.place_alertQueries.upsert(id, data.memberId, data.subscriberId, LaresJson.encodeToString(stringList, data.placeIds.sorted()))
    }

    override fun deleteTyped(
        db: LaresDb,
        id: String,
    ) {
        db.place_alertQueries.deleteById(id)
    }

    override fun deleteAllTyped(db: LaresDb) {
        db.place_alertQueries.deleteAll()
    }

    override fun validate(data: PlaceAlertData) = Validators.placeAlert(data)

    override fun authorize(
        db: LaresDb,
        viewer: Viewer,
        op: MutationOp,
        id: String,
        current: PlaceAlertData?,
        merged: PlaceAlertData?,
    ) {
        // Both the stored row and the result must be the writer's own.
        val allowed =
            (current == null || PlaceRules.canSetAlert(viewer, current)) &&
                (merged == null || PlaceRules.canSetAlert(viewer, merged))
        if (!allowed) forbidden(op, "alert")
        if (merged == null) return
        if (current != null) {
            if (current.memberId != merged.memberId) invalid("memberId")
            if (current.subscriberId != merged.subscriberId) invalid("subscriberId")
        }
        requireDerivedId(id) { Ids.placeAlertRowId(merged.memberId, merged.subscriberId) }
        if (db.memberQueries.selectById(merged.memberId).executeAsOneOrNull() == null) {
            throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Member not found")
        }
        val added = merged.placeIds - current?.placeIds.orEmpty()
        if (added.any { db.placeQueries.selectById(it).executeAsOneOrNull() == null }) {
            throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Place not found")
        }
    }

    /** Never reached: `WriteRules` refuses deletes of alert rows; only member removal on the server removes them. */
    override fun checkDelete(
        db: LaresDb,
        id: String,
        current: PlaceAlertData,
    ): Unit = throw LocalForbiddenException("Alert rows are removed only with their members")
}
