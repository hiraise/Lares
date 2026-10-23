package app.lares.client.di

import android.content.Context
import android.os.Build
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.lares.client.auth.TokenStore
import app.lares.client.db.LaresDb
import app.lares.client.platform.AppCompatLanguageApplier
import app.lares.client.platform.AppInfo
import app.lares.client.platform.DataStorePreferencesStore
import app.lares.client.platform.DataStoreServerStore
import app.lares.client.platform.KeystoreTokenStore
import app.lares.client.platform.LanguageApplier
import app.lares.client.platform.PreferencesStore
import app.lares.client.platform.ServerStore
import app.lares.contract.api.auth.DeviceInfo
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Android platform: SQLite file database, OkHttp, DataStore-backed stores with Keystore-encrypted tokens,
 * UI preferences and per-app language.
 */
fun androidPlatformModule(context: Context): Module {
    val app = context.applicationContext
    return module {
        single<SqlDriver> { AndroidSqliteDriver(LaresDb.Schema, app, "lares.db") }
        single<CoroutineDispatcher>(DbDispatcher) { Dispatchers.IO }
        single<HttpClientEngine> { OkHttp.create() }
        single<TokenStore> { KeystoreTokenStore(app) }
        single<ServerStore> { DataStoreServerStore(app) }
        single { DeviceInfo(name = "${Build.MANUFACTURER} ${Build.MODEL}".trim(), platform = "android") }
        single<PreferencesStore> { DataStorePreferencesStore(app) }
        single<LanguageApplier> { AppCompatLanguageApplier() }
        single { AppInfo(version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?") }
    }
}
