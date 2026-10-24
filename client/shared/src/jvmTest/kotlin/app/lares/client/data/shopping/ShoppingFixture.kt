package app.lares.client.data.shopping

import app.lares.client.api.LaresApi
import app.lares.client.api.createHttpClient
import app.lares.client.auth.InMemoryTokenStore
import app.lares.client.auth.LocalSession
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.TagsRepository
import app.lares.client.data.tasks.ANYA
import app.lares.client.data.tasks.DIMA
import app.lares.client.data.tasks.OLGA
import app.lares.client.data.tasks.TestClock
import app.lares.client.platform.InMemoryServerStore
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.Materializer
import app.lares.client.sync.ShoppingItemAdapter
import app.lares.client.sync.ShoppingListAdapter
import app.lares.client.sync.SyncEngine
import app.lares.client.sync.defaultEntityAdapters
import app.lares.client.sync.testLocalDb
import app.lares.contract.api.LaresJson
import app.lares.contract.api.auth.Me
import app.lares.contract.entities.ShoppingItemData
import app.lares.contract.entities.ShoppingListData
import app.lares.contract.permissions.Permission
import app.lares.contract.sync.EntityTypes
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

val SHOPPER = arrayOf(Permission.SHOPPING_VIEW, Permission.SHOPPING_EDIT, Permission.SHOPPING_MANAGE_LISTS)
val SHOPPING_KID = arrayOf(Permission.SHOPPING_VIEW, Permission.SHOPPING_EDIT)
val SHOPPING_VIEWER = arrayOf(Permission.SHOPPING_VIEW)

/** One offline client core for shopping tests, like `TaskFixture`: starts on 2026-10-21 10:00 UTC. */
class ShoppingFixture(
    start: Instant = Instant.parse("2026-10-21T10:00:00Z"),
) {
    val clock = TestClock(start)
    val localDb = testLocalDb()
    val adapters = defaultEntityAdapters(clock)
    private val api =
        LaresApi(
            createHttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }),
            InMemoryServerStore("http://family.test"),
            InMemoryTokenStore(),
            clock,
        )
    private val engine = SyncEngine(api, localDb, adapters, Materializer(adapters), clock, CoroutineScope(Job()))
    val writer = LocalWriter(localDb, adapters, clock, engine)
    val shoppingWriter = ShoppingWriter(writer, localDb, clock)
    val ticks = MutableStateFlow(start)
    val repository =
        ShoppingRepository(localDb, MembersRepository(localDb), TagsRepository(localDb), PermissionsRepository(localDb), clock, ticks)

    fun setNow(now: Instant) {
        clock.now = now
        ticks.value = now
    }

    suspend fun family() =
        localDb.transaction {
            listOf(OLGA to "Ольга", DIMA to "Дмитрий", ANYA to "Аня").forEachIndexed { i, (id, name) ->
                personQueries.upsert("person-$i", name, null, null, null, null, null, "[]", null)
                memberQueries.upsert(id, "person-$i", null, null, 0)
            }
        }

    suspend fun loginAs(
        memberId: String,
        vararg permissions: Permission,
    ) = localDb.transaction {
        LocalSession.save(
            this,
            Me("u", memberId, "h", "Петровы", "p", "Me", "me", true, isAdmin = false, permissions = permissions.toSet()),
        )
    }

    /** Rows as if they had come from the server. */
    suspend fun putList(
        id: String,
        data: ShoppingListData,
    ) = localDb.transaction { (adapters[EntityTypes.SHOPPING_LIST] as ShoppingListAdapter).upsertTyped(this, id, data) }

    suspend fun putItem(
        id: String,
        data: ShoppingItemData,
    ) = localDb.transaction { (adapters[EntityTypes.SHOPPING_ITEM] as ShoppingItemAdapter).upsertTyped(this, id, data) }

    suspend fun removeListRow(id: String) = localDb.transaction { shopping_listQueries.deleteById(id) }

    suspend fun item(id: String): ShoppingItemData =
        (adapters[EntityTypes.SHOPPING_ITEM] as ShoppingItemAdapter).let { a -> localDb.read { a.selectTyped(this, id)!! } }

    suspend fun items(listId: String) = localDb.read { shopping_itemQueries.selectByList(listId).executeAsList() }

    suspend fun outbox() = localDb.read { sync_outboxQueries.peek(100).executeAsList() }

    suspend fun fields(index: Int): JsonObject = LaresJson.parseToJsonElement(outbox()[index].fields!!).jsonObject
}

fun ShoppingFixture.fieldsOf(json: String?): JsonObject = LaresJson.parseToJsonElement(json!!).jsonObject
