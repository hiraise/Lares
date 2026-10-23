package app.familyhub.client.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.familyhub.client.api.ApiException
import app.familyhub.client.api.FamilyHubApi
import app.familyhub.client.db.FamilyHubDb
import app.familyhub.client.db.LocalDb
import app.familyhub.client.db.Sync_outbox
import app.familyhub.contract.api.ErrorCode
import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.sync.Change
import app.familyhub.contract.sync.Mutation
import app.familyhub.contract.sync.MutationOp
import app.familyhub.contract.sync.MutationStatus
import app.familyhub.contract.sync.PullResponse
import app.familyhub.contract.sync.PushRequest
import app.familyhub.contract.sync.SyncLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Timings of the sync engine (architecture/sync.md § Sync cycle). Tests shorten them. */
data class SyncConfig(
    /** Local writes are coalesced: the sync starts this long after the first write. */
    val localWriteDelay: Duration = 1.seconds,
    val backoffInitial: Duration = 2.seconds,
    val backoffMax: Duration = 5.minutes,
    val pullLimit: Int = SyncLimits.DEFAULT_PULL_LIMIT,
)

enum class SyncState { SYNCED, PENDING, SYNCING, OFFLINE, ERROR }

/** What the UI shows about sync (FND-043). */
data class SyncStatus(
    val state: SyncState,
    val pendingCount: Long,
    val lastSuccessAt: Instant?,
    val lastError: ErrorCode?,
)

/** A mutation the server rejected (architecture/sync.md: the client reverts and tells the user). */
data class Rejection(
    val id: Long,
    val mutationId: String,
    val type: String,
    val entityId: String,
    val code: ErrorCode,
    val message: String,
    val at: Instant,
)

/**
 * The client half of sync protocol v1: push the outbox, pull changes, full resync on reset. One cycle runs at a time
 * (single flight); triggers are coalesced. Started for a logged-in session, stopped on logout.
 */
