package app.lares.server.location

import app.lares.contract.api.LaresJson
import app.lares.contract.entities.PlaceAlertData
import app.lares.contract.entities.PlaceData
import app.lares.contract.entities.PlaceIcon
import app.lares.server.sync.StoredEntity
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/** Shared reads and row mapping of the place tables. All functions run inside `dbQuery`. */
object PlaceRepository {
    private val idSet = SetSerializer(String.serializer())

    /** The place by id in any household (the sync handler tells "other household" apart itself). */
    fun load(id: UUID): StoredEntity<PlaceData>? =
        PlaceTable.selectAll().where { PlaceTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[PlaceTable.householdId], it[PlaceTable.deleted], toPlaceData(it))
        }

    /** How many of [ids] are live places of the household. */
    fun countLive(
        householdId: UUID,
        ids: Set<UUID>,
    ): Long =
        PlaceTable
            .select(PlaceTable.id)
            .where { (PlaceTable.householdId eq householdId) and (PlaceTable.id inList ids) and (PlaceTable.deleted eq false) }
            .count()

    /** The alert row by id in any household. */
    fun loadAlert(id: UUID): StoredEntity<PlaceAlertData>? =
        PlaceAlertTable.selectAll().where { PlaceAlertTable.id eq id }.singleOrNull()?.let {
            StoredEntity(it[PlaceAlertTable.householdId], it[PlaceAlertTable.deleted], toAlertData(it))
        }

    /** Ids of the live alert rows about [memberId] or by them (member removal), ordered by id. */
    fun liveAlertIdsOf(
        householdId: UUID,
        memberId: UUID,
    ): List<UUID> =
        PlaceAlertTable
            .select(PlaceAlertTable.id)
            .where {
                (PlaceAlertTable.householdId eq householdId) and (PlaceAlertTable.deleted eq false) and
                    ((PlaceAlertTable.memberId eq memberId) or (PlaceAlertTable.subscriberId eq memberId))
            }.orderBy(PlaceAlertTable.id)
            .map { it[PlaceAlertTable.id] }

    /** Tombstones one alert row with the caller's [seq] (member removal). */
    fun tombstoneAlert(
        householdId: UUID,
        id: UUID,
        seq: Long,
        now: Instant,
    ) {
        PlaceAlertTable.update({ (PlaceAlertTable.householdId eq householdId) and (PlaceAlertTable.id eq id) }) {
            it[deleted] = true
            it[PlaceAlertTable.seq] = seq
            it[updatedAt] = now
        }
    }

    fun toPlaceData(row: ResultRow) =
        PlaceData(
            name = row[PlaceTable.name],
            icon = PlaceIcon.fromKey(row[PlaceTable.icon]) ?: PlaceIcon.PLACE,
            latitude = row[PlaceTable.latitude],
            longitude = row[PlaceTable.longitude],
            radiusM = row[PlaceTable.radiusM],
            address = row[PlaceTable.address],
            createdBy = row[PlaceTable.createdBy].toString(),
        )

    fun writePlace(
        it: UpdateBuilder<*>,
        data: PlaceData,
    ) {
        it[PlaceTable.name] = data.name
        it[PlaceTable.icon] = data.icon.key
        it[PlaceTable.latitude] = data.latitude
        it[PlaceTable.longitude] = data.longitude
        it[PlaceTable.radiusM] = data.radiusM
        it[PlaceTable.address] = data.address
        it[PlaceTable.createdBy] = UUID.fromString(data.createdBy)
    }

    fun toAlertData(row: ResultRow) =
        PlaceAlertData(
            memberId = row[PlaceAlertTable.memberId].toString(),
            subscriberId = row[PlaceAlertTable.subscriberId].toString(),
            placeIds = LaresJson.decodeFromString(idSet, row[PlaceAlertTable.placeIds]),
        )

    fun writeAlert(
        it: UpdateBuilder<*>,
        data: PlaceAlertData,
    ) {
        it[PlaceAlertTable.memberId] = UUID.fromString(data.memberId)
        it[PlaceAlertTable.subscriberId] = UUID.fromString(data.subscriberId)
        it[PlaceAlertTable.placeIds] = LaresJson.encodeToString(idSet, data.placeIds)
    }
}
