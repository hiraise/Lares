package app.lares.integration

import app.lares.client.api.HttpClientCustomizer
import app.lares.client.api.LaresApi
import app.lares.client.auth.SessionManager
import app.lares.client.auth.SessionState
import app.lares.client.data.GroupsRepository
import app.lares.client.data.HouseholdRepository
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.PersonsRepository
import app.lares.client.data.PersonsWriter
import app.lares.client.data.TagsRepository
import app.lares.client.data.TagsWriter
import app.lares.client.db.LocalDb
import app.lares.client.di.AppScope
import app.lares.client.di.jvmPlatformModule
import app.lares.client.di.sharedModule
import app.lares.client.sync.LocalWriter
import app.lares.client.sync.SyncConfig
import app.lares.client.sync.SyncEngine
import app.lares.contract.api.LaresJson
import app.lares.contract.api.admin.CreateMemberRequest
import app.lares.contract.api.auth.DeviceInfo
import app.lares.contract.entities.GroupData
import app.lares.contract.entities.HouseholdData
import app.lares.contract.entities.MemberData
import app.lares.contract.entities.PersonData
import app.lares.contract.entities.TagData
import app.lares.contract.sync.EntityTypes
import app.lares.server.AppDeps
import app.lares.server.module
import app.lares.server.testing.ADMIN_LOGIN
import app.lares.server.testing.ADMIN_PASSWORD
import app.lares.server.testing.ADULTS
import app.lares.server.testing.KIDS
import app.lares.server.testing.MEMBER_PASSWORD
import app.lares.server.testing.TEST_SETUP_CODE
import app.lares.server.testing.TestClock
import app.lares.server.testing.testDeps
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.Url
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The real server on a random port, against the shared Testcontainers database (emptied per server). */
class TestServer : AutoCloseable {
    /** Near the real time: clients stamp mutations with their own clock. Fixed afterwards unless a test moves it. */
    val clock = TestClock(Clock.System.now())
    val deps: AppDeps = testDeps(clock)
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = 0) { module(deps) }.start(wait = false)
    val url: String = "http://127.0.0.1:${runBlocking {
        server.engine
            .resolvedConnectors()
            .first()
            .port
    }}"

    override fun close() = server.stop(gracePeriodMillis = 0, timeoutMillis = 2_000)
}

/** Simulated network of one client: switch it off, lose push responses, or run code just before a pull. */
class TestNetwork {
    @Volatile var online = true

    @Volatile var dropPushResponses = false

    /** Called before every pull request is sent; [after] is the request's cursor. */
    @Volatile var beforePull: (suspend (after: Long) -> Unit)? = null

    val requests: MutableList<Url> = CopyOnWriteArrayList()

    /** Cursors of the pulls sent so far; 0 marks a full sync. */
    val pullCursors: List<Long>
        get() = requests.filter { it.encodedPath.endsWith("/sync/changes") }.map { it.parameters["after"]!!.toLong() }

    val customizer =
        HttpClientCustomizer {
            install(
                createClientPlugin("TestNetwork") {
                    onRequest { request, _ ->
                        if (!online) throw IOException("Network is off")
                        val url = request.url.build()
                        requests += url
                        if (url.encodedPath.endsWith("/sync/changes")) beforePull?.invoke(url.parameters["after"]!!.toLong())
                    }
                    onResponse { response ->
                        if (dropPushResponses &&
                            response.call.request.url.encodedPath
                                .endsWith("/sync/mutations")
                        ) {
                            throw IOException("Response lost")
                        }
                    }
                },
            )
        }
}

