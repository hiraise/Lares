package app.lares.android.location

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.HandlerThread
import android.os.IBinder
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.lares.android.LaresApplication
import app.lares.android.MainActivity
import app.lares.android.R
import app.lares.android.notifications.LOCATION_CHANNEL_ID
import app.lares.android.notifications.canPost
import app.lares.client.auth.SessionManager
import app.lares.client.auth.SessionState
import app.lares.client.data.PermissionsRepository
import app.lares.client.data.location.MySharing
import app.lares.client.data.location.PositionQueue
import app.lares.client.data.location.PositionUploader
import app.lares.client.data.location.SharingController
import app.lares.client.data.location.TrackingPolicy
import app.lares.client.notifications.NotificationPoster
import app.lares.client.platform.AppStartup
import app.lares.contract.api.location.OffReason
import app.lares.contract.api.notifications.NotificationChannel
import app.lares.contract.permissions.Permission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Location sharing while it is on (ADR-0028, architecture/location.md § Android, LOC-001, LOC-002, LOC-007): a
 * `location` foreground service with the persistent notification «Вы делитесь геопозицией». It drives [TrackingPolicy]
 * with the platform providers and the significant-motion trigger, queues fixes ([PositionQueue]) and uploads them
 * itself ([PositionUploader]); a minute tick ends expired pauses and retries uploads. Location callbacks run on a
 * dedicated thread, the rest on `Dispatchers.IO` in the service scope. Every decision about sharing goes through
 * [SharingController].
 */
class LocationService : Service() {
    private val koin get() = LaresApplication.koin
    private val controller by lazy { koin.get<SharingController>() }
    private val queue by lazy { koin.get<PositionQueue>() }
    private val uploader by lazy { koin.get<PositionUploader>() }
    private val poster by lazy { koin.get<NotificationPoster>() }
    private val clock: Clock = Clock.System
    private val policy = TrackingPolicy(clock)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The policy and the platform requests change together. */
    private val lock = Mutex()
    private lateinit var thread: HandlerThread
    private lateinit var source: AndroidLocationSource
    private lateinit var motion: MotionTrigger
    private lateinit var battery: BatteryReader
    private var loop: Job? = null

    @Volatile private var shown = Shown.Sharing

    @Volatile private var pausedUntil: Instant? = null

    @Volatile private var canPause = false

    @Volatile private var memberId: String? = null
    private var staleNotified = false

    private enum class Shown { Sharing, Paused, LocationOff }

