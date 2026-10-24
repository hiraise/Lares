package app.lares.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.lares.client.App
import app.lares.client.platform.DeepLinkHandler
import app.lares.client.platform.PendingNavigation
import app.lares.client.sync.SyncEngine

/** AppCompat activity so per-app language switching works on every Android version (FND-061). */
class MainActivity : AppCompatActivity() {
    private val koin get() = LaresApplication.koin

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity (rotation, language change) must not handle the launch link or tap again.
        if (savedInstanceState == null) forward(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forward(intent)
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

    private fun forward(intent: Intent?) {
        if (intent == null) return
        val entityType = intent.getStringExtra(EXTRA_ENTITY_TYPE)
        val entityId = intent.getStringExtra(EXTRA_ENTITY_ID)
        if (entityType != null && entityId != null) {
            // A notification tap (plan M1-07); handled once, also when the activity is recreated later.
            koin.get<PendingNavigation>().submit(entityType, entityId)
            intent.removeExtra(EXTRA_ENTITY_TYPE)
            intent.removeExtra(EXTRA_ENTITY_ID)
            return
        }
        if (intent.action != Intent.ACTION_VIEW) return
        intent.data?.toString()?.let { koin.get<DeepLinkHandler>().submit(it) }
    }

    companion object {
        const val EXTRA_ENTITY_TYPE = "app.lares.entityType"
        const val EXTRA_ENTITY_ID = "app.lares.entityId"
    }
}
