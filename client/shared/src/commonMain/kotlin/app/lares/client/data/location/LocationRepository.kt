package app.lares.client.data.location

import app.lares.client.api.ApiException
import app.lares.client.api.LaresApi
import app.lares.client.data.MemberView
import app.lares.client.data.MembersRepository
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.minuteTicks
import app.lares.client.db.LocalDb
import app.lares.client.sync.SyncEngine
import app.lares.client.sync.SyncState
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.api.location.AddressResult
import app.lares.contract.api.location.LiveMessage
import app.lares.contract.api.location.LocationConfig
import app.lares.contract.api.location.LocationNow
import app.lares.contract.api.location.LocationRuleInput
import app.lares.contract.api.location.LocationRules
import app.lares.contract.api.location.LocationWatchers
import app.lares.contract.api.location.MemberLocation
import app.lares.contract.api.location.PutLocationRulesRequest
import app.lares.contract.api.location.SharingStatus
import app.lares.contract.entities.PaletteColors
import app.lares.contract.location.LocationLimits
import app.lares.contract.location.Stops
import app.lares.contract.permissions.Permission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.io.IOException
import kotlin.math.round
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Timings of the map data (architecture/location.md § Core), like `SyncConfig`. Tests shorten them. */
data class LocationTimings(
    val backoffInitial: Duration = 2.seconds,
    val backoffMax: Duration = 1.minutes,
    val pollInterval: Duration = 60.seconds,
    /** The cached snapshot is written at most this often (a `gone` at once). */
    val cacheInterval: Duration = 30.seconds,
)

/**
 * Location data of the family for the map, Today and the person page (ADR-0024). [observeNow] is live while
 * collected: the `/location/live` socket ([live]; reconnects with backoff), `GET /now` polling while the socket is
 * down, and the cached snapshot when offline. Only the last snapshot is kept on the phone; it is deleted when the
 * member loses `location.view` or the server refuses (`FORBIDDEN`), and a `gone` member leaves it at once.
 * [sharing] is this phone's own sharing (`SharingController.observe()`); [ticks] recompute stale and moving every minute.
 */
