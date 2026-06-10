package com.vibecode.companion.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the Cursor API key encrypted at rest: AES-256/GCM key in the Android
 * Keystore (non-exportable), ciphertext in DataStore. The plaintext key never
 * touches disk.
 */
class ApiKeyStore(private val context: Context) {

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "companion_api_key_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private val PREF_ENCRYPTED_KEY = stringPreferencesKey("encrypted_api_key")
    }

    /** Emits the decrypted API key, or null when not set (or undecryptable). */
    val apiKey: Flow<String?> = context.companionDataStore.data.map { prefs ->
        prefs[PREF_ENCRYPTED_KEY]?.let { decrypt(it) }
    }

    suspend fun get(): String? = apiKey.first()

    suspend fun save(rawKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(rawKey.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
        context.companionDataStore.edit { it[PREF_ENCRYPTED_KEY] = blob }
    }

    suspend fun clear() {
        context.companionDataStore.edit { it.remove(PREF_ENCRYPTED_KEY) }
    }

    private fun decrypt(blob: String): String? = try {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val ciphertext = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
