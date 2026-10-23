package app.familyhub.client.di

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.familyhub.client.api.HttpClientCustomizer
import app.familyhub.client.auth.InMemoryTokenStore
import app.familyhub.client.auth.TokenStore
import app.familyhub.client.db.FamilyHubDb
import app.familyhub.client.notifications.AlarmScheduler
import app.familyhub.client.notifications.AllowAllNotificationPermissions
import app.familyhub.client.notifications.BackgroundSync
import app.familyhub.client.notifications.DeviceLanguage
import app.familyhub.client.notifications.NoopAlarmScheduler
import app.familyhub.client.notifications.NoopBackgroundSync
import app.familyhub.client.notifications.NoopPushDistributors
import app.familyhub.client.notifications.NotificationPermissions
import app.familyhub.client.notifications.NotificationPoster
import app.familyhub.client.notifications.PushDistributors
import app.familyhub.client.platform.AppInfo
import app.familyhub.client.platform.InMemoryPreferencesStore
import app.familyhub.client.platform.InMemoryServerStore
import app.familyhub.client.platform.LanguageApplier
import app.familyhub.client.platform.PreferencesStore
import app.familyhub.client.platform.ServerStore
import app.familyhub.contract.api.auth.DeviceInfo
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
        single<SqlDriver> { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { FamilyHubDb.Schema.create(it) } }
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
