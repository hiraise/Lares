package app.familyhub.server.identity

import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.entities.MemberData
import app.familyhub.contract.sync.EntityTypes
import app.familyhub.server.household.MemberTable
import app.familyhub.server.sync.ChangeRow
import app.familyhub.server.sync.ReadOnlySyncHandler
import app.familyhub.server.sync.SyncColumns
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
                if (deleted) null else FamilyHubJson.encodeToJsonElement(MemberData.serializer(), data).jsonObject,
            )
        }
    }
}
