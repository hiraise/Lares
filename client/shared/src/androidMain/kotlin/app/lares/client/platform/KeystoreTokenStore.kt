package app.lares.client.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.lares.client.auth.TokenStore
import app.lares.client.auth.Tokens
import app.lares.contract.api.LaresJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Tokens in DataStore Preferences, encrypted with an AES-GCM key that never leaves the Android Keystore
 * (`EncryptedSharedPreferences` is deprecated). If the key is gone (e.g. data restored to another device),
 * the tokens cannot be read and the user logs in again.
 */
class KeystoreTokenStore(
    context: Context,
) : TokenStore {
    private val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { File(context.filesDir, "datastore/lares_tokens.preferences_pb") })

    override suspend fun load(): Tokens? {
        val stored = dataStore.data.first()[TOKENS] ?: return null
        return withContext(Dispatchers.IO) {
            try {
                LaresJson.decodeFromString(Tokens.serializer(), decrypt(stored))
            } catch (_: GeneralSecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    override suspend fun save(tokens: Tokens) {
        val encrypted = withContext(Dispatchers.IO) { encrypt(LaresJson.encodeToString(Tokens.serializer(), tokens)) }
        dataStore.edit { it[TOKENS] = encrypted }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(TOKENS) }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(cipher.iv) + ":" + encoder.encodeToString(cipher.doFinal(plain.encodeToByteArray()))
    }

    private fun decrypt(stored: String): String {
        val decoder = Base64.getDecoder()
        val iv = decoder.decode(stored.substringBefore(':'))
        val data = decoder.decode(stored.substringAfter(':'))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(data).decodeToString()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lares_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val TOKENS = stringPreferencesKey("tokens")
    }
}
