package app.lares.client.auth

import app.lares.client.db.LaresDb
import app.lares.client.sync.toData
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.GroupData
import app.lares.contract.permissions.EffectivePermissions
import app.lares.contract.permissions.GroupView
import app.lares.contract.permissions.PermissionEvaluator
import app.lares.contract.permissions.Viewer

/** The logged-in member, kept in the local database so permission checks work offline. Runs inside a transaction. */
internal object LocalSession {
    fun me(db: LaresDb): Me? =
        db.local_sessionQueries
            .get()
            .executeAsOneOrNull()
            ?.let { LaresJson.decodeFromString(Me.serializer(), it) }

    fun save(
        db: LaresDb,
        me: Me,
    ) {
        db.local_sessionQueries.put(LaresJson.encodeToString(Me.serializer(), me))
    }

    /** The current member as `WriteRules` needs it, or null when nobody is logged in. */
    fun viewer(db: LaresDb): Viewer? {
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
