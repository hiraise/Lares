package app.lares.server.sync

import app.lares.contract.api.LaresJson
import app.lares.contract.sync.SyncEvent
import app.lares.server.platform.db.AppDatabase
import app.lares.server.platform.db.HouseholdSyncStateTable
import app.lares.server.platform.sync.HouseholdChangeBus
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Realtime events (architecture/sync.md § Realtime events): `hello` with the current seq on connect, then `changed`
 * after commits in the household, at most one message per [debounce] and always with the newest seq.
 */
class SyncEventsService(
    private val db: AppDatabase,
    private val bus: HouseholdChangeBus,
    private val debounce: Duration = 500.milliseconds,
) {
    suspend fun serve(
        session: WebSocketSession,
        householdId: UUID,
    ) = coroutineScope {
        val latest = MutableStateFlow(0L)
        val subscribed = CompletableDeferred<Unit>()
        // Subscribe before reading the current seq, so no commit falls between the two.
        val listener =
            launch {
                bus.events
                    .onSubscription { subscribed.complete(Unit) }
                    .filter { it.householdId == householdId }
                    .collect { event -> latest.update { maxOf(it, event.seq) } }
            }
        subscribed.await()
        val helloSeq =
            db.dbQuery {
                HouseholdSyncStateTable
                    .selectAll()
                    .where { HouseholdSyncStateTable.householdId eq householdId }
                    .single()[HouseholdSyncStateTable.lastSeq]
            }
        session.send(event(SyncEvent.HELLO, helloSeq))
        val sender =
            launch {
                var sent = helloSeq
                // StateFlow is conflated: seqs arriving during the delay collapse into the newest one.
                latest.collect { seq ->
                    if (seq > sent) {
                        session.send(event(SyncEvent.CHANGED, seq))
                        sent = seq
                        delay(debounce)
                    }
                }
            }
        try {
            // Clients send nothing; reading keeps the socket alive until it closes.
            for (frame in session.incoming) Unit
        } finally {
            sender.cancel()
            listener.cancel()
        }
    }

    private fun event(
        type: String,
        seq: Long,
    ) = Frame.Text(LaresJson.encodeToString(SyncEvent.serializer(), SyncEvent(type, seq)))
}
