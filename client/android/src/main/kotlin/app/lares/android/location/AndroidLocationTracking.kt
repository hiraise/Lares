package app.lares.android.location

import android.content.Context
import androidx.core.content.ContextCompat
import app.lares.client.data.location.LocationTracking
import app.lares.contract.api.location.OffReason

/**
 * [LocationTracking] on [LocationService] (architecture/location.md § Android). [start] runs from the visible app
 * (sharing setup, «Продолжить») and from [LocationResume]; a start the system refuses is reported as STOPPED once.
 * The other calls reach the service only while it runs.
 */
class AndroidLocationTracking(
    private val context: Context,
    private val resume: () -> LocationResume,
) : LocationTracking {
    override fun start() {
        if (!startService(context, LocationService.ACTION_START)) resume().reportStoppedAsync(OffReason.STOPPED)
    }

    override fun stop() {
        context.stopService(LocationService.intent(context, LocationService.ACTION_STOP))
    }

    override fun freshFix() {
        if (LocationService.running.value) startService(context, LocationService.ACTION_FRESH_FIX)
    }

    override fun pauseChanged() {
        if (LocationService.running.value) startService(context, LocationService.ACTION_PAUSE_CHANGED)
    }

    companion object {
        /**
         * Starts the foreground service; false when the system does not allow it now (Android 12+ from the
         * background: ForegroundServiceStartNotAllowedException, an IllegalStateException; or a SecurityException).
         */
        fun startService(
            context: Context,
            action: String,
        ): Boolean =
            try {
                ContextCompat.startForegroundService(context, LocationService.intent(context, action))
                true
            } catch (_: IllegalStateException) {
                false
            } catch (_: SecurityException) {
                false
            }
    }
}
