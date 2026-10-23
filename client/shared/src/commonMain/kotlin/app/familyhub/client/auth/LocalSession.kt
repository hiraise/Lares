package app.familyhub.client.auth

import app.familyhub.client.db.FamilyHubDb
import app.familyhub.client.sync.toData
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.auth.Me
import app.familyhub.contract.entities.GroupData
import app.familyhub.contract.permissions.EffectivePermissions
import app.familyhub.contract.permissions.GroupView
import app.familyhub.contract.permissions.PermissionEvaluator
import app.familyhub.contract.permissions.Viewer

/** The logged-in member, kept in the local database so permission checks work offline. Runs inside a transaction. */
internal object LocalSession {
    fun me(db: FamilyHubDb): Me? =
        db.local_sessionQueries
            .get()
            .executeAsOneOrNull()
            ?.let { FamilyHubJson.decodeFromString(Me.serializer(), it) }

    fun save(
        db: FamilyHubDb,
        me: Me,
    ) {
        db.local_sessionQueries.put(FamilyHubJson.encodeToString(Me.serializer(), me))
    }

    /** The current member as `WriteRules` needs it, or null when nobody is logged in. */
    fun viewer(db: FamilyHubDb): Viewer? {
        val me = me(db) ?: return null
        val groups =
            db.group_entityQueries
                .selectAll()
                .executeAsList()
                .map { it.id to it.toData() }
        return Viewer(me.memberId, me.personId, effectivePermissions(me, groups))
    }
}

/**
 * Permissions of [me] evaluated from the synced groups with the shared evaluator (ADR-0014). Until the first sync
 * brings groups, the permissions from login are used.
 */
fun effectivePermissions(
    me: Me,
    groups: List<Pair<String, GroupData>>,
): EffectivePermissions {
    if (groups.isEmpty()) return EffectivePermissions(me.isAdmin, me.permissions)
    val views = groups.map { (id, data) -> GroupView(id, data.builtIn, data.permissions, data.memberIds, deleted = false) }
    return PermissionEvaluator.evaluate(me.memberId, views)
}
