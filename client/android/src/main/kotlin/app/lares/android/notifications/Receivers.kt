package app.lares.android.notifications

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lares.android.LaresApplication
import app.lares.android.location.LocationResume
import app.lares.client.di.AppScope
import app.lares.client.notifications.ReminderScheduler
import app.lares.client.notifications.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A reminder's alarm went off: show it (FND-050). */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val koin = LaresApplication.koin
        val pending = goAsync()
        koin.get<CoroutineScope>(AppScope).launch {
            try {
                koin.get<Reminders>().fire(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "app.lares.android.REMINDER"
        const val EXTRA_ID = "id"
    }
}

/**
 * Alarms are lost on reboot and app update; they are registered again from the local `reminder` table. Also runs when
 * the user grants exact alarms, so pending reminders switch to exact timing.
 */
class RescheduleRemindersReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in HANDLED) return
        val koin = LaresApplication.koin
        val pending = goAsync()
        koin.get<CoroutineScope>(AppScope).launch {
            try {
                koin.get<ReminderScheduler>().rescheduleAll()
                // Location sharing comes back after a reboot or an update (M4-07).
                if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) koin.get<LocationResume>().check()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val HANDLED =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            )
    }
}
