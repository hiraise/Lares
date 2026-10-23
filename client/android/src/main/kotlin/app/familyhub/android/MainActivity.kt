package app.familyhub.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.familyhub.client.App
import app.familyhub.client.platform.DeepLinkHandler
import app.familyhub.client.sync.SyncEngine

/** AppCompat activity so per-app language switching works on every Android version (FND-061). */
class MainActivity : AppCompatActivity() {
    private val koin get() = FamilyHubApplication.koin

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, language change) must not handle the launch link again.
        if (savedInstanceState == null) forwardLink(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardLink(intent)
    }

    override fun onStart() {
        super.onStart()
        // The realtime connection is kept only while the app is visible (sync.md).
        koin.get<SyncEngine>().setForeground(true)
    }

    override fun onStop() {
        koin.get<SyncEngine>().setForeground(false)
        super.onStop()
    }

    private fun forwardLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.toString()?.let { koin.get<DeepLinkHandler>().submit(it) }
    }
}