/** One simulated phone: a complete client core with its own in-memory database, token store and network. */
class TestClient(
    private val serverUrl: String,
    name: String,
) : AutoCloseable {
    val network = TestNetwork()
    private val koin: Koin =
        koinApplication {
            modules(
                sharedModule(jvmPlatformModule(DeviceInfo(name, "test"), network.customizer)),
                module {
                    single {
                        SyncConfig(
                            localWriteDelay = 100.milliseconds,
                            backoffInitial = 300.milliseconds,
                            backoffMax = 2.seconds,
                        )
                    }
                },
            )
        }.koin

    val session: SessionManager = koin.get()
    val engine: SyncEngine = koin.get()
    val api: LaresApi = koin.get()
    val localDb: LocalDb = koin.get()
    val writer: LocalWriter = koin.get()
    val tags: TagsWriter = koin.get()
    val persons: PersonsWriter = koin.get()
    val tagsRepository: TagsRepository = koin.get()
    val personsRepository: PersonsRepository = koin.get()
    val membersRepository: MembersRepository = koin.get()
    val groupsRepository: GroupsRepository = koin.get()
    val householdRepository: HouseholdRepository = koin.get()
    val permissionsRepository: PermissionsRepository = koin.get()

    suspend fun connect() = session.connect(serverUrl)

    suspend fun login(
        login: String,
        password: String,
    ) {
        connect()
        session.login(login, password)
    }

    /** Runs a sync cycle and fails the test if it does not succeed. */
    suspend fun sync() {
        check(engine.syncNow()) { "Sync failed: ${engine.status.value}" }
    }

    suspend fun pendingCount(): Long = localDb.read { sync_outboxQueries.count().executeAsOne() }

    suspend fun tagsNamed(name: String) = tagsRepository.observeAll(includeArchived = true).first().filter { it.data.name == name }

    /** Everything the typed tables hold, as the UI sees it. */
    suspend fun snapshot(): Snapshot =
        Snapshot(
            households = listOfNotNull(householdRepository.observe().first()).associate { it.id to it.data },
            members = membersRepository.observeAll().first().associate { it.id to it.data },
            groups = groupsRepository.observeAll().first().associate { it.id to it.data },
            persons = personsRepository.observeAll().first().associate { it.id to it.data },
            tags = tagsRepository.observeAll(includeArchived = true).first().associate { it.id to it.data },
        )

    /** The server's state for this member: a full pull from 0, decoded with the contract classes. */
    suspend fun serverSnapshot(): Snapshot {
        val changes = mutableListOf<app.lares.contract.sync.Change>()
        var page = api.pull(0)
        changes += page.changes
        while (page.hasMore) {
            page = api.pull(page.cursor, accessVersion = page.accessVersion, epoch = page.epoch)
            changes += page.changes
        }

        fun <T> of(
            type: String,
            serializer: kotlinx.serialization.KSerializer<T>,
        ) = changes
            .filter { it.type == type && !it.deleted }
            .associate { it.id to LaresJson.decodeFromJsonElement(serializer, it.data!!) }
        return Snapshot(
            households = of(EntityTypes.HOUSEHOLD, HouseholdData.serializer()),
            members = of(EntityTypes.MEMBER, MemberData.serializer()),
            groups = of(EntityTypes.GROUP, GroupData.serializer()),
            persons = of(EntityTypes.PERSON, PersonData.serializer()),
            tags = of(EntityTypes.TAG, TagData.serializer()),
        )
    }

    val isLoggedIn: Boolean get() = session.state.value is SessionState.LoggedIn

    override fun close() {
        koin.get<CoroutineScope>(AppScope).cancel()
        koin.get<HttpClient>().close()
        koin.close()
    }
}

data class Snapshot(
    val households: Map<String, HouseholdData>,
    val members: Map<String, MemberData>,
    val groups: Map<String, GroupData>,
    val persons: Map<String, PersonData>,
    val tags: Map<String, TagData>,
) {
    val isEmpty: Boolean get() = households.isEmpty() && members.isEmpty() && groups.isEmpty() && persons.isEmpty() && tags.isEmpty()
}

/**
 * A household with three members on three devices: Anna (administrator), Boris (Adults) and Carol (Kids).
 * Every client has synced once.
 */
class Family(
    val server: TestServer,
) : AutoCloseable {
    val anna = TestClient(server.url, "Anna's phone")
    val boris = TestClient(server.url, "Boris's phone")
    val carol = TestClient(server.url, "Carol's phone")
    val all = listOf(anna, boris, carol)
    lateinit var adultsId: String
    lateinit var kidsId: String
    lateinit var borisMemberId: String
    lateinit var carolMemberId: String

    suspend fun start(): Family {
        anna.connect()
        anna.session.setup(TEST_SETUP_CODE, "The Smiths", "Europe/Moscow", "RUB", "en", "Anna", ADMIN_LOGIN, ADMIN_PASSWORD)
        anna.sync()
        val groups = anna.groupsRepository.observeAll().first()
        adultsId = groups.single { it.data.name == ADULTS }.id
        kidsId = groups.single { it.data.name == KIDS }.id
        borisMemberId = anna.api.createMember(CreateMemberRequest("Boris", "boris", MEMBER_PASSWORD, setOf(adultsId))).memberId
        carolMemberId = anna.api.createMember(CreateMemberRequest("Carol", "carol", MEMBER_PASSWORD, setOf(kidsId))).memberId
        boris.login("boris", MEMBER_PASSWORD)
        carol.login("carol", MEMBER_PASSWORD)
        all.forEach { it.sync() }
        return this
    }

    override fun close() {
        all.forEach(TestClient::close)
        server.close()
    }
}

/** Runs [block] against a fresh server and family. */
fun familyTest(block: suspend Family.() -> Unit) =
    runBlocking {
        Family(TestServer()).use { family ->
            family.start()
            family.block()
        }
    }

/**
 * Invariant 8 (convergence): after every client syncs (twice, so each sees the others' last pushes), their typed tables
 * equal each other and the server state from a pull at 0.
 */
suspend fun assertConverged(clients: List<TestClient>) {
    repeat(2) { clients.forEach { it.sync() } }
    val server = clients.first().serverSnapshot()
    for (client in clients) {
        assertEquals(server, client.snapshot(), "client state differs from the server")
        assertEquals(0, client.pendingCount(), "client has pending mutations")
    }
}

/** Waits until [flow] emits a value matching [predicate]. */
suspend fun <T> Flow<T>.await(
    timeout: Duration = 5.seconds,
    predicate: (T) -> Boolean,
): T = withTimeout(timeout) { first(predicate) }
