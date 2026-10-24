package app.lares.client.data.shopping

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.TagsRepository
import app.lares.client.data.minuteTicks
import app.lares.client.data.observeViewer
import app.lares.client.db.LocalDb
import app.lares.client.sync.toData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Read side of the shopping module (architecture/shopping.md § Models and views): combines both shopping tables,
 * members, tags, the logged-in member and a minute ticker (so "who is shopping" expires without data changes), and
 * computes the views with [ShoppingViews]. [ticks] is [minuteTicks] of [clock]; tests pass their own.
 */
class ShoppingRepository(
    private val localDb: LocalDb,
    private val members: MembersRepository,
    private val tags: TagsRepository,
    private val permissions: PermissionsRepository,
    clock: Clock,
    private val ticks: Flow<Instant> = minuteTicks(clock),
) {
    private val db get() = localDb.db

    private fun rows() =
        combine(
            db.shopping_listQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
            db.shopping_itemQueries
                .selectAll()
                .asFlow()
                .mapToList(localDb.dispatcher),
        ) { lists, items -> lists.map { it.id to it.toData() } to items.map { it.id to it.toData() } }

    /** All inputs of the views; a new value on every data change and every minute. */
    fun observeInputs(): Flow<ShoppingInputs> =
        combine(
            rows(),
            members.observeAll(),
            tags.observeAll(includeArchived = true),
            observeViewer(localDb, permissions),
            ticks,
        ) { (lists, items), allMembers, allTags, viewer, now ->
            ShoppingInputs(lists, items, allMembers, allTags, viewer, now)
        }

    fun observeLists(): Flow<List<ShoppingListView>> = observeInputs().map(ShoppingViews::lists).distinctUntilChanged()

    /** The list page, filtered by [tagId] when set; null when the list does not exist (deleted). */
    fun observeDetail(
        listId: String,
        tagId: String? = null,
    ): Flow<ShoppingListDetail?> = observeInputs().map { ShoppingViews.detail(it, listId, tagId) }.distinctUntilChanged()

    /** The list for the Today card; null when no list has anything to buy. */
    fun observeActive(): Flow<ActiveList?> = observeInputs().map(ShoppingViews::active).distinctUntilChanged()

    /** The shopping section of a tag page (TAG-004). */
    fun observeForTag(tagId: String): Flow<ShoppingTagView> = observeInputs().map { ShoppingViews.forTag(it, tagId) }.distinctUntilChanged()
}
