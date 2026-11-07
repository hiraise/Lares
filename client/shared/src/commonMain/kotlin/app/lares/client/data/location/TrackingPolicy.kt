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
 * Battery-aware tracking (ADR-0028, ADR-0030, architecture/location.md § Tracking policy, LOC-007), without platform
 * code: MOVING records a fix every ≥ 30 s or ≥ 50 m; STILL (after 3 minutes within 75 m, widened by the fixes' accuracy)
 * records every fix as a heartbeat and leaves on two fixes in a row ≥ 150 m away; PAUSED records nothing. Starts in
 * MOVING, also after a resume. While moving, the platform request follows the pace (every minute on foot, every 30 s
 * after a fix at ≥ 18 km/h, for a minute) and the phone's power (low battery or battery saver doubles the interval).
 * Times of "now" come from [clock], intervals between fixes from their `recordedAt`.
 */
class TrackingPolicy(
    private val clock: Clock,
) {
    enum class Mode { MOVING, STILL, PAUSED }

    /** [newMode] is set only when the mode changed; [requestChanged]: the same mode needs another platform request. */
    data class Decision(
        val record: Boolean,
        val newMode: Mode? = null,
        val requestChanged: Boolean = false,
    )

    var mode: Mode = Mode.MOVING
        private set

    private var modeSince: Instant = clock.now()
    private var lastRecorded: PositionFix? = null
    private val recent = ArrayDeque<PositionFix>()
    private var stillAnchor: Pair<Double, Double>? = null

    /** MOVING at vehicle speed: shorter intervals keep the track detailed. */
    private var fast = false
    private var lastFastAt: Instant? = null
    private var lastAccurate: PositionFix? = null

    /** Battery at or below [LOW_BATTERY] and not charging, or the phone's battery saver: longer intervals. */
    private var lowPower = false

    /** STILL: the last accurate fix was ≥ [LEAVE_STILL_DISTANCE_M] away; one more leaves STILL. */
    private var awayOnce = false

    /** [powerSave]: the phone's battery saver is on. */
    fun onFix(
        fix: PositionFix,
        powerSave: Boolean = false,
    ): Decision {
        val wasLowPower = lowPower
        lowPower = powerSave || (fix.battery?.let { it <= LOW_BATTERY } == true && fix.charging != true)
        return when (mode) {
            Mode.PAUSED -> Decision(record = false)
            Mode.MOVING -> onMovingFix(fix, powerChanged = lowPower != wasLowPower)
            Mode.STILL -> onStillFix(fix)
        }
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
                val (interval, minInterval) = if (fast) FAST_INTERVAL to FAST_MIN_INTERVAL else MOVING_INTERVAL to MOVING_MIN_INTERVAL
                val factor = if (lowPower) LOW_POWER_FACTOR else 1
                TrackingRequest(provider, interval * factor, minInterval * factor, RequestQuality.HIGH_ACCURACY, passive = false)
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

    /**
     * MOVING: the oldest queued fix is ≥ 30 s old (2 min on low power) or 20 are queued; STILL: anything queued;
     * PAUSED: never.
     */
    fun shouldUpload(
        queueOldest: Instant?,
        queueSize: Long,
        mode: Mode,
    ): Boolean =
        when (mode) {
            Mode.PAUSED -> {
                false
            }

            Mode.STILL -> {
                queueSize > 0
            }

            Mode.MOVING -> {
                val after = if (lowPower) LOW_POWER_UPLOAD_AFTER else UPLOAD_AFTER
                queueSize >= UPLOAD_BATCH || (queueOldest != null && clock.now() - queueOldest >= after)
            }
        }

    private fun onMovingFix(
        fix: PositionFix,
        powerChanged: Boolean,
    ): Decision {
        if (fix.accuracyM > MAX_ACCURACY_M) return Decision(record = false, requestChanged = powerChanged)
        val paceChanged = updatePace(fix)
        val last = lastRecorded
        val record =
            last == null ||
                fix.recordedAt - last.recordedAt >= RECORD_INTERVAL ||
                distance(fix, last) >= RECORD_DISTANCE_M
        if (record) lastRecorded = fix
        val now = clock.now()
        recent.addLast(fix)
        val window = if (lowPower) LOW_POWER_STILL_WINDOW else STILL_WINDOW
        while (recent.isNotEmpty() && recent.first().recordedAt < now - window) recent.removeFirst()
        if (now - modeSince >= window && recent.size >= STILL_MIN_FIXES && isStill(recent)) {
            stillAnchor = mean(recent)
            return Decision(record, switchTo(Mode.STILL))
        }
        return Decision(record, requestChanged = powerChanged || paceChanged)
    }

    /** Fast for [FAST_HOLD] after a fix at ≥ [FAST_SPEED_MPS] (reported, or from the last fix); true when it changed. */
    private fun updatePace(fix: PositionFix): Boolean {
        val previous = lastAccurate
        lastAccurate = fix
        val seconds = previous?.let { (fix.recordedAt - it.recordedAt).inWholeMilliseconds / 1000.0 } ?: 0.0
        val speed = fix.speedMps?.toDouble() ?: if (previous != null && seconds > 0) distance(fix, previous) / seconds else null
        if (speed != null && speed >= FAST_SPEED_MPS) lastFastAt = fix.recordedAt
        val nowFast = lastFastAt?.let { fix.recordedAt - it < FAST_HOLD } ?: false
        val changed = nowFast != fast
        fast = nowFast
        return changed
    }

    /** A single fix far away is indoor noise more often than a departure: it is not recorded until the next confirms it. */
    private fun onStillFix(fix: PositionFix): Decision {
        if (fix.accuracyM > MAX_ACCURACY_M) return Decision(record = false)
        val anchor = stillAnchor
        if (anchor != null && fix.accuracyM <= LEAVE_STILL_ACCURACY_M) {
            val away = Geo.distanceM(anchor.first, anchor.second, fix.latitude, fix.longitude) >= LEAVE_STILL_DISTANCE_M
            if (away && !awayOnce) {
                awayOnce = true
                return Decision(record = false)
            }
            awayOnce = false
            if (away) {
                lastRecorded = fix
                return Decision(record = true, newMode = switchTo(Mode.MOVING))
            }
        }
        lastRecorded = fix
        return Decision(record = true)
    }

    private fun isStill(fixes: Collection<PositionFix>): Boolean {
        val (lat, lon) = mean(fixes)
        return fixes.all { fix ->
            val radius = STILL_RADIUS_M + minOf(fix.accuracyM.toDouble(), STILL_ACCURACY_ALLOWANCE_M)
            Geo.distanceM(lat, lon, fix.latitude, fix.longitude) <= radius && (fix.speedMps?.let { it < STILL_SPEED_MPS } ?: true)
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
        awayOnce = false
        fast = false
        lastFastAt = null
        lastAccurate = null
        return newMode
    }

    companion object {
        const val MAX_ACCURACY_M = 200f
        const val RECORD_DISTANCE_M = 50.0
        val RECORD_INTERVAL = 30.seconds
        val STILL_WINDOW = 3.minutes
        const val STILL_MIN_FIXES = 3
        const val STILL_RADIUS_M = 75.0

        /** Indoors fixes scatter by their accuracy: a fix counts as within the still radius plus up to this much of it. */
        const val STILL_ACCURACY_ALLOWANCE_M = 50.0
        const val STILL_SPEED_MPS = 1f
        const val LEAVE_STILL_DISTANCE_M = 150.0
        const val LEAVE_STILL_ACCURACY_M = 100f
        const val UPLOAD_BATCH = 20L
        val UPLOAD_AFTER = 30.seconds
        val MOVING_INTERVAL = 1.minutes
        val MOVING_MIN_INTERVAL = 30.seconds
        val FAST_INTERVAL = 30.seconds
        val FAST_MIN_INTERVAL = 15.seconds
        const val FAST_SPEED_MPS = 5.0
        val FAST_HOLD = 1.minutes
        const val LOW_BATTERY = 20
        const val LOW_POWER_FACTOR = 2

        /** Enough for [STILL_MIN_FIXES] at the low-power walking interval of 2 minutes. */
        val LOW_POWER_STILL_WINDOW = 5.minutes
        val LOW_POWER_UPLOAD_AFTER = 2.minutes
        val STILL_INTERVAL = 15.minutes
        val STILL_INTERVAL_NO_SENSOR = 5.minutes
        val STILL_MIN_INTERVAL = 5.minutes
        const val FUSED_MIN_SDK = 31
        const val HIGH_ACCURACY_STILL_SDK = 36
    }
}
