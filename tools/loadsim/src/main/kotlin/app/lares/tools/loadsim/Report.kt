package app.lares.tools.loadsim

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.math.ceil

/** Latency statistics of one endpoint in one slice of the run (milliseconds). */
data class EndpointStats(
    val endpoint: String,
    val count: Int,
    val errors: Int,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val max: Double,
) {
    companion object {
        const val ALL = "ALL"

        fun of(
            endpoint: String,
            samples: List<Sample>,
        ): EndpointStats {
            val sorted = samples.map { it.millis }.sorted()
            return EndpointStats(
                endpoint = endpoint,
                count = samples.size,
                errors = samples.count { it.failed },
                p50 = percentile(sorted, 0.50),
                p95 = percentile(sorted, 0.95),
                p99 = percentile(sorted, 0.99),
                max = sorted.lastOrNull() ?: 0.0,
            )
        }

        /** Nearest-rank percentile of an ascending list; 0 for an empty list. */
        fun percentile(
            sorted: List<Double>,
            q: Double,
        ): Double {
            if (sorted.isEmpty()) return 0.0
            val rank = ceil(q * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        /** Per endpoint plus an [ALL] row, endpoints sorted by name. WebSocket opens are not latency of the API. */
        fun table(samples: List<Sample>): List<EndpointStats> {
            val api = samples.filterNot { it.endpoint.startsWith("WS ") }
            return samples.groupBy { it.endpoint }.toSortedMap().map { (endpoint, list) -> of(endpoint, list) } +
                of(ALL, api)
        }
    }
}

/** Phase boundaries as wall-clock times, for matching memory samples taken outside loadsim. */
data class PhaseSpan(
    val phase: Phase,
    val startEpochMs: Long,
    val endEpochMs: Long,
)

/** Results of a run: statistics, verdict and output files. */
class Report(
    private val config: Config,
    private val recorder: Recorder,
    private val spans: List<PhaseSpan>,
) {
    private val samples = recorder.samples()
    private val measured = samples.filter { it.phase == Phase.STEADY || it.phase == Phase.BURST }
    private val afterSetup = samples.filter { it.phase != Phase.SETUP }

    val steady = EndpointStats.table(measured.filter { it.phase == Phase.STEADY })
    val burst = EndpointStats.table(measured.filter { it.phase == Phase.BURST })
    val overall = EndpointStats.table(measured)

    /** Failed requests after setup (warm-up included), WebSocket reconnect attempts included. */
    val requestErrorRate: Double = afterSetup.count { it.failed }.toDouble() / afterSetup.size.coerceAtLeast(1)

    val rejectionRate: Double = recorder.mutationsRejected.get().toDouble() / recorder.mutationsSent.get().coerceAtLeast(1)

    val passed: Boolean get() = requestErrorRate <= config.maxErrorRate && rejectionRate <= config.maxErrorRate

    fun write(dir: Path) {
        dir.createDirectories()
        dir.resolve("latency.csv").bufferedWriter().use { out ->
            out.appendLine("epoch_ms,phase,endpoint,status,ms")
            for (s in samples) {
                out.appendLine("${s.atEpochMs},${s.phase.name.lowercase()},${s.endpoint},${s.status},${fmt(s.millis)}")
            }
        }
        dir.resolve("phases.csv").writeText(
            buildString {
                appendLine("phase,start_epoch_ms,end_epoch_ms")
                for (span in spans) appendLine("${span.phase.name.lowercase()},${span.startEpochMs},${span.endEpochMs}")
            },
        )
        dir.resolve("summary.json").writeText(summaryJson().toString() + "\n")
    }

    fun summaryJson(): JsonObject =
        buildJsonObject {
            putJsonObject("config") {
                put("server", config.server)
                put("devices", config.devices)
                put("members", config.members)
                put("duration", config.duration.toString())
                put("warmup", config.warmup.toString())
                put("burst", config.effectiveBurstAt?.let { config.burst.toString() } ?: "off")
                config.effectiveBurstAt?.let { put("burstAt", it.toString()) }
                put("pushInterval", config.pushInterval.toString())
                put("burstInterval", config.burstInterval.toString())
                put("sharingDevices", config.sharing)
                put("positionInterval", config.positionInterval.toString())
                put("mapViewers", config.mapViewers)
                put("places", config.places)
            }
            putJsonArray("phases") {
                for (span in spans) {
                    add(
                        buildJsonObject {
                            put("phase", span.phase.name.lowercase())
                            put("startEpochMs", span.startEpochMs)
                            put("endEpochMs", span.endEpochMs)
                        },
                    )
                }
            }
            putJsonObject("latencyMs") {
                put("steady", statsJson(steady))
                put("burst", statsJson(burst))
                put("overall", statsJson(overall))
            }
            putJsonObject("counters") {
                put("requestsAfterSetup", afterSetup.size)
                put("failedRequestsAfterSetup", afterSetup.count { it.failed })
                put("mutationsSent", recorder.mutationsSent.get())
                put("mutationsRejected", recorder.mutationsRejected.get())
                putJsonObject("rejectionsByCode") {
                    recorder.rejectionsByCode.forEach { (code, count) -> put(code.name, count.get()) }
                }
                put("webSocketsOpened", recorder.wsOpened.get())
                put("webSocketsClosed", recorder.wsClosed.get())
                put("eventsReceived", recorder.eventsReceived.get())
                put("pullResets", recorder.pullResets.get())
            }
            putJsonObject("location") {
                put("positionsSent", recorder.positionsSent.get())
                put("positionsAccepted", recorder.positionsAccepted.get())
                put("uploadsNotSharingHere", recorder.uploadsNotSharingHere.get())
                put("refreshesRequested", recorder.refreshesRequested.get())
                put("refreshesAnswered", recorder.refreshesAnswered.get())
                put("liveSocketsOpened", recorder.liveOpened.get())
                put("liveSocketsClosed", recorder.liveClosed.get())
                put("liveMessages", recorder.liveMessages)
                put("arrivalsSeenLive", recorder.arrivalsSeen.get())
                put("departuresSeenLive", recorder.departuresSeen.get())
                putJsonObject("liveMessagesByType") {
                    recorder.liveMessagesByType.toSortedMap().forEach { (type, count) -> put(type, count.get()) }
                }
            }
            put("requestErrorRate", requestErrorRate)
            put("rejectionRate", rejectionRate)
            put("maxErrorRate", config.maxErrorRate)
            put("passed", passed)
        }

    /** Plain-text summary for the console. */
    fun text(): String =
        buildString {
            appendLine("Latency (ms), measured phases only:")
            appendLine(
                String.format(
                    Locale.ROOT,
                    "%-36s %-7s %7s %6s %8s %8s %8s %8s",
                    "endpoint",
                    "phase",
                    "count",
                    "err",
                    "p50",
                    "p95",
                    "p99",
                    "max",
                ),
            )
            for ((label, rows) in listOf("steady" to steady, "burst" to burst, "all" to overall)) {
                for (row in rows) {
                    if (row.count == 0) continue
                    appendLine(
                        String.format(
                            Locale.ROOT,
                            "%-36s %-7s %7d %6d %8.1f %8.1f %8.1f %8.1f",
                            row.endpoint,
                            label,
                            row.count,
                            row.errors,
                            row.p50,
                            row.p95,
                            row.p99,
                            row.max,
                        ),
                    )
                }
            }
            appendLine(
                "Mutations: ${recorder.mutationsSent.get()} sent, ${recorder.mutationsRejected.get()} rejected" +
                    if (recorder.rejectionsByCode.isEmpty()) "" else " ${recorder.rejectionsByCode.mapValues { it.value.get() }}",
            )
            appendLine(
                "WebSockets: ${recorder.wsOpened.get()} opened, ${recorder.wsClosed.get()} closed; " +
                    "${recorder.eventsReceived.get()} events; ${recorder.pullResets.get()} pull resets",
            )
            appendLine(
                "Location: ${recorder.positionsSent.get()} positions sent, ${recorder.positionsAccepted.get()} accepted, " +
                    "${recorder.uploadsNotSharingHere.get()} not sharing here; ${recorder.refreshesRequested.get()} refreshes " +
                    "requested, ${recorder.refreshesAnswered.get()} answered by an extra fix; live: ${recorder.liveOpened.get()} " +
                    "opened, ${recorder.liveClosed.get()} closed, ${recorder.liveMessages} messages " +
                    "${recorder.liveMessagesByType.toSortedMap().mapValues { it.value.get() }}; seen live: " +
                    "${recorder.arrivalsSeen.get()} arrivals, ${recorder.departuresSeen.get()} departures",
            )
            appendLine(
                "Error rate: requests ${percent(requestErrorRate)}, rejected mutations ${percent(rejectionRate)} " +
                    "(limit ${percent(config.maxErrorRate)}) -> ${if (passed) "PASSED" else "FAILED"}",
            )
        }

    private fun statsJson(rows: List<EndpointStats>): JsonObject =
        buildJsonObject {
            for (row in rows) {
                putJsonObject(row.endpoint) {
                    put("count", row.count)
                    put("errors", row.errors)
                    put("p50", round(row.p50))
                    put("p95", round(row.p95))
                    put("p99", round(row.p99))
                    put("max", round(row.max))
                }
            }
        }

    private fun round(value: Double): Double = Math.round(value * 10) / 10.0

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.2f %%", value * 100)
}
