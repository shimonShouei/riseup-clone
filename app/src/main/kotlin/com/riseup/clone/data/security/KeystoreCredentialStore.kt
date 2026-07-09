package com.riseup.clone.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.riseup.clone.domain.scraper.ScraperCredentials
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [CredentialStore] whose secrets are encrypted at rest under a key that never
 * leaves the Android Keystore.
 *
 * Design (hand-rolled AES/GCM over the AndroidKeyStore, rather than
 * `EncryptedSharedPreferences`): a single 256-bit AES key lives in the
 * `AndroidKeyStore` under [KEY_ALIAS]. The key material is non-exportable and,
 * on devices with a TEE/StrongBox, backed by hardware — the app only ever holds
 * an opaque handle. Each credential blob is serialized ([CredentialCodec]),
 * sealed with a fresh IV ([AesGcmCipher]), and the resulting `iv` + `ciphertext`
 * are the only things persisted, base64-encoded, in an ordinary
 * [SharedPreferences] file keyed by institution. Even with full read access to
 * that file an attacker gets ciphertext they cannot decrypt without the
 * Keystore-resident key.
 *
 * This approach was chosen over `androidx.security:security-crypto` because it
 * (a) adds no dependency (only the platform Keystore + JCE) and (b) keeps the
 * actual crypto in a key-agnostic seam ([AesGcmCipher] / [CredentialCodec]) that
 * is fully unit-testable on the JVM, whereas security-crypto's encryption is
 * entirely Keystore-bound and only exercisable on a device/emulator.
 *
 * Security invariants:
 * - The plaintext credential is never written to disk or logs; only ciphertext
 *   is persisted, and [ScraperCredentials.toString] is redacted.
 * - [clear] deletes the persisted ciphertext for one institution; [clearAll]
 *   also deletes the Keystore key, which cryptographically shreds every
 *   remaining blob (they become undecryptable). This is what sign-out relies on.
 *
 * All disk/Keystore I/O is done on [Dispatchers.IO], consistent with the
 * repositories.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {

    // Application context only — never an Activity — so the store can outlive UI.
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cipher = AesGcmCipher()

    override suspend fun save(
        institution: String,
        credentials: ScraperCredentials,
    ): Unit = withContext(Dispatchers.IO) {
        val sealed = cipher.seal(getOrCreateKey(), CredentialCodec.encode(credentials))
        prefs.edit()
            .putString(ivKey(institution), sealed.iv.toBase64())
            .putString(ciphertextKey(institution), sealed.ciphertext.toBase64())
            .apply()
    }

    override suspend fun load(institution: String): ScraperCredentials? = withContext(Dispatchers.IO) {
        val iv = prefs.getString(ivKey(institution), null)?.fromBase64() ?: return@withContext null
        val ciphertext =
            prefs.getString(ciphertextKey(institution), null)?.fromBase64() ?: return@withContext null
        val plaintext = cipher.open(getOrCreateKey(), AesGcmCipher.Sealed(iv, ciphertext))
        CredentialCodec.decode(plaintext)
    }

    override suspend fun clear(institution: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(ivKey(institution))
            .remove(ciphertextKey(institution))
            .apply()
    }

    override suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
        // Deleting the key renders any lingering ciphertext permanently
        // undecryptable — defense in depth on top of clearing the prefs.
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }

    /**
     * Return the AES key from the AndroidKeyStore, generating (and persisting) it
     * on first use. The key is symmetric, 256-bit, GCM/no-padding, and usable for
     * both encrypt and decrypt. No user-authentication requirement is set: the
     * background sync worker must be able to decrypt without a device unlock.
     */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
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

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private fun ivKey(institution: String) = "${institution}$IV_SUFFIX"

    private fun ciphertextKey(institution: String) = "${institution}$CIPHERTEXT_SUFFIX"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.riseup.clone.credentials"
        const val PREFS_NAME = "secure_credentials"
        const val IV_SUFFIX = ".iv"
        const val CIPHERTEXT_SUFFIX = ".ct"
    }
}
