package app.lares.client.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.lares.client.api.HttpClientCustomizer
import app.lares.client.auth.InMemoryTokenStore
import app.lares.client.auth.TokenStore
import app.lares.client.db.LaresDb
import app.lares.client.notifications.AlarmScheduler
import app.lares.client.notifications.AllowAllNotificationPermissions
import app.lares.client.notifications.BackgroundSync
import app.lares.client.notifications.DeviceLanguage
import app.lares.client.notifications.NoopAlarmScheduler
import app.lares.client.notifications.NoopBackgroundSync
import app.lares.client.notifications.NoopPushDistributors
import app.lares.client.notifications.NotificationPermissions
import app.lares.client.notifications.NotificationPoster
import app.lares.client.notifications.PushDistributors
import app.lares.client.platform.AppInfo
import app.lares.client.platform.InMemoryPreferencesStore
import app.lares.client.platform.InMemoryServerStore
import app.lares.client.platform.LanguageApplier
import app.lares.client.platform.PreferencesStore
import app.lares.client.platform.ServerStore
import app.lares.contract.api.auth.DeviceInfo
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * JVM platform: in-memory SQLite and in-memory stores (tests; a file database for desktop comes later), CIO engine,
 * no-op notifications.
 * The in-memory driver has a single connection, so database work runs one task at a time.
 */
fun jvmPlatformModule(
    device: DeviceInfo = DeviceInfo(name = "Desktop", platform = "jvm"),
    customizer: HttpClientCustomizer? = null,
): Module =
    module {
        single<SqlDriver> { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { LaresDb.Schema.create(it) } }
        single<CoroutineDispatcher>(DbDispatcher) { Dispatchers.IO.limitedParallelism(1) }
        single<HttpClientEngine> { CIO.create() }
        single<TokenStore> { InMemoryTokenStore() }
        single<ServerStore> { InMemoryServerStore() }
        single { device }
        single<PreferencesStore> { InMemoryPreferencesStore() }
        single<LanguageApplier> { LanguageApplier { } }
        single { AppInfo(version = "dev") }
        single<NotificationPoster> { NotificationPoster { _, _, _, _ -> } }
        single<AlarmScheduler> { NoopAlarmScheduler() }
        single<BackgroundSync> { NoopBackgroundSync() }
        single<PushDistributors> { NoopPushDistributors() }
        single<NotificationPermissions> { AllowAllNotificationPermissions() }
        single<DeviceLanguage> { DeviceLanguage { "en" } }
        if (customizer != null) single { customizer }
    }
