package app.lares.server.notifications

import app.lares.contract.api.LaresJson
import app.lares.contract.api.notifications.PushPayload
import app.lares.contract.api.notifications.PushRegistrationRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A request the fake push service received. */
class ReceivedPush(
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/**
 * A push service on a random local port, standing in for ntfy. Every `POST /{topic}` is recorded and answered with
 * [status]. Use [device] to get a registration whose messages the test can decrypt.
 */
class FakePushService : AutoCloseable {
    @Volatile
    var status: HttpStatusCode = HttpStatusCode.Created

    private val received = Channel<ReceivedPush>(Channel.UNLIMITED)

    private val server: EmbeddedServer<*, *> =
        embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            routing {
                post("/{topic}") {
                    val headers =
                        call.request.headers
                            .entries()
                            .associate { it.key.lowercase() to it.value.first() }
                    received.send(ReceivedPush(call.request.local.uri, headers, call.receive<ByteArray>()))
                    call.respond(status, "")
                }
            }
        }.start(wait = false)

    val port: Int =
        runBlocking {
            server.engine
                .resolvedConnectors()
                .first()
                .port
        }

    fun device(
        topic: String,
        language: String = "en",
    ): FakeDevice = FakeDevice("http://127.0.0.1:$port/$topic", language)

    suspend fun next(timeout: Duration = 5.seconds): ReceivedPush = withTimeout(timeout) { received.receive() }

    /** Waits briefly and returns what arrived, if anything. */
    suspend fun nextOrNull(timeout: Duration = 300.milliseconds): ReceivedPush? = withTimeoutOrNull(timeout) { received.receive() }

    override fun close() {
        server.stop(0, 0)
    }
}

/** A UnifiedPush client: endpoint plus the RFC 8291 key pair and auth secret, able to decrypt messages. */
class FakeDevice(
    val endpoint: String,
    val language: String,
) {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = keyPair.public as ECPublicKey
    private val authSecret = ByteArray(16).also { SecureRandom().nextBytes(it) }
    private val publicBytes = uncompressed(publicKey)

    val registration: PushRegistrationRequest
        get() = PushRegistrationRequest(endpoint, b64(publicBytes), b64(authSecret), language)

    /** Decrypts an `aes128gcm` body (RFC 8188 header + RFC 8291 key derivation) and parses the payload. */
    fun decrypt(body: ByteArray): PushPayload {
        val salt = body.copyOfRange(0, 16)
        val idLength = body[20].toInt() and 0xFF
        val serverPublic = body.copyOfRange(21, 21 + idLength)
        val ciphertext = body.copyOfRange(21 + idLength, body.size)

        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private as ECPrivateKey)
        agreement.doPhase(publicKeyFrom(serverPublic), true)
        val ecdhSecret = agreement.generateSecret()

        val keyInfo = "WebPush: info\u0000".toByteArray() + publicBytes + serverPublic
        val ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm\u0000".toByteArray(), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce\u0000".toByteArray(), 12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val padded = cipher.doFinal(ciphertext)
        val plain = padded.copyOfRange(0, padded.indexOfLast { it == 2.toByte() })
        return LaresJson.decodeFromString(PushPayload.serializer(), plain.decodeToString())
    }

    private fun publicKeyFrom(bytes: ByteArray): ECPublicKey {
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), publicKey.params)) as ECPublicKey
    }

    private companion object {
        fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun uncompressed(key: ECPublicKey): ByteArray = byteArrayOf(4) + fixed(key.w.affineX) + fixed(key.w.affineY)

        fun fixed(value: BigInteger): ByteArray {
            val bytes = value.toByteArray().takeLast(32).toByteArray()
            return ByteArray(32 - bytes.size) + bytes
        }

        fun hkdf(
            salt: ByteArray,
            ikm: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            val prk = hmac(salt, ikm)
            return hmac(prk, info + byteArrayOf(1)).copyOf(length)
        }

        fun hmac(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    }
}
