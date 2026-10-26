package app.lares.tools.loadsim

import app.lares.contract.api.ErrorCode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** Phases of a run. Statistics cover [STEADY] and [BURST]; [SETUP] and [WARMUP] are recorded but excluded. */
enum class Phase { SETUP, WARMUP, STEADY, BURST }

/** One finished request. [status] is the HTTP status, 101 for an opened WebSocket, 0 for a network error. */
data class Sample(
    val atEpochMs: Long,
    val phase: Phase,
    val endpoint: String,
    val status: Int,
    val millis: Double,
) {
    val failed: Boolean get() = status == 0 || status >= 400
}

/** Collects samples and counters from all devices; safe for concurrent use. */
class Recorder {
    @Volatile
    var phase: Phase = Phase.SETUP

    private val samples = ConcurrentLinkedQueue<Sample>()
    val mutationsSent = AtomicLong()
    val mutationsRejected = AtomicLong()
    val rejectionsByCode = ConcurrentHashMap<ErrorCode, AtomicLong>()
    val wsOpened = AtomicLong()
    val wsClosed = AtomicLong()
    val eventsReceived = AtomicLong()
    val pullResets = AtomicLong()

    // Location (plan M4-05).
    val positionsSent = AtomicLong()
    val positionsAccepted = AtomicLong()
    val uploadsNotSharingHere = AtomicLong()
    val refreshesRequested = AtomicLong()
    val refreshesAnswered = AtomicLong()
    val liveOpened = AtomicLong()
    val liveClosed = AtomicLong()
    val arrivalsSeen = AtomicLong()
    val departuresSeen = AtomicLong()
    val liveMessagesByType = ConcurrentHashMap<String, AtomicLong>()

    fun liveMessage(type: String) {
        liveMessagesByType.computeIfAbsent(type) { AtomicLong() }.incrementAndGet()
    }

    val liveMessages: Long get() = liveMessagesByType.values.sumOf { it.get() }

    fun record(
        endpoint: String,
        status: Int,
        millis: Double,
    ) {
        samples += Sample(System.currentTimeMillis(), phase, endpoint, status, millis)
    }

    fun rejected(code: ErrorCode) {
        mutationsRejected.incrementAndGet()
        rejectionsByCode.computeIfAbsent(code) { AtomicLong() }.incrementAndGet()
    }

    private val notes = AtomicLong()

    /** Prints the first [MAX_NOTES] problems to stderr; the rest are only counted in the statistics. */
    fun note(message: String) {
        val n = notes.incrementAndGet()
        if (n <= MAX_NOTES) System.err.println("loadsim: $message")
        if (n == MAX_NOTES.toLong()) System.err.println("loadsim: further problems are not printed")
    }

    fun samples(): List<Sample> = samples.toList().sortedBy { it.atEpochMs }

    private companion object {
        const val MAX_NOTES = 30
    }
}
