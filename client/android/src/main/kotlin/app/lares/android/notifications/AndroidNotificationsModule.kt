package app.lares.android.notifications

import android.content.Context
import app.lares.client.notifications.AlarmScheduler
import app.lares.client.notifications.BackgroundSync
import app.lares.client.notifications.DeviceLanguage
import app.lares.client.notifications.NotificationPermissions
import app.lares.client.notifications.NotificationPoster
import app.lares.client.notifications.PreferredLanguage
import app.lares.client.notifications.PushDistributors
import org.koin.core.module.Module
import org.koin.dsl.module

/** Android implementations of the notification platform (plan M0-09). */
fun androidNotificationsModule(context: Context): Module {
    val app = context.applicationContext
    return module {
        single<NotificationPoster> { AndroidNotificationPoster(app) }
        single<AlarmScheduler> { AndroidAlarmScheduler(app) }
        single<BackgroundSync> { WorkManagerBackgroundSync(app) { get() } }
        single<PushDistributors> { UnifiedPushDistributors(app) }
        single<NotificationPermissions> { AndroidNotificationPermissions(app) }
        single<DeviceLanguage> { PreferredLanguage(get(), ::systemLanguage) }
    }
}
