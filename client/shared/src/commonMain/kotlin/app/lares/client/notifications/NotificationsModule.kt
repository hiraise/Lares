package app.lares.client.notifications

import app.lares.client.di.AppScope
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * The notification core (plan M0-09). The platform module must provide [NotificationPoster], [AlarmScheduler],
 * [BackgroundSync], [PushDistributors], [NotificationPermissions] and [DeviceLanguage] (the JVM module provides
 * no-op versions). A new module per call, like `sharedModule`.
 */
fun notificationsModule(): Module =
    module {
        single { NotificationSettingsRepository(get(), get()) }
        single { PushEndpoints(get(), get(), get()) }
        single { Reminders(get(), get(), get(), getOrNull<Clock>() ?: Clock.System) } bind ReminderScheduler::class
        single { PushMessageHandler(get(), get(), get()) }
        single { NotificationsCoordinator(get(), get(), get(), get(), get(), get(), get(), get(), get(AppScope)) }
    }
