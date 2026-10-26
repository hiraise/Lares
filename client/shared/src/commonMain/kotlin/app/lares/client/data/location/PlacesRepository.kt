package app.lares.client.data.location

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.lares.client.auth.LocalSession
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.observeViewer
import app.lares.client.db.LaresDb
import app.lares.client.db.LocalDb
import app.lares.client.sync.LocalForbiddenException
import app.lares.client.sync.LocalWriteException
import app.lares.client.sync.LocalWriter
import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData
import app.lares.contract.permissions.Permission
import app.lares.contract.permissions.Viewer
import app.lares.contract.sync.EntityTypes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/** Places (everybody with `location.view`) and the logged-in member's own arrive / leave alerts; works offline. */
class PlacesRepository(
    private val localDb: LocalDb,
    private val permissions: PermissionsRepository,
) {
    private val db get() = localDb.db

    fun observePlaces(): Flow<List<Place>> =
        combine(
            db.placeQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            permissions.observeMine(),
        ) { rows, mine ->
            if (mine.has(Permission.LOCATION_VIEW)) rows.map { Place(it.id, it.toData()) } else emptyList()
        }.distinctUntilChanged()

    /** Own rows only (the server sends no others; a row left from another login is never shown). */
    fun observeMyAlerts(): Flow<List<PlaceAlert>> =
        combine(
            db.place_alertQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            observeViewer(localDb, permissions),
        ) { rows, viewer ->
            if (viewer == null || !viewer.permissions.has(Permission.LOCATION_VIEW)) {
                emptyList()
            } else {
                rows.map { PlaceAlert(it.id, it.toData()) }.filter { it.data.subscriberId == viewer.memberId }
            }
        }.distinctUntilChanged()
}

/**
 * Every user action on places and own alerts (LOC-020, LOC-021), through [LocalWriter]: checked locally, works
 * offline, sends only changed fields. Throws [LocalWriteException] when the change is not allowed or invalid.
 */
class PlacesWriter(
    private val writer: LocalWriter,
    private val localDb: LocalDb,
    @Suppress("unused") private val clock: Clock,
) {
    /** Creates a place and returns its id; `createdBy` is set to the member. */
    suspend fun create(draft: PlaceData): String {
        val me = localDb.read { viewer() }
        val id = Ids.newId()
        writer.upsert(EntityTypes.PLACE, id, encode(PlaceData.serializer(), draft.copy(createdBy = me.memberId)))
        return id
    }

    /** Saves [data] (changed fields only). */
    suspend fun update(
        id: String,
        data: PlaceData,
    ) {
        val current = localDb.read { place(id) }
        patch(EntityTypes.PLACE, id, PlaceData.serializer(), current, data.copy(createdBy = current.createdBy))
    }

    suspend fun delete(id: String) = writer.delete(EntityTypes.PLACE, id)

    /**
     * «Сообщать: прибытие и уход» about [memberId] for [placeIds] (empty = off). Writes the derived row with the full set,
     * only when it differs from the stored one.
     */
    suspend fun setAlert(
        memberId: String,
        placeIds: Set<String>,
    ) {
        val (me, current) =
            localDb.read {
                val me = viewer()
                me to
                    place_alertQueries
                        .selectById(Ids.placeAlertRowId(memberId, me.memberId))
                        .executeAsOneOrNull()
                        ?.toData()
            }
        if (current == null && placeIds.isEmpty()) return
        if (current?.placeIds == placeIds) return
        val data = PlaceAlertData(memberId, me.memberId, placeIds)
        val id = Ids.placeAlertRowId(memberId, me.memberId)
        // The full set always: two phones of the subscriber agree on the last write, not on a merge of sets.
        val fields = encode(PlaceAlertData.serializer(), data)
        writer.upsert(
            EntityTypes.PLACE_ALERT,
            id,
            if (current ==
                null
            ) {
                fields
            } else {
                JsonObject(mapOf("placeIds" to fields.getValue("placeIds")))
            },
        )
    }

    private suspend fun <T> patch(
        type: String,
        id: String,
        serializer: KSerializer<T>,
        current: T,
        newData: T,
    ) {
        val currentJson = encode(serializer, current)
        val newJson = encode(serializer, newData)
        val keys = (currentJson.keys + newJson.keys).filter { currentJson[it] != newJson[it] }
        if (keys.isEmpty()) return
        writer.upsert(type, id, JsonObject(keys.associateWith { newJson[it] ?: JsonNull }))
    }

    private fun LaresDb.viewer(): Viewer = LocalSession.viewer(this) ?: throw LocalForbiddenException("Not logged in")

    private fun LaresDb.place(id: String): PlaceData =
        placeQueries.selectById(id).executeAsOneOrNull()?.toData()
            ?: throw LocalWriteException(ErrorCode.ENTITY_NOT_FOUND, message = "Place not found")

    private fun <T> encode(
        serializer: KSerializer<T>,
        data: T,
    ): JsonObject = LaresJson.encodeToJsonElement(serializer, data).jsonObject
}
