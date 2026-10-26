package app.lares.android.location

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat
import app.lares.android.R
import app.lares.android.notifications.canPost
import app.lares.client.data.location.DeviceLocationStatus
import app.lares.client.data.location.LocationDevice

/**
 * Permissions and settings of this phone for sharing (plan M4-07): precise location counts, «approximate only» does
 * not; below API 29 background location comes with the foreground permission.
 */
class AndroidLocationDevice(
    private val context: Context,
) : LocationDevice {
    override fun status(): DeviceLocationStatus {
        val manager = context.getSystemService(LocationManager::class.java)
        return DeviceLocationStatus(
            fine = LocationResume.hasFine(context),
            background = LocationResume.hasLocationPermissions(context),
            locationOn = manager != null && LocationManagerCompat.isLocationEnabled(manager),
            notifications = canPost(context) && NotificationManagerCompat.from(context).areNotificationsEnabled(),
            batteryUnrestricted =
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
            significantMotion = MotionTrigger.present(context),
        )
    }

    override fun backgroundOptionLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.backgroundPermissionOptionLabel.toString()
        } else {
            context.getString(R.string.location_background_option)
        }

    override fun openAppSettings() {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
    }

    override fun openLocationSettings() {
        open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    override fun requestIgnoreBatteryOptimizations() {
        // Allowed for this use case (ADR-0028); some vendor builds lack the dialog, then the list of apps opens.
        if (!open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()))) openBatterySettings()
    }

    override fun openBatterySettings() {
        if (!open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppSettings()
    }

    override val manufacturer: String = Build.MANUFACTURER.orEmpty()

    private fun packageUri(): Uri = Uri.parse("package:${context.packageName}")

    private fun open(intent: Intent): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
}
