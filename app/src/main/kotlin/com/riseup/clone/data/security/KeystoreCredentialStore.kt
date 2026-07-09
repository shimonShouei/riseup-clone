package com.riseup.clone.data.security

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
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
 * ### Device-unlock binding (M2-8; `backend/SECURITY.md` §3 refinement 2, threat T6)
 * Because the stored secrets are *live* bank credentials and a backend bearer
 * token, the key requires a **recent user authentication** (device PIN / pattern /
 * biometric) to encrypt or decrypt — see [getOrCreateKey]. A thief who dumps this
 * app's storage from a stolen phone without unlocking it (recently) gets ciphertext
 * the Keystore will refuse to open. The validity window ([AUTH_VALIDITY_SECONDS])
 * is short, so decryption only succeeds shortly after the user unlocked the device
 * — which is exactly why sync is now foreground-only (the background worker can no
 * longer decrypt headlessly; see [com.riseup.clone.data.sync.LedgerSyncWorker] and
 * MainActivity).
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
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val cipher = AesGcmCipher()

    override suspend fun save(
        institution: String,
        credentials: ScraperCredentials,
    ): Unit = withContext(Dispatchers.IO) {
        val plaintext = CredentialCodec.encode(credentials)
        val sealed = try {
            cipher.seal(getOrCreateKey(), plaintext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The old key was invalidated (lock/biometric changed) — its ciphertext
            // is already unrecoverable. Regenerate a fresh key and seal under it so a
            // re-connect (re-entering credentials) can succeed rather than deadlock.
            cipher.seal(regenerateKey(), plaintext)
        }
        prefs.edit()
            .putString(ivKey(institution), sealed.iv.toBase64())
            .putString(ciphertextKey(institution), sealed.ciphertext.toBase64())
            .apply()
    }

    override suspend fun load(institution: String): CredentialLoad = withContext(Dispatchers.IO) {
        val iv = prefs.getString(ivKey(institution), null)?.fromBase64()
            ?: return@withContext CredentialLoad.Absent
        val ciphertext = prefs.getString(ciphertextKey(institution), null)?.fromBase64()
            ?: return@withContext CredentialLoad.Absent
        try {
            val plaintext = cipher.open(getOrCreateKey(), AesGcmCipher.Sealed(iv, ciphertext))
            CredentialLoad.Loaded(CredentialCodec.decode(plaintext))
        } catch (e: UserNotAuthenticatedException) {
            // Key is unlock-bound and the device wasn't unlocked recently enough.
            // Recoverable: unlock in the foreground and retry. (No cred material in
            // the exception — safe to surface as a typed outcome, not to log.)
            CredentialLoad.AuthRequired
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Secure lock screen removed or biometrics re-enrolled → this ciphertext
            // can never be decrypted again. Caller must prompt a re-connect.
            CredentialLoad.KeyInvalidated
        }
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
     * both encrypt and decrypt.
     *
     * **User-authentication requirement (M2-8).** When the device has a secure lock
     * screen, the key is bound to a recent user authentication (device credential
     * or biometric) with a short validity window ([AUTH_VALIDITY_SECONDS]):
     * - API 30+ uses [KeyGenParameterSpec.Builder.setUserAuthenticationParameters]
     *   with `AUTH_DEVICE_CREDENTIAL or AUTH_BIOMETRIC_STRONG`, so either a PIN/pattern/
     *   password *or* a fingerprint/face satisfies it within the window.
     * - API 26–29 falls back to the deprecated
     *   `setUserAuthenticationValidityDurationSeconds`, whose semantics are the same
     *   time-bound "authenticated in the last N seconds" gate.
     *
     * A time-bound key is deliberately chosen over a per-operation
     * `BiometricPrompt` + `CryptoObject`: it requires no biometric integration and
     * naturally makes decryption succeed only right after the user unlocked the
     * device — i.e. in the foreground — which is the whole point of foreground-only
     * sync.
     *
     * [setInvalidatedByBiometricEnrollment] is set so that enrolling a new
     * fingerprint/face (a classic account-takeover step on a seized phone)
     * permanently invalidates the key, forcing a credential re-entry.
     *
     * **Degraded mode.** If the device has *no* secure lock screen there is nothing
     * to bind to; `KeyguardManager.isDeviceSecure()` is false and the key is
     * generated without the auth requirement (the platform would otherwise refuse
     * to generate it). Credentials are then protected only by the non-exportable
     * Keystore key, exactly as before this hardening — the app stays usable but the
     * stolen-phone posture is weaker. Users holding live bank credentials should set
     * a device lock; this is surfaced as guidance, not enforced (enforcing it would
     * lock a lockless device out of its own data).
     */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return generateKey()
    }

    /** Delete any existing key and generate a fresh one (invalidated-key recovery). */
    private fun regenerateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (isDeviceSecure()) {
            builder.setUserAuthenticationRequired(true)
            // Invalidate the key if biometrics are re-enrolled — defeats an attacker
            // who enrols their own biometric on a seized, still-unlocked phone.
            builder.setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            }
        }
        // else: degraded mode — no secure lock screen to bind to (see getOrCreateKey doc).

        generator.init(builder.build())
        return generator.generateKey()
    }

    /** True when a PIN/pattern/password (or stronger) secures the device. */
    private fun isDeviceSecure(): Boolean =
        (appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)
            ?.isDeviceSecure == true

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

        /**
         * How recently the user must have authenticated for a decrypt to succeed.
         * Short enough that decryption only works shortly after an unlock (making
         * sync effectively foreground-only), long enough to cover an app-open sync
         * plus a manual retry without re-prompting on every operation.
         */
        const val AUTH_VALIDITY_SECONDS = 90
    }
}
