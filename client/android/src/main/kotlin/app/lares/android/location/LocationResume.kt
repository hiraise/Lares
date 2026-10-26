package app.lares.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.lares.android.R
import app.lares.client.data.location.LocationDevice
import app.lares.client.data.location.SharingController
import app.lares.client.notifications.NotificationPoster
import app.lares.client.platform.AppStartup
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.notifications.NotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings sharing back after a reboot, an app update, process death or from the periodic sync (architecture/location.md
 * § Android, LOC-001, LOC-012): sharing enabled here and the service not running → start it when allowed, else tell
 * the server why (once per reason) and post «Передача геопозиции остановлена — откройте Lares».
 */
class LocationResume(
    private val context: Context,
    private val controller: () -> SharingController,
    private val device: LocationDevice,
    private val poster: NotificationPoster,
    private val startup: AppStartup,
    private val scope: CoroutineScope,
) {
    suspend fun check() {
        // The process may have been started for this: the saved session must be restored before any call.
        startup.ready.first { it }
        if (LocationService.running.value || !controller().isEnabled()) return
        val status = device.status()
        when {
            !status.fine || !status.background -> report(OffReason.PERMISSION_REVOKED)
            !status.locationOn -> report(OffReason.LOCATION_DISABLED)
            !AndroidLocationTracking.startService(context, LocationService.ACTION_START) -> report(OffReason.STOPPED)
        }
    }

    /** For callers outside coroutines (a refused service start). */
    fun reportStoppedAsync(reason: OffReason) {
        scope.launch { report(reason) }
    }

    private suspend fun report(reason: OffReason) {
        if (controller().reportOff(reason)) {
            poster.show(STOPPED_ID, NotificationChannel.SYSTEM, context.getString(R.string.location_stopped_title), "", null)
        }
    }

    companion object {
        const val STOPPED_ID = "location.stopped"

        /** Precise location; «approximate only» does not count (plan M4-07). */
        fun hasFine(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Precise location also in the background (below API 29 it comes with the foreground permission). */
        fun hasLocationPermissions(context: Context): Boolean =
            hasFine(context) &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                )
    }
}
