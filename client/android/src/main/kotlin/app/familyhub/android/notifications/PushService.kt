package app.familyhub.android.notifications

import android.content.Context
import android.content.pm.PackageManager
import app.familyhub.android.FamilyHubApplication
import app.familyhub.client.di.AppScope
import app.familyhub.client.notifications.PushDistributor
import app.familyhub.client.notifications.PushDistributors
import app.familyhub.client.notifications.PushEndpoints
import app.familyhub.client.notifications.PushMessageHandler
import app.familyhub.client.notifications.PushStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.unifiedpush.android.connector.data.ResolvedDistributor

/**
 * Receives UnifiedPush events (connector 3.x, plan M0-09). The connector decrypts messages (RFC 8291) itself;
 * the endpoint goes to the family server with the public key set.
 */
class FamilyHubPushService : PushService() {
    private val koin get() = FamilyHubApplication.koin
    private val scope get() = koin.get<CoroutineScope>(AppScope)
    private val distributors get() = koin.get<PushDistributors>() as UnifiedPushDistributors

    override fun onNewEndpoint(
        endpoint: PushEndpoint,
        instance: String,
    ) {
        distributors.onRegistered()
        val keys = endpoint.pubKeySet ?: return
        scope.launch { koin.get<PushEndpoints>().onNewEndpoint(endpoint.url, keys.pubKey, keys.auth) }
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        // Messages are always encrypted (versions.md § UnifiedPush); anything else is not from the family server.
        if (!message.decrypted) return
        scope.launch { koin.get<PushMessageHandler>().handle(message.content) }
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        distributors.onFailed()
    }

    override fun onUnregistered(instance: String) {
        distributors.refresh()
        scope.launch { koin.get<PushEndpoints>().onUnregistered() }
    }
}

/** [PushDistributors] on the UnifiedPush connector. No VAPID key is passed: ntfy does not need one. */
class UnifiedPushDistributors(
    private val context: Context,
) : PushDistributors {
    private val state = MutableStateFlow(PushStatus())
    override val status: StateFlow<PushStatus> = state.asStateFlow()

    init {
        refresh()
    }

    override fun register() {
        if (UnifiedPush.getAckDistributor(context) == null) {
            // Several distributors: the user picks one in Settings › Notifications. None: the settings explain ntfy.
            val resolved = UnifiedPush.resolveDefaultDistributor(context) as? ResolvedDistributor.Found ?: return refresh()
            UnifiedPush.saveDistributor(context, resolved.packageName)
        }
        UnifiedPush.register(context)
        refresh()
    }

    override fun select(id: String) {
        UnifiedPush.saveDistributor(context, id)
        UnifiedPush.register(context)
        state.update { it.copy(failed = false) }
        refresh()
    }

    override fun unregister() {
        UnifiedPush.unregister(context)
        refresh()
    }

    override fun refresh() {
        state.update { current ->
            // A failed registration is shown against the chosen distributor, which then needs the user's attention.
            val id = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)?.takeIf { current.failed }
            val available = UnifiedPush.getDistributors(context).filter { it != context.packageName }.map(::distributor)
            current.copy(connected = id?.let(::distributor), available = available, failed = current.failed && id != null)
        }
    }

    internal fun onRegistered() {
        state.update { it.copy(failed = false) }
        refresh()
    }

    internal fun onFailed() {
        state.update { it.copy(failed = true) }
        refresh()
    }

    private fun distributor(packageName: String): PushDistributor {
        val label =
            try {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                packageName
            }
        return PushDistributor(packageName, label)
    }
}
