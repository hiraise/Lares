package app.familyhub.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.familyhub.android.notifications.androidNotificationsModule
import app.familyhub.android.notifications.createNotificationChannels
import app.familyhub.client.di.androidPlatformModule
import app.familyhub.client.di.sharedModule
import app.familyhub.client.notifications.NotificationsCoordinator
import app.familyhub.client.platform.AppStartup
import app.familyhub.client.sync.SyncEngine
import app.familyhub.client.ui.uiModule
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class FamilyHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin =
            startKoin {
                modules(
                    sharedModule(androidPlatformModule(this@FamilyHubApplication)),
                    androidNotificationsModule(this@FamilyHubApplication),
                    uiModule(),
                )
            }.koin
        createNotificationChannels(this)
        // Before the session is restored, so the coordinator sees the login of this process.
        koin.get<NotificationsCoordinator>().start()
        // Restore the saved session right away (offline too); the UI waits for it.
        koin.get<AppStartup>().start()
        watchConnectivity(koin.get())
    }

    /** sync.md trigger "connectivity regained": sync as soon as a network with internet appears. */
    private fun watchConnectivity(syncEngine: SyncEngine) {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        manager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = syncEngine.request()
            },
        )
    }

    companion object {
        val koin get() = GlobalContext.get()
    }
}
