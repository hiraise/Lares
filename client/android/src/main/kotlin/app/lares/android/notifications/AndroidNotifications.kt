package app.lares.android.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.lares.android.MainActivity
import app.lares.android.R
import app.lares.client.notifications.AlarmScheduler
import app.lares.client.notifications.NotificationPermissions
import app.lares.client.notifications.NotificationPoster
import app.lares.client.notifications.NotificationTarget
import kotlin.time.Instant
import app.lares.contract.api.notifications.NotificationChannel as Channel

/** Creates the notification channels (FND-050…052); called at every app start so names follow the UI language. */
fun createNotificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val channels =
        Channel.entries.map { channel ->
            val (name, description, importance) =
                when (channel) {
                    Channel.REMINDERS -> {
                        Triple(R.string.channel_reminders, R.string.channel_reminders_description, NotificationManager.IMPORTANCE_HIGH)
                    }

                    Channel.FAMILY_ACTIVITY -> {
                        Triple(
                            R.string.channel_family_activity,
                            R.string.channel_family_activity_description,
                            NotificationManager.IMPORTANCE_DEFAULT,
                        )
                    }

                    Channel.SYSTEM -> {
                        Triple(R.string.channel_system, R.string.channel_system_description, NotificationManager.IMPORTANCE_LOW)
                    }
                }
            NotificationChannel(channel.id, context.getString(name), importance).apply {
                this.description = context.getString(description)
            }
        }
    manager.createNotificationChannels(channels)
}

/**
 * Shows notifications; a tap opens the app, or the notification's target (plan M1-07). Without the permission
 * (Android 13+) nothing is shown.
 */
class AndroidNotificationPoster(
    private val context: Context,
) : NotificationPoster {
    override fun show(
        id: String,
        channel: Channel,
        title: String,
        body: String,
        target: NotificationTarget?,
    ) {
        if (!canPost(context)) return
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (target != null) {
            // A data URI per notification keeps the PendingIntents (and their extras) apart.
            intent
                .setData(Uri.parse("lares-notification:${Uri.encode(id)}"))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ENTITY_TYPE, target.entityType)
                .putExtra(MainActivity.EXTRA_ENTITY_ID, target.entityId)
        }
        val open =
            PendingIntent.getActivity(
                context,
                if (target == null) 0 else id.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, channel.id)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        try {
            NotificationManagerCompat.from(context).notify(id, 0, notification)
        } catch (_: SecurityException) {
            // The permission was revoked between the check and the call.
        }
    }
}

internal fun canPost(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

class AndroidNotificationPermissions(
    private val context: Context,
) : NotificationPermissions {
    override val needsRuntimePermission: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override fun notificationsAllowed(): Boolean = canPost(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun exactAlarmsAllowed(): Boolean = canScheduleExact(context)

    override fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent =
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

internal fun canScheduleExact(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

/**
 * OS alarms for reminders: exact and allowed while idle when the user granted exact alarms (`SCHEDULE_EXACT_ALARM`,
 * requested through the system settings screen), otherwise inexact while idle (may be a few minutes late).
 */
class AndroidAlarmScheduler(
    private val context: Context,
) : AlarmScheduler {
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)

    override fun set(
        id: String,
        at: Instant,
    ) {
        val manager = alarmManager ?: return
        val time = at.toEpochMilliseconds()
        if (canScheduleExact(context)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent(id))
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pendingIntent(id))
        }
    }

    override fun cancel(id: String) {
        alarmManager?.cancel(pendingIntent(id))
    }

    // The id is in the data URI, so every reminder has its own PendingIntent.
    private fun pendingIntent(id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderAlarmReceiver::class.java)
                .setAction(ReminderAlarmReceiver.ACTION)
                .setData(
                    Uri
                        .Builder()
                        .scheme("lares-reminder")
                        .opaquePart(id)
                        .build(),
                ).putExtra(ReminderAlarmReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** The system language, for notification texts when the app follows the system (per-app locale does not change it). */
fun systemLanguage(): String =
    Resources
        .getSystem()
        .configuration.locales
        .get(0)
        ?.language ?: "en"
