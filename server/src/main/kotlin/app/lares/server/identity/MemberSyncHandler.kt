package app.lares.server.identity

import app.lares.contract.api.LaresJson
import app.lares.contract.entities.MemberData
import app.lares.contract.sync.EntityTypes
import app.lares.server.household.MemberTable
import app.lares.server.sync.ChangeRow
import app.lares.server.sync.ReadOnlySyncHandler
import app.lares.server.sync.SyncColumns
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/** Members with their login state (read-only; written through the members endpoints). Password hashes never leave the server. */
class MemberSyncHandler : ReadOnlySyncHandler() {
    override val type: String = EntityTypes.MEMBER

    private val columns = SyncColumns(MemberTable, MemberTable.householdId, MemberTable.seq, MemberTable.deleted)

    override fun changesSince(
        tx: JdbcTransaction,
        householdId: UUID,
        after: Long,
        upTo: Long,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ChangeRow> {
        val rows = columns.changed(householdId, after, upTo, limit, includeTombstones)
        val userIds = rows.mapNotNull { it[MemberTable.userId] }
        val users =
            if (userIds.isEmpty()) {
                emptyMap()
            } else {
                UserAccountTable
                    .selectAll()
                    .where { UserAccountTable.id inList userIds }
                    .associateBy { it[UserAccountTable.id] }
            }
        return rows.map { row ->
            val deleted = row[MemberTable.deleted]
            val userId = row[MemberTable.userId]
            val user = userId?.let(users::get)
            val data =
                MemberData(
                    personId = row[MemberTable.personId].toString(),
                    userId = userId?.toString(),
                    login = user?.get(UserAccountTable.login),
                    hasPassword = user?.get(UserAccountTable.passwordHash) != null,
                )
            ChangeRow(
                type,
                row[MemberTable.id].toString(),
                row[MemberTable.seq],
                deleted,
                if (deleted) null else LaresJson.encodeToJsonElement(MemberData.serializer(), data).jsonObject,
            )
        }
    }
}