class SyncEngine(
    private val api: FamilyHubApi,
    private val localDb: LocalDb,
    private val adapters: EntityAdapters,
    private val materializer: Materializer,
    private val clock: Clock,
    appScope: CoroutineScope,
    private val config: SyncConfig = SyncConfig(),
) {
    private sealed interface Phase {
        data object Idle : Phase

        data object Syncing : Phase

        data class Failed(
            val offline: Boolean,
            val code: ErrorCode?,
        ) : Phase
    }

    private class Session(
        val job: Job,
        val scope: CoroutineScope,
        val triggers: Channel<Unit>,
        val delayedTriggers: Channel<Unit>,
    )

    private val cycleMutex = Mutex()
    private val session = MutableStateFlow<Session?>(null)
    private val phase = MutableStateFlow<Phase>(Phase.Idle)
    private val lastSuccessAt = MutableStateFlow<Instant?>(null)
    private val foreground = MutableStateFlow(false)
    private val connected = MutableStateFlow(false)

    private val pendingCount: Flow<Long> =
        localDb.db.sync_outboxQueries
            .count()
            .asFlow()
            .mapToOne(localDb.dispatcher)

    val status: StateFlow<SyncStatus> =
        combine(phase, pendingCount, lastSuccessAt) { phase, pending, lastSuccess ->
            val state =
                when (phase) {
                    Phase.Syncing -> SyncState.SYNCING
                    is Phase.Failed -> if (phase.offline) SyncState.OFFLINE else SyncState.ERROR
                    Phase.Idle -> if (pending > 0) SyncState.PENDING else SyncState.SYNCED
                }
            SyncStatus(state, pending, lastSuccess, (phase as? Phase.Failed)?.code)
        }.stateIn(appScope, SharingStarted.Eagerly, SyncStatus(SyncState.SYNCED, 0, null, null))

    /** True while the realtime WebSocket is open (foreground only). */
    val realtimeConnected: StateFlow<Boolean> = connected.asStateFlow()

    /** Rejections the user has not seen yet. */
    val rejections: Flow<List<Rejection>> =
        localDb.db.sync_rejectionQueries
            .selectUnseen()
            .asFlow()
            .mapToList(localDb.dispatcher)
            .map { rows ->
                rows.map {
                    Rejection(
                        id = it.id,
                        mutationId = it.mutation_id,
                        type = it.type,
                        entityId = it.entity_id,
                        code = ErrorCode.entries.firstOrNull { code -> code.name == it.code } ?: ErrorCode.UNKNOWN,
                        message = it.message,
                        at = Instant.parse(it.at),
                    )
                }
            }

    suspend fun markRejectionsSeen(ids: Collection<Long>) {
        if (ids.isNotEmpty()) localDb.transaction { sync_rejectionQueries.markSeen(ids) }
    }

    /** Starts the trigger loop and the realtime connection for the current session, then syncs. */
    fun start(scope: CoroutineScope) {
        if (session.value != null) return
        val job = SupervisorJob(scope.coroutineContext[Job])
        val sessionScope = CoroutineScope(scope.coroutineContext + job)
        val created = Session(job, sessionScope, Channel(Channel.CONFLATED), Channel(Channel.CONFLATED))
        if (!session.compareAndSet(null, created)) {
            job.cancel()
            return
        }
        sessionScope.launch {
            val state = localDb.read { SyncStateStore.load(this) }
            lastSuccessAt.value = state.lastSuccessAt
        }
        sessionScope.launch { triggerLoop(created.triggers) }
        sessionScope.launch {
            // Coalesces local writes: one sync per delay window.
            for (unit in created.delayedTriggers) {
                delay(config.localWriteDelay)
                created.triggers.trySend(Unit)
            }
        }
        sessionScope.launch { realtimeLoop() }
        created.triggers.trySend(Unit)
    }

    /** Stops the session's loops and waits for a running cycle to finish. */
    suspend fun stop() {
        val current = session.value ?: return
        if (!session.compareAndSet(current, null)) return
        current.job.cancelAndJoin()
        cycleMutex.withLock { }
        phase.value = Phase.Idle
        lastSuccessAt.value = null
        connected.value = false
    }

    /** Asks for a sync as soon as possible (app start, connectivity regained, push tickle). */
    fun request() {
        session.value?.triggers?.trySend(Unit)
    }

    /** Asks for a sync shortly, coalescing bursts of local writes. */
    fun requestAfterLocalWrite() {
        session.value?.delayedTriggers?.trySend(Unit)
    }

    /** The realtime WebSocket is kept open only in the foreground. */
    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    /** Runs one sync cycle now and waits for it; returns true on success. */
    suspend fun syncNow(): Boolean = cycleMutex.withLock { runCycle() }

    private suspend fun triggerLoop(triggers: Channel<Unit>) {
        var backoff = config.backoffInitial
        for (unit in triggers) {
            if (syncNow()) {
                backoff = config.backoffInitial
            } else {
                // Another trigger (e.g. a local write) ends the wait early.
                withTimeoutOrNull(backoff) { triggers.receive() }
                backoff = minOf(backoff * 2, config.backoffMax)
                triggers.trySend(Unit)
            }
        }
    }

    private suspend fun realtimeLoop() {
        foreground.collectLatest { inForeground ->
            if (!inForeground) {
                connected.value = false
                return@collectLatest
            }
            var backoff = config.backoffInitial
            while (true) {
                try {
                    api.events().collect { event ->
                        connected.value = true
                        backoff = config.backoffInitial
                        val cursor = localDb.read { SyncStateStore.cursor(this) }
                        if (event.seq > cursor) request()
                    }
                } catch (e: CancellationException) {
                    connected.value = false
                    throw e
                } catch (_: Exception) {
                    // Network or handshake failure: retry with backoff; the regular sync reports errors.
                }
                connected.value = false
                delay(backoff)
                backoff = minOf(backoff * 2, config.backoffMax)
            }
        }
    }

    private suspend fun runCycle(): Boolean {
        phase.value = Phase.Syncing
        return try {
            push()
            pull()
            val now = clock.now()
            localDb.transaction { SyncStateStore.saveResult(this, now, null) }
            lastSuccessAt.value = now
            phase.value = Phase.Idle
            true
        } catch (e: CancellationException) {
            phase.value = Phase.Idle
            throw e
        } catch (e: ApiException) {
            // A server error (5xx) is reported as an error too: the network works, the server does not.
            fail(offline = false, e.code)
            false
        } catch (_: IOException) {
            fail(offline = true, null)
            false
        } catch (_: Exception) {
            fail(offline = false, ErrorCode.UNKNOWN)
            false
        }
    }

    private suspend fun fail(
        offline: Boolean,
        code: ErrorCode?,
    ) {
        phase.value = Phase.Failed(offline, code)
        localDb.transaction { SyncStateStore.saveResult(this, null, code) }
    }

    // Push (architecture/sync.md § Sync cycle, step 1).

    private suspend fun push() {
        while (true) {
            val batch = localDb.read { sync_outboxQueries.peek(SyncLimits.MAX_MUTATIONS.toLong()).executeAsList() }
            if (batch.isEmpty()) return
            val response =
                try {
                    api.push(PushRequest(batch.map(::toMutation)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    localDb.transaction { sync_outboxQueries.markAttempt(e.message ?: e::class.simpleName, batch.last().local_seq) }
                    throw e
                }
            val results = response.results.associateBy { it.mutationId }
            var handled = 0
            localDb.transaction {
                for (entry in batch) {
                    val result = results[entry.mutation_id] ?: continue
                    handled++
                    sync_outboxQueries.deleteByMutationId(entry.mutation_id)
                    if (result.status == MutationStatus.REJECTED) {
                        // Revert to server state plus the remaining pending changes, and tell the user.
                        materializer.rematerialize(this, entry.type, entry.entity_id)
                        val error = result.error
                        sync_rejectionQueries.insert(
                            mutation_id = entry.mutation_id,
                            type = entry.type,
                            entity_id = entry.entity_id,
                            code = (error?.code ?: ErrorCode.UNKNOWN).name,
                            message = error?.message ?: "",
                            at = clock.now().toString(),
                        )
                    }
                    // Applied (also duplicate): keep the typed row; the pull brings the authoritative state.
                }
            }
            if (handled == 0) throw IllegalStateException("Push response did not answer any mutation")
        }
    }

    private fun toMutation(entry: Sync_outbox) =
        Mutation(
            mutationId = entry.mutation_id,
            type = entry.type,
            id = entry.entity_id,
            op = if (entry.op == Materializer.OP_DELETE) MutationOp.DELETE else MutationOp.UPSERT,
            fields = entry.fields?.let(Materializer::parseObject),
            createdAt = Instant.parse(entry.created_at),
        )

    // Pull (steps 2 and 3).

    private suspend fun pull() {
        val state = localDb.read { SyncStateStore.load(this) }
        if (state.needsFullSync) return fullResync()
        var cursor = state.cursor
        var accessVersion = state.accessVersion!!
        var epoch = state.epoch!!
        while (true) {
            val page = api.pull(cursor, config.pullLimit, accessVersion, epoch)
            if (page.reset) return fullResync()
            localDb.transaction {
                for (change in page.changes) applyChange(this, change)
                SyncStateStore.savePosition(this, page.cursor, page.accessVersion, page.epoch)
            }
            cursor = page.cursor
            accessVersion = page.accessVersion
            epoch = page.epoch
            if (!page.hasMore) return
        }
    }

    private fun applyChange(
        db: FamilyHubDb,
        change: Change,
    ) {
        if (adapters[change.type] == null) return
        db.sync_shadowQueries.upsert(change.type, change.id, change.seq, change.deleted.toDb(), change.data?.let(::encodeObject))
        materializer.rematerialize(db, change.type, change.id)
    }

    /**
     * Loads every page from `after = 0` into memory, then in one transaction replaces all typed tables and shadows
     * and rematerializes entities with pending outbox entries. The outbox is kept. A reset during the pages restarts.
     */
    private suspend fun fullResync() {
        repeat(FULL_RESYNC_ATTEMPTS) {
            val pages = loadAllPages() ?: return@repeat
            val last = pages.last()
            localDb.transaction {
                adapters.all.forEach { it.deleteAllTyped(this) }
                sync_shadowQueries.deleteAll()
                for (page in pages) {
                    for (change in page.changes) {
                        if (adapters[change.type] == null) continue
                        sync_shadowQueries.upsert(
                            change.type,
                            change.id,
                            change.seq,
                            change.deleted.toDb(),
                            change.data?.let(::encodeObject),
                        )
                    }
                }
                val touched =
                    sync_shadowQueries.selectAllKeys().executeAsList().map { it.type to it.id } +
                        sync_outboxQueries.pendingEntities().executeAsList().map { it.type to it.entity_id }
                for ((type, id) in touched.toSet()) materializer.rematerialize(this, type, id)
                SyncStateStore.savePosition(this, last.cursor, last.accessVersion, last.epoch)
            }
            return
        }
        throw IllegalStateException("Full resync did not finish after $FULL_RESYNC_ATTEMPTS attempts")
    }

    /** All pages of a full sync, or null if the server asked for a reset in between. */
    private suspend fun loadAllPages(): List<PullResponse>? {
        val pages = mutableListOf(api.pull(0, config.pullLimit))
        while (pages.last().hasMore) {
            val previous = pages.last()
            val page = api.pull(previous.cursor, config.pullLimit, previous.accessVersion, previous.epoch)
            if (page.reset) return null
            pages += page
        }
        return pages
    }

    private fun encodeObject(json: JsonObject): String = FamilyHubJson.encodeToString(JsonObject.serializer(), json)

    private companion object {
        const val FULL_RESYNC_ATTEMPTS = 3
    }
}
