package app.familyhub.server.notifications

import app.familyhub.contract.api.FamilyHubJson
import app.familyhub.contract.api.notifications.PushPayload
import app.familyhub.server.platform.db.AppDatabase
import com.interaso.webpush.VapidKeys
import com.interaso.webpush.WebPush
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Where to send a push: a row of `push_registration`. */
data class PushTarget(
    val sessionId: UUID,
    val memberId: UUID,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val language: String,
)

enum class PushSendResult { SENT, EXPIRED, FAILED, REFUSED }

/**
 * Sends Web Push messages (RFC 8030) encrypted with RFC 8291 `aes128gcm` and signed with VAPID (RFC 8292) through
 * `com.interaso:webpush`. The VAPID key pair is generated on first use and kept in `instance_secret`.
 * Expired endpoints (404/410) are deleted; other failures are logged and dropped (no retry queue in M0).
 */
class WebPushSender(
    private val db: AppDatabase,
    private val guard: PushEndpointGuard,
    /** VAPID `sub`: an https URL or a mailto: address. */
    private val subject: String,
    private val http: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(10.seconds.toJavaDuration())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
) {
    private val log = KotlinLogging.logger {}
    private val keysLock = Mutex()

    @Volatile
    private var webPush: WebPush? = null

    suspend fun send(
        target: PushTarget,
        payload: PushPayload,
        ttl: Duration,
        urgency: WebPush.Urgency,
    ): PushSendResult {
        val cleartext = FamilyHubJson.encodeToString(PushPayload.serializer(), payload).encodeToByteArray()
        if (cleartext.size > PushPayload.MAX_BYTES) {
            log.error { "Push payload of ${cleartext.size} bytes exceeds ${PushPayload.MAX_BYTES}; not sent" }
            return PushSendResult.FAILED
        }
        val refusal = withContext(Dispatchers.IO) { guard.check(target.endpoint) }
        if (refusal != null) {
            log.warn { "Push endpoint of session ${target.sessionId} refused: $refusal" }
            return PushSendResult.REFUSED
        }
        val status =
            try {
                val webPush = webPush()
                val body = webPush.getBody(cleartext, decodeKey(target.p256dh), decodeKey(target.auth))
                val request =
                    HttpRequest
                        .newBuilder(URI(target.endpoint))
                        .timeout(15.seconds.toJavaDuration())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .apply {
                            webPush.getHeaders(target.endpoint, ttl.inWholeSeconds.toInt(), null, urgency).forEach { (name, value) ->
                                setHeader(name, value)
                            }
                        }.build()
                http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode()
            } catch (e: Exception) {
                log.warn(e) { "Push to session ${target.sessionId} failed" }
                return PushSendResult.FAILED
            }
        return when (status) {
            in 200..299 -> {
                PushSendResult.SENT
            }

            404, 410 -> {
                log.info { "Push endpoint of session ${target.sessionId} expired ($status); registration deleted" }
                deleteRegistration(target)
                PushSendResult.EXPIRED
            }

            else -> {
                log.warn { "Push service answered $status for session ${target.sessionId}" }
                PushSendResult.FAILED
            }
        }
    }

    /** Deletes the registration only if it still points to the same endpoint (the device may have re-registered). */
    private suspend fun deleteRegistration(target: PushTarget) {
        db.dbQuery {
            PushRegistrationTable.deleteWhere {
                (PushRegistrationTable.sessionId eq target.sessionId) and (PushRegistrationTable.endpoint eq target.endpoint)
            }
        }
    }

    private suspend fun webPush(): WebPush =
        webPush ?: keysLock.withLock {
            webPush ?: WebPush(subject, loadOrCreateKeys()).also { webPush = it }
        }

    private suspend fun loadOrCreateKeys(): VapidKeys =
        db.dbQuery {
            fun read(): VapidKeys? {
                val values =
                    InstanceSecretTable
                        .selectAll()
                        .where { InstanceSecretTable.name inList listOf(VAPID_PUBLIC, VAPID_PRIVATE) }
                        .associate { it[InstanceSecretTable.name] to it[InstanceSecretTable.value] }
                val public = values[VAPID_PUBLIC] ?: return null
                val private = values[VAPID_PRIVATE] ?: return null
                return VapidKeys.create(public, private)
            }
            read() ?: run {
                val generated = VapidKeys.generate()
                InstanceSecretTable.insertIgnore {
                    it[name] = VAPID_PUBLIC
                    it[value] = generated.x509PublicKey
                }
                InstanceSecretTable.insertIgnore {
                    it[name] = VAPID_PRIVATE
                    it[value] = generated.pkcs8PrivateKey
                }
                read() ?: error("VAPID keys missing after insert")
            }
        }

    companion object {
        private const val VAPID_PUBLIC = "vapid.public"
        private const val VAPID_PRIVATE = "vapid.private"

        /** Decodes base64url or standard base64, with or without padding. */
        fun decodeKey(text: String): ByteArray =
            Base64.getUrlDecoder().decode(
                text
                    .trim()
                    .replace('+', '-')
                    .replace('/', '_')
                    .trimEnd('='),
            )

        /** VAPID subject: the public URL if it is https (as required by the spec), otherwise a placeholder address. */
        fun subjectFor(publicUrl: String): String = if (publicUrl.startsWith("https://")) publicUrl else "mailto:push@familyhub.invalid"
    }
}
