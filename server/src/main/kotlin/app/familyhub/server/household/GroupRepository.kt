package app.familyhub.server.household

import app.familyhub.contract.permissions.BuiltInGroup
import app.familyhub.contract.permissions.GroupView
import app.familyhub.contract.permissions.Permission
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

object GroupRepository {
    fun create(
        householdId: UUID,
        id: UUID,
        name: String,
        builtIn: BuiltInGroup?,
        permissions: Set<Permission>,
        seq: Long,
        now: Instant,
    ) {
        GroupTable.insert {
            it[GroupTable.id] = id
            it[GroupTable.householdId] = householdId
            it[GroupTable.name] = name
            it[GroupTable.builtIn] = builtIn?.name
            it[GroupTable.seq] = seq
            it[createdAt] = now
            it[updatedAt] = now
        }
        GroupPermissionTable.batchInsert(permissions) { permission ->
            this[GroupPermissionTable.groupId] = id
            this[GroupPermissionTable.permission] = permission.key
        }
    }

    /** Adds a membership. The caller stamps the group with a new seq ([stampSeq]): memberships are part of the group. */
    fun addMember(
        groupId: UUID,
        memberId: UUID,
    ) {
        GroupMembershipTable.insert {
            it[GroupMembershipTable.groupId] = groupId
            it[GroupMembershipTable.memberId] = memberId
        }
    }

    fun groupIdsOfMember(
        householdId: UUID,
        memberId: UUID,
    ): List<UUID> =
        (GroupMembershipTable innerJoin GroupTable)
            .select(GroupMembershipTable.groupId)
            .where { (GroupTable.householdId eq householdId) and (GroupMembershipTable.memberId eq memberId) }
            .map { it[GroupMembershipTable.groupId] }

    /** Removes all memberships of the member and returns the affected group ids (callers bump their seq). */
    fun removeMemberFromAll(
        householdId: UUID,
        memberId: UUID,
    ): List<UUID> {
        val groupIds = groupIdsOfMember(householdId, memberId)
        if (groupIds.isNotEmpty()) {
            GroupMembershipTable.deleteWhere {
                (GroupMembershipTable.memberId eq memberId) and (GroupMembershipTable.groupId inList groupIds)
            }
        }
        return groupIds
    }

    fun stampSeq(
        householdId: UUID,
        groupId: UUID,
        seq: Long,
        now: Instant,
    ) {
        GroupTable.update({ (GroupTable.householdId eq householdId) and (GroupTable.id eq groupId) }) {
            it[GroupTable.seq] = seq
            it[updatedAt] = now
        }
    }

    /** All groups of the household (including deleted ones, flagged) as the permission evaluator needs them. */
    fun loadViews(householdId: UUID): List<GroupView> {
        val groups = GroupTable.selectAll().where { GroupTable.householdId eq householdId }.toList()
        if (groups.isEmpty()) return emptyList()
        val ids = groups.map { it[GroupTable.id] }
        val permissions =
            GroupPermissionTable
                .selectAll()
                .where { GroupPermissionTable.groupId inList ids }
                .groupBy({ it[GroupPermissionTable.groupId] }) { it[GroupPermissionTable.permission] }
        val members =
            (GroupMembershipTable innerJoin MemberTable)
                .select(GroupMembershipTable.groupId, GroupMembershipTable.memberId)
                .where { (GroupMembershipTable.groupId inList ids) and (MemberTable.deleted eq false) }
                .groupBy({ it[GroupMembershipTable.groupId] }) { it[GroupMembershipTable.memberId].toString() }
        return groups.map { row ->
            val id = row[GroupTable.id]
            GroupView(
                id = id.toString(),
                builtIn = row[GroupTable.builtIn]?.let { name -> BuiltInGroup.entries.firstOrNull { it.name == name } },
                // Keys unknown to this version (e.g. after a downgrade) are ignored.
                permissions = permissions[id].orEmpty().mapNotNull(Permission::fromKey).toSet(),
                memberIds = members[id].orEmpty().toSet(),
                deleted = row[GroupTable.deleted],
            )
        }
    }

    /** Number of non-deleted members in the household's Administrators group, not counting [exceptMemberId]. */
    fun countAdministrators(
        householdId: UUID,
        exceptMemberId: UUID? = null,
    ): Long {
        var condition =
            (GroupTable.householdId eq householdId) and
                (GroupTable.builtIn eq BuiltInGroup.ADMINISTRATORS.name) and
                (GroupTable.deleted eq false) and
                (MemberTable.deleted eq false)
        if (exceptMemberId != null) condition = condition and (MemberTable.id neq exceptMemberId)
        return (GroupTable innerJoin GroupMembershipTable innerJoin MemberTable)
            .selectAll()
            .where { condition }
            .count()
    }
}
