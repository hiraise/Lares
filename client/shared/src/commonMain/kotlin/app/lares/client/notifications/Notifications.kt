package app.lares.client.notifications

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.lares.client.api.LaresApi
import app.lares.client.db.LocalDb
import app.lares.contract.api.LaresJson
import app.lares.contract.api.notifications.NotificationSettings
import app.lares.contract.api.notifications.NotificationType
import app.lares.contract.api.notifications.PushPayload
import app.lares.contract.api.notifications.PushRegistrationRequest
import app.lares.contract.api.notifications.PushTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException

/**
 * Notification settings of the member (FND-052): stored on the server, mirrored locally so the app can skip local
 * reminders of muted types and show the switches offline.
 */
class NotificationSettingsRepository(
    private val api: LaresApi,
    private val localDb: LocalDb,
) {
    val muted: Flow<Set<NotificationType>> =
        localDb.db.notification_muteQueries
            .all()
            .asFlow()
            .mapToList(localDb.dispatcher)
            .map { keys -> keys.mapNotNull(NotificationType::fromKey).toSet() }

    suspend fun isMuted(type: NotificationType): Boolean =
        localDb.read { notification_muteQueries.all().executeAsList() }.contains(type.key)

    /** Loads the settings from the server into the local mirror. */
    suspend fun refresh() = store(api.notificationSettings())

    suspend fun setMuted(
        type: NotificationType,
        muted: Boolean,
    ) {
        val current = localDb.read { notification_muteQueries.all().executeAsList() }.mapNotNull(NotificationType::fromKey).toSet()
        store(api.setNotificationSettings(NotificationSettings(if (muted) current + type else current - type)))
    }

    suspend fun sendTest(): PushTestResult = api.testPush().result

    private suspend fun store(settings: NotificationSettings) {
        localDb.transaction {
            notification_muteQueries.deleteAll()
            settings.muted.forEach { notification_muteQueries.put(it.key) }
        }
    }
}

/**
 * This device's UnifiedPush endpoint (FND-051): kept locally and sent to the server with the UI language.
 * Sending again is needed when it failed (offline) or when the UI language changed.
 */
class PushEndpoints(
    private val api: LaresApi,
    private val localDb: LocalDb,
    private val language: DeviceLanguage,
) {
    /** From the push service: a new or changed endpoint. Stored first, so a failed upload is retried later. */
    suspend fun onNewEndpoint(
        url: String,
        p256dh: String,
        auth: String,
    ) {
        localDb.transaction { push_endpointQueries.put(url, p256dh, auth) }
        uploadIfNeeded()
    }

    /** Sends the stored endpoint unless the server already has it in the current language. Never throws. */
    suspend fun uploadIfNeeded() {
        val stored = localDb.read { push_endpointQueries.get().executeAsOneOrNull() } ?: return
        val lang = language.current()
        if (stored.registered_language == lang) return
        try {
            api.registerPush(PushRegistrationRequest(stored.url, stored.p256dh, stored.auth, lang))
            localDb.transaction { push_endpointQueries.markRegistered(lang, stored.url) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or logged out: retried on the next app start or login.
        }
    }

    /** The distributor dropped the registration, or the user logged out. Never throws. */
    suspend fun onUnregistered() {
        localDb.transaction { push_endpointQueries.deleteAll() }
        try {
            api.unregisterPush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // After logout the session no longer exists; the server deleted the registration with it.
        }
    }
}

/**
 * Handles a decrypted push message (plan M0-09): `sync` → background sync; `notification` → shown on its type's
 * channel unless muted. Unknown kinds and types (from a newer server) are ignored.
 */
class PushMessageHandler(
    private val poster: NotificationPoster,
    private val backgroundSync: BackgroundSync,
    private val settings: NotificationSettingsRepository,
) {
    suspend fun handle(content: ByteArray) {
        val payload =
            try {
                LaresJson.decodeFromString(PushPayload.serializer(), content.decodeToString())
            } catch (_: SerializationException) {
                return
            } catch (_: IllegalArgumentException) {
                return
            }
        when (payload.kind) {
            PushPayload.KIND_SYNC -> {
                backgroundSync.requestNow()
            }

            PushPayload.KIND_NOTIFICATION -> {
                val type = payload.type ?: return
                val title = payload.title ?: return
                if (settings.isMuted(type)) return
                val id = payload.entityId?.let { "${type.key}:$it" } ?: type.key
                poster.show(id, type.channel, title, payload.body.orEmpty())
            }
        }
    }
}
