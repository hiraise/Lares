package app.lares.client.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.lares.client.db.LocalDb
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.Me
import app.lares.contract.permissions.Viewer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** The logged-in member with their current permissions, or null when nobody is logged in. */
fun observeViewer(
    localDb: LocalDb,
    permissions: PermissionsRepository,
): Flow<Viewer?> {
    val me =
        localDb.db.local_sessionQueries
            .get()
            .asFlow()
            .mapToOneOrNull(localDb.dispatcher)
            .map { json -> json?.let { LaresJson.decodeFromString(Me.serializer(), it) } }
    return combine(me, permissions.observeMine()) { current, mine ->
        current?.let { Viewer(it.memberId, it.personId, mine) }
    }
}
