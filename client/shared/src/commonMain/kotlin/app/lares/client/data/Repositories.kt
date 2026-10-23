package app.lares.client.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.lares.client.auth.effectivePermissions
import app.lares.client.db.LocalDb
import app.lares.client.sync.paletteColor
import app.lares.client.sync.toData
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PaletteColors
import app.lares.contract.permissions.EffectivePermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Read side for the UI: every repository observes the typed tables, so it works offline and updates after each sync.

class HouseholdRepository(
    private val localDb: LocalDb,
) {
    fun observe(): Flow<Household?> =
        localDb.db.householdQueries
            .selectAll()
            .asFlow()
            .mapToList(localDb.dispatcher)
            .map { rows -> rows.firstOrNull()?.let { Household(it.id, it.toData()) } }
}

class MembersRepository(
    private val localDb: LocalDb,
) {
    /** Members joined with their persons for display names and colors. */
    fun observeAll(): Flow<List<MemberView>> =
        localDb.db.memberQueries
            .selectAllWithPerson()
            .asFlow()
            .mapToList(localDb.dispatcher)
            .map { rows ->
                rows.map {
                    MemberView(
                        id = it.id,
                        data = MemberData(it.person_id, it.user_id, it.login, it.has_password != 0L),
                        name = it.person_name ?: it.login ?: "",
                        color = paletteColor(it.person_color) ?: PaletteColors.fallbackFor(it.person_id),
                    )
                }
            }
}

class GroupsRepository(
    private val localDb: LocalDb,
) {
    fun observeAll(): Flow<List<Group>> =
        localDb.db.group_entityQueries
            .selectAll()
            .asFlow()
            .mapToList(localDb.dispatcher)
            .map { rows -> rows.map { Group(it.id, it.toData()) } }
}

class PersonsRepository(
    private val localDb: LocalDb,
) {
    fun observeAll(): Flow<List<Person>> =
        localDb.db.personQueries
            .selectAll()
            .asFlow()
            .mapToList(localDb.dispatcher)
            .map { rows -> rows.map { Person(it.id, it.toData()) } }

    fun observe(id: String): Flow<Person?> =
        localDb.db.personQueries
            .selectById(id)
            .asFlow()
            .mapToOneOrNull(localDb.dispatcher)
            .map { row -> row?.let { Person(it.id, it.toData()) } }
}

class TagsRepository(
    private val localDb: LocalDb,
) {
    fun observeAll(includeArchived: Boolean = false): Flow<List<Tag>> {
        val query = if (includeArchived) localDb.db.tagQueries.selectAll() else localDb.db.tagQueries.selectActive()
        return query
            .asFlow()
            .mapToList(localDb.dispatcher)
            .map { rows -> rows.map { Tag(it.id, it.toData()) } }
    }
}

class PermissionsRepository(
    private val localDb: LocalDb,
) {
    /** The current member's permissions, evaluated locally from the synced groups (ADR-0014); works offline. */
    fun observeMine(): Flow<EffectivePermissions> {
        val me =
            localDb.db.local_sessionQueries
                .get()
                .asFlow()
                .mapToOneOrNull(localDb.dispatcher)
                .map { json -> json?.let { LaresJson.decodeFromString(Me.serializer(), it) } }
        val groups =
            localDb.db.group_entityQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher)
                .map { rows -> rows.map { it.id to it.toData() } }
        return combine(me, groups) { current, synced ->
            current?.let { effectivePermissions(it, synced) } ?: EffectivePermissions(isAdmin = false, permissions = emptySet())
        }.distinctUntilChanged()
    }
}