    private val providersReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                scope.launch { applyMode() }
            }
        }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { maybeUpload(force = true) }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("lares-location").apply { start() }
        source = AndroidLocationSource(this, thread.looper)
        motion = MotionTrigger(this) { scope.launch { onMotion() } }
        battery = BatteryReader(this)
        ContextCompat.registerReceiver(
            this,
            providersReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        mutableRunning.value = true
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Within 5 s of the start, before anything else.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_FRESH_FIX -> {
                startLoop()
                scope.launch { freshFix() }
            }

            ACTION_PAUSE_CHANGED -> {
                startLoop()
                scope.launch { applyMode() }
            }

            // A null intent is a sticky restart: START.
            else -> {
                startLoop()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mutableRunning.value = false
        scope.cancel()
        source.stop()
        motion.disarm()
        runCatching { unregisterReceiver(providersReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
        thread.quitSafely()
        super.onDestroy()
    }

    private fun enterForeground(): Boolean =
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (an IllegalStateException) or SecurityException (no location
            // permission for a `location` service).
            if (e !is IllegalStateException && e !is SecurityException) throw e
            val reason = if (LocationResume.hasLocationPermissions(this)) OffReason.STOPPED else OffReason.PERMISSION_REVOKED
            LaresApplication.koin.get<LocationResume>().reportStoppedAsync(reason)
            false
        }

    /** Once per service: waits for the session, then tracks until stopped. */
    private fun startLoop() {
        if (loop?.isActive == true) return
        loop =
            scope.launch {
                koin.get<AppStartup>().ready.first { it }
                val session = koin.get<SessionManager>().state.value
                if (session !is SessionState.LoggedIn || !controller.isEnabled()) {
                    stopSelf()
                    return@launch
                }
                memberId = session.me.memberId
                // Sharing runs again: the «stopped» phone notice of LocationResume is out of date.
                NotificationManagerCompat.from(this@LocationService).cancel(LocationResume.STOPPED_ID, 0)
                launch {
                    koin
                        .get<PermissionsRepository>()
                        .observeMine()
                        .map { it.has(Permission.LOCATION_PAUSE_OWN) }
                        .distinctUntilChanged()
                        .collect {
                            canPause = it
                            updateNotification()
                        }
                }
                applyMode()
                maybeUpload(force = true)
                while (isActive) {
                    delay(TICK)
                    controller.checkPause()
                    maybeUpload()
                    checkStaleUploads()
                }
            }
    }

    /** Applies the local pause and the phone's location switch to the policy and the platform requests. */
    private suspend fun applyMode() {
        val paused = controller.isPausedLocally()
        lock.withLock {
            when {
                paused -> {
                    policy.onPause()
                    source.stop()
                    motion.disarm()
                    shown = Shown.Paused
                }

                !source.locationEnabled -> {
                    policy.onResume()
                    source.stop()
                    motion.disarm()
                    shown = Shown.LocationOff
                }

                else -> {
                    policy.onResume()
                    // Location is back: track as moving, so a fix (and with it the state SHARING) comes soon.
                    if (shown == Shown.LocationOff) policy.onSignificantMotion()
                    shown = Shown.Sharing
                    requestUpdates()
                }
            }
        }
        pausedUntil = (controller.observe().first() as? MySharing.Paused)?.until
        updateNotification()
        // Location turned off while sharing (LOC-012): the server learns it; the service waits for it to come back,
        // and the next upload brings the state back.
        if (shown == Shown.LocationOff) controller.reportOff(OffReason.LOCATION_DISABLED)
    }

    /** Called under [lock]: the platform request for the policy's mode. */
    private suspend fun requestUpdates() {
        val request =
            policy.request(policy.mode, Build.VERSION.SDK_INT, motion.available, source.hasFused) ?: return
        val started = source.start(request) { location -> scope.launch { onLocation(location) } }
        if (!started && !LocationResume.hasLocationPermissions(this)) {
            controller.reportOff(OffReason.PERMISSION_REVOKED)
        }
        if (policy.mode == TrackingPolicy.Mode.STILL) motion.arm() else motion.disarm()
    }

    private suspend fun onLocation(location: Location) {
        val fix = location.toFix(battery.level(), battery.charging())
        lock.withLock {
            val decision = policy.onFix(fix)
            if (decision.record) {
                queue.add(fix)
                controller.onFixRecorded(fix.recordedAt)
            }
            if (decision.newMode != null) requestUpdates()
        }
        maybeUpload()
    }

    private suspend fun onMotion() {
        lock.withLock { if (policy.onSignificantMotion() != null) requestUpdates() }
    }

    /** «Обновить» (LOC-011): one high-accuracy fix, recorded and uploaded at once; never while paused here. */
    private suspend fun freshFix() {
        if (controller.isPausedLocally()) return
        val provider =
            policy.request(TrackingPolicy.Mode.MOVING, Build.VERSION.SDK_INT, motion.available, source.hasFused)?.provider ?: return
        source.currentLocation(provider, FRESH_FIX_TIMEOUT) { location ->
            scope.launch {
                if (location != null && !controller.isPausedLocally()) {
                    val fix = location.toFix(battery.level(), battery.charging())
                    lock.withLock { policy.onFreshFix(fix) }
                    queue.add(fix)
                    controller.onFixRecorded(fix.recordedAt)
                }
                uploader.flush(force = true)
            }
        }
    }

    /** Uploads by the policy; an empty queue still sends a pending sharing change. */
    private suspend fun maybeUpload(force: Boolean = false) {
        val size = queue.size()
        if (force || size == 0L || policy.shouldUpload(queue.oldest(), size, policy.mode)) uploader.flush(force)
    }

    /** LOC-008: one phone notice when nothing could be uploaded for an hour while fixes wait. */
    private suspend fun checkStaleUploads() {
        val stale = controller.observe().first() == MySharing.StaleUploads
        if (stale && !staleNotified) {
            poster.show(
                STALE_ID,
                NotificationChannel.SYSTEM,
                getString(R.string.location_stale_title),
                getString(R.string.location_stale_text),
                null,
            )
        }
        staleNotified = stale
    }

    private fun updateNotification() {
        if (!canPost(this)) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification())
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the call.
        }
    }

    private fun notification(): Notification {
        val title =
            when (shown) {
                Shown.Sharing -> {
                    getString(R.string.location_sharing_title)
                }

                Shown.LocationOff -> {
                    getString(R.string.location_off_title)
                }

                Shown.Paused -> {
                    pausedUntil?.let {
                        getString(
                            R.string.location_paused_until_title,
                            DateFormat.getTimeFormat(this).format(Date(it.toEpochMilliseconds())),
                        )
                    } ?: getString(R.string.location_paused_title)
                }
            }
        val open =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(android.net.Uri.parse("lares-notification:location"))
                .apply {
                    memberId?.let {
                        putExtra(MainActivity.EXTRA_ENTITY_TYPE, ENTITY_LOCATION)
                        putExtra(MainActivity.EXTRA_ENTITY_ID, it)
                    }
                }
        val builder =
            NotificationCompat
                .Builder(this, LOCATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_location)
                .setContentTitle(title)
                .setContentText(getString(R.string.app_label))
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(
                    PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
        when {
            shown == Shown.Paused -> {
                builder.addAction(
                    0,
                    getString(R.string.location_action_resume),
                    LocationActionReceiver.pendingIntent(this, LocationActionReceiver.ACTION_RESUME),
                )
            }

            // Members without `location.pause_own` never see «Пауза» (LOC-010).
            canPause -> {
                builder.addAction(
                    0,
                    getString(R.string.location_action_pause_hour),
                    LocationActionReceiver.pendingIntent(this, LocationActionReceiver.ACTION_PAUSE_HOUR),
                )
            }
        }
        return builder.build()
    }

    companion object {
        const val ACTION_START = "app.lares.android.location.START"
        const val ACTION_FRESH_FIX = "app.lares.android.location.FRESH_FIX"
        const val ACTION_PAUSE_CHANGED = "app.lares.android.location.PAUSE_CHANGED"
        const val ACTION_STOP = "app.lares.android.location.STOP"

        /** The notification-tap target type (the Map, M4-08). */
        const val ENTITY_LOCATION = "location"
        private const val NOTIFICATION_ID = 1002
        private const val STALE_ID = "location.stale_uploads"
        private val TICK = 1.minutes
        private val FRESH_FIX_TIMEOUT = 45.seconds

        private val mutableRunning = MutableStateFlow(false)

        /** Whether the service runs in this process. */
        val running: StateFlow<Boolean> = mutableRunning.asStateFlow()

        fun intent(
            context: Context,
            action: String,
        ): Intent = Intent(context, LocationService::class.java).setAction(action)
    }
}
