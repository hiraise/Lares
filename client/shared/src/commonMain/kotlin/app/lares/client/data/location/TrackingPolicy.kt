package app.lares.client.data.location

import app.lares.contract.api.location.PositionFix
import app.lares.contract.location.Geo
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Location provider of a request: `fused` on API 31+ when the phone has it, else `gps`. */
enum class LocationProvider { FUSED, GPS }

enum class RequestQuality { HIGH_ACCURACY, BALANCED }

/** What the tracking service asks the platform for in a mode. */
data class TrackingRequest(
    val provider: LocationProvider,
    val interval: Duration,
    val minInterval: Duration,
    val quality: RequestQuality,
    /** Also listen to the `passive` provider (fixes other apps asked for). */
    val passive: Boolean,
)

/**
 * Battery-aware tracking (ADR-0028, architecture/location.md § Tracking policy, LOC-007), without platform code:
 * MOVING records a fix every ≥ 30 s or ≥ 50 m; STILL (after 3 minutes within 75 m) records every fix as a heartbeat;
 * PAUSED records nothing. Starts in MOVING, also after a resume. Times of "now" come from [clock], intervals between
 * fixes from their `recordedAt`.
 */
class TrackingPolicy(
    private val clock: Clock,
) {
    enum class Mode { MOVING, STILL, PAUSED }

    /** [newMode] is set only when the mode changed. */
    data class Decision(
        val record: Boolean,
        val newMode: Mode? = null,
    )

    var mode: Mode = Mode.MOVING
        private set

    private var modeSince: Instant = clock.now()
    private var lastRecorded: PositionFix? = null
    private val recent = ArrayDeque<PositionFix>()
    private var stillAnchor: Pair<Double, Double>? = null

    fun onFix(fix: PositionFix): Decision =
        when (mode) {
            Mode.PAUSED -> Decision(record = false)
            Mode.MOVING -> onMovingFix(fix)
            Mode.STILL -> onStillFix(fix)
        }

    /** The significant-motion trigger fired: MOVING again (no change while paused or moving). */
    fun onSignificantMotion(): Mode? = if (mode == Mode.STILL) switchTo(Mode.MOVING) else null

    fun onPause(): Mode? = if (mode != Mode.PAUSED) switchTo(Mode.PAUSED) else null

    fun onResume(): Mode? = if (mode == Mode.PAUSED) switchTo(Mode.MOVING) else null

    /** «Обновить»: the fresh fix is recorded regardless of the thresholds; the mode does not change. */
    fun onFreshFix(fix: PositionFix) {
        lastRecorded = fix
    }

    /** The platform request for [mode] on Android API [sdk]; null while paused. */
    fun request(
        mode: Mode,
        sdk: Int,
        hasMotionSensor: Boolean,
        hasFused: Boolean = true,
    ): TrackingRequest? {
        val provider = if (sdk >= FUSED_MIN_SDK && hasFused) LocationProvider.FUSED else LocationProvider.GPS
        return when (mode) {
            Mode.PAUSED -> {
                null
            }

            Mode.MOVING -> {
                TrackingRequest(provider, MOVING_INTERVAL, MOVING_MIN_INTERVAL, RequestQuality.HIGH_ACCURACY, passive = false)
            }

            Mode.STILL -> {
                TrackingRequest(
                    provider = provider,
                    interval = if (hasMotionSensor) STILL_INTERVAL else STILL_INTERVAL_NO_SENSOR,
                    minInterval = STILL_MIN_INTERVAL,
                    quality = if (sdk >= HIGH_ACCURACY_STILL_SDK) RequestQuality.HIGH_ACCURACY else RequestQuality.BALANCED,
                    passive = true,
                )
            }
        }
    }

    /** MOVING: the oldest queued fix is ≥ 30 s old or 20 are queued; STILL: anything queued; PAUSED: never. */
    fun shouldUpload(
        queueOldest: Instant?,
        queueSize: Long,
        mode: Mode,
    ): Boolean =
        when (mode) {
            Mode.PAUSED -> false
            Mode.STILL -> queueSize > 0
            Mode.MOVING -> queueSize >= UPLOAD_BATCH || (queueOldest != null && clock.now() - queueOldest >= UPLOAD_AFTER)
        }

    private fun onMovingFix(fix: PositionFix): Decision {
        if (fix.accuracyM > MAX_ACCURACY_M) return Decision(record = false)
        val last = lastRecorded
        val record =
            last == null ||
                fix.recordedAt - last.recordedAt >= RECORD_INTERVAL ||
                distance(fix, last) >= RECORD_DISTANCE_M
        if (record) lastRecorded = fix
        val now = clock.now()
        recent.addLast(fix)
        while (recent.isNotEmpty() && recent.first().recordedAt < now - STILL_WINDOW) recent.removeFirst()
        if (now - modeSince >= STILL_WINDOW && recent.size >= STILL_MIN_FIXES && isStill(recent)) {
            stillAnchor = mean(recent)
            return Decision(record, switchTo(Mode.STILL))
        }
        return Decision(record)
    }

    private fun onStillFix(fix: PositionFix): Decision {
        if (fix.accuracyM > MAX_ACCURACY_M) return Decision(record = false)
        lastRecorded = fix
        val anchor = stillAnchor
        val moved =
            anchor != null &&
                fix.accuracyM <= LEAVE_STILL_ACCURACY_M &&
                Geo.distanceM(anchor.first, anchor.second, fix.latitude, fix.longitude) >= LEAVE_STILL_DISTANCE_M
        return Decision(record = true, newMode = if (moved) switchTo(Mode.MOVING) else null)
    }

    private fun isStill(fixes: Collection<PositionFix>): Boolean {
        val (lat, lon) = mean(fixes)
        return fixes.all { fix ->
            Geo.distanceM(lat, lon, fix.latitude, fix.longitude) <= STILL_RADIUS_M && (fix.speedMps?.let { it < STILL_SPEED_MPS } ?: true)
        }
    }

    private fun mean(fixes: Collection<PositionFix>): Pair<Double, Double> =
        fixes.sumOf { it.latitude } / fixes.size to fixes.sumOf { it.longitude } / fixes.size

    private fun distance(
        a: PositionFix,
        b: PositionFix,
    ): Double = Geo.distanceM(a.latitude, a.longitude, b.latitude, b.longitude)

    private fun switchTo(newMode: Mode): Mode {
        mode = newMode
        modeSince = clock.now()
        recent.clear()
        if (newMode != Mode.STILL) stillAnchor = null
        return newMode
    }

    companion object {
        const val MAX_ACCURACY_M = 200f
        const val RECORD_DISTANCE_M = 50.0
        val RECORD_INTERVAL = 30.seconds
        val STILL_WINDOW = 3.minutes
        const val STILL_MIN_FIXES = 3
        const val STILL_RADIUS_M = 75.0
        const val STILL_SPEED_MPS = 1f
        const val LEAVE_STILL_DISTANCE_M = 150.0
        const val LEAVE_STILL_ACCURACY_M = 100f
        const val UPLOAD_BATCH = 20L
        val UPLOAD_AFTER = 30.seconds
        val MOVING_INTERVAL = 10.seconds
        val MOVING_MIN_INTERVAL = 5.seconds
        val STILL_INTERVAL = 15.minutes
        val STILL_INTERVAL_NO_SENSOR = 5.minutes
        val STILL_MIN_INTERVAL = 5.minutes
        const val FUSED_MIN_SDK = 31
        const val HIGH_ACCURACY_STILL_SDK = 36
    }
}
