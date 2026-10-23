package app.lares.tools.loadsim

import app.lares.contract.Ids
import app.lares.contract.api.ErrorCode
import app.lares.contract.api.LaresJson
import app.lares.contract.entities.PaletteColor
import app.lares.contract.sync.EntityTypes
import app.lares.contract.sync.Mutation
import app.lares.contract.sync.MutationOp
import app.lares.contract.sync.MutationStatus
import app.lares.contract.sync.PullResponse
import app.lares.contract.sync.PushRequest
import app.lares.contract.sync.PushResponse
import app.lares.contract.sync.SyncEvent
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One simulated phone: keeps the event WebSocket open, pushes 1–3 tag/person mutations every push interval (±20 %)
 * followed by a pull, pulls on every `changed` event (single-flight, coalesced like the app), refreshes its tokens.
 */
class Device(
    private val index: Int,
    val api: DeviceApi,
    private val config: Config,
    private val recorder: Recorder,
    private val runId: String,
) {
    private val random = Random(runId.hashCode() * 31 + index)
    private val pullLock = Mutex()
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var cursor = 0L
    private var accessVersion = 0L
    private var epoch = ""

    // Entities this device created; only the push loop touches them. Each device edits only its own rows, so
    // rejections can only come from server-side problems, not from simulated conflicts.
    private val ownTags = mutableListOf<String>()
    private val ownPersons = mutableListOf<String>()
    private var counter = 0

    fun launchIn(scope: CoroutineScope) {
        scope.launch { eventsLoop() }
        scope.launch { for (request in syncRequests) guarded { pull() } }
        scope.launch { pushLoop() }
        scope.launch { refreshLoop(api, config.refreshInterval, random, recorder) }
    }

    /** Pulls until the server has nothing more; a reset starts a full sync. */
    suspend fun pull() =
        pullLock.withLock {
            while (true) {
                val path =
                    if (cursor == 0L) {
                        "/sync/changes?after=0"
                    } else {
                        "/sync/changes?after=$cursor&accessVersion=$accessVersion&epoch=$epoch"
                    }
                val response = api.get<PullResponse>("GET /sync/changes", path)
                if (response.reset) {
                    recorder.pullResets.incrementAndGet()
                    cursor = 0
                    continue
                }
                cursor = response.cursor
                accessVersion = response.accessVersion
                epoch = response.epoch
                if (!response.hasMore) break
            }
        }

    private suspend fun eventsLoop() {
        while (true) {
            try {
                api.events {
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val event = LaresJson.decodeFromString(SyncEvent.serializer(), frame.readText())
                        recorder.eventsReceived.incrementAndGet()
                        if (event.type == SyncEvent.CHANGED || event.seq > cursor) syncRequests.trySend(Unit)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recorder.note("device $index: events: ${e.message}")
            }
            recorder.wsClosed.incrementAndGet()
            delay(1.seconds)
        }
    }

    private suspend fun pushLoop() {
        while (true) {
            pause()
            guarded {
                push()
                pull()
            }
        }
    }

    /** Waits one push interval with ±20 % jitter; a starting burst ends the wait early. */
    private suspend fun pause() {
        val inBurst = recorder.phase == Phase.BURST
        val interval = if (inBurst) config.burstInterval else config.pushInterval
        val due = TimeSource.Monotonic.markNow() + interval * random.nextDouble(0.8, 1.2)
        while (due.hasNotPassedNow()) {
            if (!inBurst && recorder.phase == Phase.BURST) return
            delay(minOf(-due.elapsedNow(), 250.milliseconds))
        }
    }

    private suspend fun push() {
        val planned = List(random.nextInt(1, 4)) { nextMutation() }
        val response =
            api.call<PushRequest, PushResponse>(
                "POST /sync/mutations",
                HttpMethod.Post,
                "/sync/mutations",
                PushRequest(planned.map { it.mutation }),
            )
        recorder.mutationsSent.addAndGet(planned.size.toLong())
        val results = response.results.associateBy { it.mutationId }
        for (item in planned) {
            val result = results[item.mutation.mutationId]
            when {
                result == null -> {
                    recorder.rejected(ErrorCode.UNKNOWN)
                }

                result.status == MutationStatus.REJECTED -> {
                    recorder.rejected(result.error?.code ?: ErrorCode.UNKNOWN)
                    recorder.note("device $index: rejected ${item.mutation.type} ${item.mutation.op}: ${result.error}")
                }

                else -> {
                    item.onApplied()
                }
            }
        }
    }

    private class Planned(
        val mutation: Mutation,
        val onApplied: () -> Unit = {},
    )

    /** 10 % deletes and 40 % updates of own rows, the rest creates; tags and persons alike. */
    private fun nextMutation(): Planned {
        val isTag = random.nextBoolean()
        val own = if (isTag) ownTags else ownPersons
        val type = if (isTag) EntityTypes.TAG else EntityTypes.PERSON
        val roll = random.nextDouble()
        counter++
        return when {
            own.isNotEmpty() && roll < 0.1 -> {
                // Forgotten right away so a later mutation of the same batch cannot touch the tombstone.
                val id = own.removeAt(random.nextInt(own.size))
                Planned(mutation(type, id, MutationOp.DELETE, null))
            }

            own.isNotEmpty() && roll < 0.5 -> {
                val id = own[random.nextInt(own.size)]
                val fields = if (isTag) buildJsonObject { put("color", color()) } else buildJsonObject { put("notes", "Note $counter") }
                Planned(mutation(type, id, MutationOp.UPSERT, fields))
            }

            else -> {
                val id = Ids.newId()
                val fields = if (isTag) newTag() else newPerson()
                Planned(mutation(type, id, MutationOp.UPSERT, fields)) { own += id }
            }
        }
    }

    private fun newTag(): JsonObject =
        buildJsonObject {
            put("name", "LT $runId d$index-$counter")
            put("color", color())
        }

    private fun newPerson(): JsonObject =
        buildJsonObject {
            put("name", "Relative $runId d$index-$counter")
            put("relation", "cousin")
            put("birthYear", random.nextInt(1950, 2020))
            put("birthMonth", random.nextInt(1, 13))
            put("birthDay", random.nextInt(1, 29))
        }

    private fun color(): String = PaletteColor.entries[random.nextInt(PaletteColor.entries.size)].name.lowercase()

    private fun mutation(
        type: String,
        id: String,
        op: MutationOp,
        fields: JsonObject?,
    ) = Mutation(Ids.newId(), type, id, op, fields, Clock.System.now())

    private suspend fun guarded(block: suspend () -> Unit) = guarded(recorder, "device $index", block)
}

/** Runs [block]; failures are already recorded by [DeviceApi], so they are only noted and followed by a short pause. */
suspend fun guarded(
    recorder: Recorder,
    who: String,
    block: suspend () -> Unit,
) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        recorder.note("$who: ${e.message}")
        delay(1.seconds)
    }
}

/** Rotates the tokens of [api] every [interval] (±20 %), as the app does. */
suspend fun refreshLoop(
    api: DeviceApi,
    interval: Duration,
    random: Random,
    recorder: Recorder,
) {
    while (true) {
        delay(interval * random.nextDouble(0.8, 1.2))
        guarded(recorder, "refresh") { api.refresh() }
    }
}
