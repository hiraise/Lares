package app.lares.android.location

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.lares.android.LaresApplication
import app.lares.client.data.location.PauseChoice
import app.lares.client.data.location.SharingController
import app.lares.client.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** «Пауза на 1 час» and «Продолжить» from the persistent notification (LOC-010); the controller tells the service. */
class LocationActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        if (action != ACTION_PAUSE_HOUR && action != ACTION_RESUME) return
        val koin = LaresApplication.koin
        val pending = goAsync()
        koin.get<CoroutineScope>(AppScope).launch {
            try {
                val controller = koin.get<SharingController>()
                if (action == ACTION_PAUSE_HOUR) controller.pause(PauseChoice.HOUR) else controller.resume()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_HOUR = "app.lares.android.location.PAUSE_HOUR"
        const val ACTION_RESUME = "app.lares.android.location.RESUME"

        fun pendingIntent(
            context: Context,
            action: String,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, LocationActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
