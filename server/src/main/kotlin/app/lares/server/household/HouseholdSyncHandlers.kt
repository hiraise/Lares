package app.lares.server.household

import app.lares.contract.api.LaresJson
import app.lares.contract.entities.GroupData
import app.lares.contract.entities.HouseholdData
import app.lares.contract.permissions.BuiltInGroup
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import app.lares.server.platform.db.HouseholdTable
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.ReadOnlySyncHandler
import app.lares.server.sync.SyncColumns
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

// Read-only sync types of the household module; they are written through the admin REST endpoints.

/** The household row itself; its tenant column is its id. */
class HouseholdSyncHandler : ReadOnlySyncHandler() {
    override val type: String = EntityTypes.HOUSEHOLD

    private val columns = SyncColumns(HouseholdTable, HouseholdTable.id, HouseholdTable.seq, HouseholdTable.deleted)

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> =
        columns.changed(householdId, after, upTo, limit, includeTombstones).map { row ->
            val deleted = row[HouseholdTable.deleted]
            val data =
                HouseholdData(
                    name = row[HouseholdTable.name],
                    timeZone = row[HouseholdTable.timeZone],
                    currency = row[HouseholdTable.currency],
                    defaultLanguage = row[HouseholdTable.defaultLanguage],
                    locationHistoryDays = row[HouseholdTable.locationHistoryDays],
                )
            ChangeRow(
                type,
                row[HouseholdTable.id].toString(),
                row[HouseholdTable.seq],
                deleted,
                if (deleted) null else LaresJson.encodeToJsonElement(HouseholdData.serializer(), data).jsonObject,
            )
        }
}

/** Groups with their permissions and member ids; changes to either bump the group's seq. */
class GroupSyncHandler : ReadOnlySyncHandler() {
    override val type: String = EntityTypes.GROUP

    private val columns = SyncColumns(GroupTable, GroupTable.householdId, GroupTable.seq, GroupTable.deleted)

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> {
        val rows = columns.changed(householdId, after, upTo, limit, includeTombstones)
        val ids = rows.filterNot { it[GroupTable.deleted] }.map { it[GroupTable.id] }
        val permissions =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                GroupPermissionTable
                    .selectAll()
                    .where { GroupPermissionTable.groupId inList ids }
                    .groupBy({ it[GroupPermissionTable.groupId] }) { it[GroupPermissionTable.permission] }
            }
        val members =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                (GroupMembershipTable innerJoin MemberTable)
                    .select(GroupMembershipTable.groupId, GroupMembershipTable.memberId)
                    .where { (GroupMembershipTable.groupId inList ids) and (MemberTable.deleted eq false) }
                    .groupBy({ it[GroupMembershipTable.groupId] }) { it[GroupMembershipTable.memberId].toString() }
            }
        return rows.map { row ->
            val id = row[GroupTable.id]
            val deleted = row[GroupTable.deleted]
            val data =
                if (deleted) {
                    null
                } else {
                    GroupData(
                        name = row[GroupTable.name],
                        builtIn = row[GroupTable.builtIn]?.let { name -> BuiltInGroup.entries.firstOrNull { it.name == name } },
                        permissions = permissions[id].orEmpty().mapNotNull(Permission::fromKey).toSet(),
                        memberIds = members[id].orEmpty().toSet(),
                    )
                }
            ChangeRow(
                type,
                id.toString(),
                row[GroupTable.seq],
                deleted,
                data?.let { LaresJson.encodeToJsonElement(GroupData.serializer(), it).jsonObject },
            )
        }
    }
}