class LocationRepository(
    private val api: LaresApi,
    private val localDb: LocalDb,
    private val members: MembersRepository,
    private val places: PlacesRepository,
    private val permissions: PermissionsRepository,
    private val syncEngine: SyncEngine,
    scope: CoroutineScope,
    private val clock: Clock,
    private val sharing: Flow<MySharing> = flowOf(MySharing.Off),
    private val ticks: Flow<Instant> = minuteTicks(clock),
    private val live: () -> Flow<LiveMessage> = api::locationLive,
    private val timings: LocationTimings = LocationTimings(),
) {
    private val shared: Flow<MapState> =
        combine(
            remote(),
            combine(members.observeAll(), places.observePlaces(), ::Pair),
            sharing,
            syncEngine.status.map { it.state == SyncState.OFFLINE }.distinctUntilChanged(),
            ticks,
        ) { remote, (allMembers, allPlaces), me, syncOffline, now ->
            MapState(
                members = join(remote.members.values, allMembers, allPlaces, now),
                places = allPlaces,
                me = me,
                connection =
                    when {
                        remote.socket == Socket.UP -> Connection.LIVE
                        syncOffline || (remote.socketIoFailed && remote.pollIoFailed) -> Connection.OFFLINE
                        else -> Connection.POLLING
                    },
                updatedAt = remote.updatedAt,
            )
        }.distinctUntilChanged().shareIn(scope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), replay = 1)

    private val addressCache = mutableMapOf<Pair<Double, Double>, String?>()
    private val addressLock = Mutex()
    private var configLoaded: LocationConfig? = null

    /** The map state; one socket for all collectors, closed 5 s after the last one stops. */
    fun observeNow(): Flow<MapState> = shared

    /** «Обновить» (LOC-011). */
    suspend fun refresh(memberId: String): Result<Unit> = apiResult { api.requestLocationRefresh(memberId) }

    /** The member's day (household-local [date]) with the stops found in it; `FORBIDDEN` for a CURRENT level. */
    suspend fun history(
        memberId: String,
        date: LocalDate,
    ): Result<DayHistory> =
        apiResult {
            val history = api.history(memberId, date)
            val known = localDb.read { placeQueries.selectAll().executeAsList().map { it.id to it.toData() } }
            val ongoingAfter = clock.now() - LocationLimits.STALE_MINUTES.minutes
            DayHistory(memberId, history.date, history.points, Stops.detect(history.points, known, ongoingAfter))
        }

    /**
     * The street address of a shown position (LOC-014), or null when addresses are off, nothing was found or the
     * server cannot be reached. Cached in memory by coordinates rounded to 4 decimals (as the server does).
     */
    suspend fun address(
        latitude: Double,
        longitude: Double,
    ): String? {
        val key = round4(latitude) to round4(longitude)
        addressLock.withLock { if (key in addressCache) return addressCache[key] }
        if (config()?.addresses != true) return null
        val label =
            try {
                api.address(key.first, key.second).label
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        addressLock.withLock { addressCache[key] = label }
        return label
    }

    suspend fun search(query: String): Result<List<AddressResult>> = apiResult { api.searchAddress(query).results }

    /**
     * The map style, address switch and history period: from the server once per app run ([reload] after an
     * administrator changed the period), kept in `location_pref` for offline use; null before the first load.
     */
    suspend fun config(reload: Boolean = false): LocationConfig? {
        if (!reload) configLoaded?.let { return it }
        val fresh =
            try {
                api.locationConfig()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        if (fresh != null) {
            configLoaded = fresh
            localDb.transaction { location_prefQueries.put(CONFIG_KEY, LaresJson.encodeToString(LocationConfig.serializer(), fresh)) }
            return fresh
        }
        return localDb
            .read { location_prefQueries.get(CONFIG_KEY).executeAsOneOrNull() }
            ?.let { runCatching { LaresJson.decodeFromString(LocationConfig.serializer(), it) }.getOrNull() }
    }

    suspend fun watchers(): Result<LocationWatchers> = apiResult { api.watchers() }

    suspend fun rules(): Result<LocationRules> = apiResult { api.locationRules() }

    suspend fun saveRules(rules: List<LocationRuleInput>): Result<Unit> = apiResult { api.putLocationRules(PutLocationRulesRequest(rules)) }

    private enum class Socket { CONNECTING, UP, DOWN }

    private data class Remote(
        val members: Map<String, MemberLocation>,
        val updatedAt: Instant?,
        val socket: Socket = Socket.CONNECTING,
        val socketIoFailed: Boolean = false,
        val pollIoFailed: Boolean = false,
    )

    /** The server's view of the family while collected, starting from the cached snapshot. */
    private fun remote(): Flow<Remote> =
        channelFlow {
            val cached = loadCache()
            val state = MutableStateFlow(Remote(cached?.members?.associateBy { it.memberId }.orEmpty(), cached?.serverTime))
            var lastCacheWrite: Instant? = null
            var dirty = false
            // The socket, the poll and the permission watcher change the state and the cache from parallel coroutines.
            val lock = Mutex()

            suspend fun storeLocked(force: Boolean) {
                val now = clock.now()
                if (!force && lastCacheWrite?.let { now - it < timings.cacheInterval } == true) return
                writeCache(state.value)
                lastCacheWrite = now
                dirty = false
            }

            suspend fun apply(message: LiveMessage) =
                lock.withLock {
                    when (message.type) {
                        LiveMessage.SNAPSHOT -> {
                            val now = message.now ?: return@withLock
                            state.update { it.copy(members = now.members.associateBy { m -> m.memberId }, updatedAt = now.serverTime) }
                            dirty = true
                            storeLocked(force = false)
                        }

                        LiveMessage.MEMBER -> {
                            val member = message.member ?: return@withLock
                            state.update { it.copy(members = it.members + (member.memberId to member), updatedAt = clock.now()) }
                            dirty = true
                            storeLocked(force = false)
                        }

                        LiveMessage.GONE -> {
                            val id = message.memberId ?: return@withLock
                            state.update { it.copy(members = it.members - id) }
                            storeLocked(force = true)
                        }

                        else -> {
                            Unit
                        }
                    }
                }

            /** `FORBIDDEN` or no `location.view`: nothing hidden stays on the phone. */
            suspend fun forbidden() =
                lock.withLock {
                    state.update { it.copy(members = emptyMap(), updatedAt = null) }
                    localDb.transaction { location_snapshotQueries.remove(SNAPSHOT_KEY) }
                    dirty = false
                }

            launch { state.collect { send(it) } }
            launch {
                permissions.observeMine().map { it.has(Permission.LOCATION_VIEW) }.distinctUntilChanged().collect { canView ->
                    if (!canView) forbidden()
                }
            }
            launch {
                var backoff = timings.backoffInitial
                while (true) {
                    try {
                        live().collect { message ->
                            if (state.value.socket != Socket.UP) state.update { it.copy(socket = Socket.UP, socketIoFailed = false) }
                            backoff = timings.backoffInitial
                            apply(message)
                        }
                        state.update { it.copy(socket = Socket.DOWN) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ApiException) {
                        if (e.code == ErrorCode.FORBIDDEN) forbidden()
                        state.update { it.copy(socket = Socket.DOWN, socketIoFailed = false) }
                    } catch (_: IOException) {
                        state.update { it.copy(socket = Socket.DOWN, socketIoFailed = true) }
                    } catch (_: Exception) {
                        state.update { it.copy(socket = Socket.DOWN, socketIoFailed = false) }
                    }
                    delay(backoff)
                    backoff = minOf(backoff * 2, timings.backoffMax)
                }
            }
            launch {
                state.map { it.socket == Socket.DOWN }.distinctUntilChanged().collectLatest { down ->
                    while (down) {
                        try {
                            val now = api.locationNow()
                            apply(LiveMessage(LiveMessage.SNAPSHOT, now = now))
                            state.update { it.copy(pollIoFailed = false) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ApiException) {
                            if (e.code == ErrorCode.FORBIDDEN) forbidden()
                            state.update { it.copy(pollIoFailed = false) }
                        } catch (_: IOException) {
                            state.update { it.copy(pollIoFailed = true) }
                        } catch (_: Exception) {
                            state.update { it.copy(pollIoFailed = false) }
                        }
                        delay(timings.pollInterval)
                    }
                }
            }
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { lock.withLock { if (dirty) storeLocked(force = true) } }
            }
        }

    private suspend fun loadCache(): LocationNow? =
        localDb
            .read { location_snapshotQueries.get(SNAPSHOT_KEY).executeAsOneOrNull() }
            ?.let { runCatching { LaresJson.decodeFromString(LocationNow.serializer(), it.json) }.getOrNull() }

    private suspend fun writeCache(remote: Remote) {
        localDb.transaction {
            if (remote.members.isEmpty()) {
                location_snapshotQueries.remove(SNAPSHOT_KEY)
            } else {
                val now = LocationNow(remote.members.values.toList(), remote.updatedAt ?: clock.now())
                location_snapshotQueries.put(SNAPSHOT_KEY, LaresJson.encodeToString(LocationNow.serializer(), now), clock.now().toString())
            }
        }
    }

    private fun join(
        locations: Collection<MemberLocation>,
        allMembers: List<MemberView>,
        allPlaces: List<Place>,
        now: Instant,
    ): List<MemberOnMap> {
        val byId = allMembers.associateBy { it.id }
        val placesById = allPlaces.associateBy { it.id }
        return locations
            .map { location ->
                val member = byId[location.memberId]
                val last = location.last
                MemberOnMap(
                    memberId = location.memberId,
                    personId = member?.data?.personId,
                    name = member?.name.orEmpty(),
                    color = member?.color ?: PaletteColors.fallbackFor(location.memberId),
                    level = location.level,
                    status = location.status,
                    pausedUntil = location.pausedUntil,
                    offReason = location.offReason,
                    last = last,
                    place = location.placeId?.let(placesById::get),
                    placeSince = location.placeSince,
                    stale =
                        location.status == SharingStatus.SHARING &&
                            last != null &&
                            last.recordedAt < now - LocationLimits.STALE_MINUTES.minutes,
                    moving =
                        last != null &&
                            (last.speedMps ?: 0f) >= LocationLimits.MOVING_SPEED_MPS &&
                            now - last.recordedAt < LocationLimits.MOVING_MAX_AGE_MINUTES.minutes,
                )
            }.sortedWith(compareBy({ it.name.lowercase() }, { it.memberId }))
    }

    private suspend fun <T> apiResult(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun round4(value: Double): Double = round(value * ROUNDING) / ROUNDING

    private companion object {
        const val SNAPSHOT_KEY = "now"
        const val CONFIG_KEY = "config"
        const val SHARE_TIMEOUT_MS = 5_000L
        const val ROUNDING = 10_000.0
    }
}
