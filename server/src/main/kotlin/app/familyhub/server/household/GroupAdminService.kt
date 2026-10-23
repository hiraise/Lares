package app.familyhub.server.household

import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.admin.CreateGroupRequest
import app.familyhub.contract.api.admin.GroupCreated
import app.familyhub.contract.api.admin.UpdateGroupRequest
import app.familyhub.contract.validation.Validators
import app.familyhub.server.platform.db.AppDatabase
import app.familyhub.server.platform.http.ApiException
import app.familyhub.server.platform.http.validate
import app.familyhub.server.platform.newEntityId
import app.familyhub.server.platform.nowForDb
import app.familyhub.server.platform.sync.lockHousehold
import app.familyhub.server.platform.sync.recordChange
import java.util.UUID
import kotlin.time.Clock

/**
 * Groups, their permissions and members (FND-021, FND-022; ADR-0014). The caller has `household.manage`.
 * "Administrators" cannot be renamed, deleted or given other permissions (`BUILTIN_GROUP`) and keeps at least one member.
 */
class GroupAdminService(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    suspend fun create(
        householdId: UUID,
        request: CreateGroupRequest,
    ): GroupCreated {
        validate { add("name", Validators.groupName(request.name)) }
        return db.dbQuery {
            lockHousehold(householdId)
            val memberIds = HouseholdRefs.requireMembers(householdId, request.memberIds)
            val groupId = newEntityId()
            val now = clock.nowForDb()
            GroupRepository.create(householdId, groupId, request.name.trim(), null, request.permissions, recordChange(householdId), now)
            GroupRepository.addMembers(groupId, memberIds)
            AccessVersionService.bump(householdId, memberIds)
            GroupCreated(groupId.toString())
        }
    }

    suspend fun update(
        householdId: UUID,
        groupId: String,
        request: UpdateGroupRequest,
    ) {
        validate { request.name?.let { add("name", Validators.groupName(it)) } }
        db.dbQuery {
            lockHousehold(householdId)
            val group = HouseholdRefs.requireGroup(householdId, groupId)
            if (group.builtIn != null && (request.name != null || request.permissions != null)) {
                throw ApiException(ErrorCode.BUILTIN_GROUP, "The built-in group cannot be renamed or get other permissions")
            }
            val memberIds = request.memberIds?.let { HouseholdRefs.requireMembers(householdId, it) }
            var changed = false
            val affected = mutableSetOf<UUID>()

            val name = request.name?.trim()
            if (name != null && name != group.name) {
                GroupRepository.rename(householdId, group.id, name)
                changed = true
            }
            val permissions = request.permissions
            if (permissions != null && permissions != GroupRepository.permissionsOf(group.id)) {
                GroupRepository.setPermissions(group.id, permissions)
                // Members before and after a membership change below are all affected.
                affected += GroupRepository.memberIdsOf(group.id)
                memberIds?.let(affected::addAll)
                changed = true
            }
            if (memberIds != null) {
                val membershipChanged = setMembersOfGroup(group, memberIds)
                if (membershipChanged.isNotEmpty()) changed = true
                affected += membershipChanged
            }
            if (changed) GroupRepository.stampSeq(householdId, group.id, recordChange(householdId), clock.nowForDb())
            AccessVersionService.bump(householdId, affected)
        }
    }

    /** Deletes an ordinary group: removes its memberships and leaves a tombstone for sync. */
    suspend fun delete(
        householdId: UUID,
        groupId: String,
    ) {
        db.dbQuery {
            lockHousehold(householdId)
            val group = HouseholdRefs.requireGroup(householdId, groupId)
            if (group.builtIn != null) throw ApiException(ErrorCode.BUILTIN_GROUP, "The built-in group cannot be deleted")
            val members = GroupRepository.memberIdsOf(group.id)
            GroupRepository.removeMembers(group.id, members)
            GroupRepository.tombstone(householdId, group.id, recordChange(householdId), clock.nowForDb())
            AccessVersionService.bump(householdId, members)
        }
    }
}
