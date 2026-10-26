package app.lares.android.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.CancellationSignal
import app.lares.client.data.location.LocationProvider
import app.lares.client.data.location.RequestQuality
import app.lares.client.data.location.TrackingRequest
import app.lares.contract.api.location.PositionFix
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The platform location API without Google Play Services (ADR-0006): `fused` on API 31+ when the phone has it, else
 * `gps`, plus `passive` in STILL (architecture/location.md § Android). Callbacks arrive on [looper]. Callers check the
 * permission first; a missing one is reported as false, never thrown.
 */
class AndroidLocationSource(
    context: Context,
    private val looper: Looper,
) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val handler = Handler(looper)
    private val listeners = mutableListOf<LocationListenerCompat>()

    /**
     * The platform has a `fused` provider (AOSP has one from API 31). Not on the emulator: its `fused` provider never
     * passes on `geo fix` positions, only `gps` gets them (checked in M4-07).
     */
    val hasFused: Boolean
        get() = manager != null && !isEmulator && LocationManagerCompat.hasProvider(manager, FUSED)

    val locationEnabled: Boolean
        get() = manager != null && LocationManagerCompat.isLocationEnabled(manager)

    /** Replaces the current requests with [request]. False when the platform refused (no permission, no provider). */
    @SuppressLint("MissingPermission")
    fun start(
        request: TrackingRequest,
        onLocation: (Location) -> Unit,
    ): Boolean {
        stop()
        val manager = manager ?: return false
        val platform =
            LocationRequestCompat
                .Builder(request.interval.inWholeMilliseconds)
                .setMinUpdateIntervalMillis(request.minInterval.inWholeMilliseconds)
                .setQuality(
                    when (request.quality) {
                        RequestQuality.HIGH_ACCURACY -> LocationRequestCompat.QUALITY_HIGH_ACCURACY
                        RequestQuality.BALANCED -> LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY
                    },
                ).build()
        val providers = listOfNotNull(providerName(request.provider), PASSIVE.takeIf { request.passive })
        var started = false
        for (provider in providers) {
            if (!LocationManagerCompat.hasProvider(manager, provider)) continue
            val listener = LocationListenerCompat { onLocation(it) }
            try {
                val passiveRequest =
                    if (provider == PASSIVE) {
                        LocationRequestCompat
                            .Builder(LocationRequestCompat.PASSIVE_INTERVAL)
                            .setMinUpdateIntervalMillis(request.minInterval.inWholeMilliseconds)
                            .build()
                    } else {
                        platform
                    }
                LocationManagerCompat.requestLocationUpdates(manager, provider, passiveRequest, listener, looper)
                listeners += listener
                started = started || provider != PASSIVE
            } catch (_: SecurityException) {
                return false
            } catch (_: IllegalArgumentException) {
                // The provider disappeared between the check and the call.
            }
        }
        return started
    }

    // Removing updates needs no permission; lint cannot tell (a revoked permission throws nothing here).
    @SuppressLint("MissingPermission")
    fun stop() {
        val manager = manager ?: return
        listeners.forEach {
            try {
                LocationManagerCompat.removeUpdates(manager, it)
            } catch (_: SecurityException) {
                // Nothing to remove without the permission.
            }
        }
        listeners.clear()
    }

    /**
     * One high-accuracy fix («Обновить», LOC-011) from [provider]; [onResult] gets null after [timeout] or when the
     * platform refuses.
     */
    @SuppressLint("MissingPermission")
    fun currentLocation(
        provider: LocationProvider,
        timeout: Duration,
        onResult: (Location?) -> Unit,
    ) {
        val manager = manager ?: return onResult(null)
        val signal = CancellationSignal()
        var done = false
        val finish = { location: Location? ->
            if (!done) {
                done = true
                onResult(location)
            }
        }
        handler.postDelayed({
            signal.cancel()
            finish(null)
        }, timeout.inWholeMilliseconds)
        try {
            LocationManagerCompat.getCurrentLocation(manager, providerName(provider), signal, { handler.post(it) }) { finish(it) }
        } catch (_: SecurityException) {
            finish(null)
        } catch (_: IllegalArgumentException) {
            finish(null)
        }
    }

    /** `fused` when asked for and present on API 31+, else `gps` (TrackingPolicy decides, this double-checks). */
    fun providerName(provider: LocationProvider): String =
        if (provider == LocationProvider.FUSED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasFused) FUSED else GPS

    companion object {
        /** The Android emulator (ranchu / goldfish kernels). */
        val isEmulator: Boolean = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish"

        const val FUSED = "fused"
        const val GPS = LocationManager.GPS_PROVIDER
        const val PASSIVE = LocationManager.PASSIVE_PROVIDER
    }
}

/** A platform location as a fix for the queue; battery added by the caller. */
fun Location.toFix(
    battery: Int?,
    charging: Boolean?,
): PositionFix =
    PositionFix(
        recordedAt = Instant.fromEpochMilliseconds(time),
        latitude = latitude,
        longitude = longitude,
        accuracyM = if (hasAccuracy()) accuracy.coerceIn(0f, MAX_ACCURACY) else MAX_ACCURACY,
        speedMps = if (hasSpeed()) speed.coerceIn(0f, MAX_SPEED) else null,
        bearingDeg = if (hasBearing()) bearing.coerceIn(0f, MAX_BEARING) else null,
        altitudeM = if (hasAltitude()) altitude else null,
        battery = battery,
        charging = charging,
    )

private const val MAX_ACCURACY = 10_000f
private const val MAX_SPEED = 100f
private const val MAX_BEARING = 360f
