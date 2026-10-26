package app.lares.client.data.location

// What the location core needs from the platform (architecture/location.md § Core, ADR-0028). Android implements these
// in the app module (M4-07); the JVM build and tests use the no-op versions below.

/** The tracking service: started while sharing is enabled here, told about pauses and «Обновить». */
interface LocationTracking {
    fun start()

    fun stop()

    /** One high-accuracy fix now, recorded and uploaded at once (LOC-011). */
    fun freshFix()

    /** The local pause started or ended; the service re-reads it. */
    fun pauseChanged()
}

/** What the phone allows, for the sharing setup and «Нужен доступ» states. */
data class DeviceLocationStatus(
    val fine: Boolean,
    val background: Boolean,
    val locationOn: Boolean,
    val notifications: Boolean,
    val batteryUnrestricted: Boolean,
    val significantMotion: Boolean,
) {
    /** Sharing can run in the background. */
    val canShare: Boolean get() = fine && background && locationOn
}

/** Permissions and settings of the phone (Android implementation in M4-07). */
interface LocationDevice {
    fun status(): DeviceLocationStatus

    /** The system's name of the background option, e.g. «Разрешить в любом режиме». */
    fun backgroundOptionLabel(): String

    fun openAppSettings()

    fun openLocationSettings()

    fun requestIgnoreBatteryOptimizations()

    fun openBatterySettings()

    val manufacturer: String
}

class NoopLocationTracking : LocationTracking {
    override fun start() = Unit

    override fun stop() = Unit

    override fun freshFix() = Unit

    override fun pauseChanged() = Unit
}

/** A device that allows everything (JVM, tests). */
class AllowAllLocationDevice : LocationDevice {
    override fun status() =
        DeviceLocationStatus(
            fine = true,
            background = true,
            locationOn = true,
            notifications = true,
            batteryUnrestricted = true,
            significantMotion = true,
        )

    override fun backgroundOptionLabel() = "Allow all the time"

    override fun openAppSettings() = Unit

    override fun openLocationSettings() = Unit

    override fun requestIgnoreBatteryOptimizations() = Unit

    override fun openBatterySettings() = Unit

    override val manufacturer: String = "jvm"
}
