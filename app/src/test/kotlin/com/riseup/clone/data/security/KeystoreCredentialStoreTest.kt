package com.riseup.clone.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.riseup.clone.domain.scraper.ScraperCredentials
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Ignore
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end round-trip against the real [KeystoreCredentialStore] — encrypting
 * through the platform Keystore + JCE and persisting ciphertext in a real
 * [android.content.SharedPreferences]. Confirms save/load returns the same
 * credentials, that clear removes them, and overwrite semantics.
 *
 * Ignored on this build machine only, for two independent reasons: (1) the same
 * Robolectric limitation as [com.riseup.clone.data.PersistedTransactionRepositoryTest]
 * (its environment setup loads a native conscrypt binary not published for
 * Windows/ARM64, so the runner cannot start here, and there is no device); and
 * (2) the `AndroidKeyStore` provider does not exist off-device even under
 * Robolectric, so hardware-backed key generation cannot run in a JVM unit test at
 * all. The store's crypto is instead proven on the JVM by [AesGcmCipherTest]
 * (the AES/GCM path) and [CredentialCodecTest] (serialization), with the store
 * contract proven by [InMemoryCredentialStoreTest]. Run this on x86_64 CI or a
 * device — remove [Ignore] there.
 */
@Ignore("AndroidKeyStore provider is unavailable off-device, and Robolectric cannot initialize on Windows/ARM64 (missing conscrypt native); run on a device")
@RunWith(RobolectricTestRunner::class)
class KeystoreCredentialStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val institution = "Bank Leumi"
    private val creds = ScraperCredentials(
        username = "user",
        password = "p@ss",
        extra = mapOf("nationalId" to "123456789"),
    )

    @Test
    fun `save then load returns the same credentials`() = runBlocking {
        val store = KeystoreCredentialStore(context)

        store.save(institution, creds)

        assertEquals(creds, store.load(institution))
    }

    @Test
    fun `clear removes the persisted secret`() = runBlocking {
        val store = KeystoreCredentialStore(context)
        store.save(institution, creds)

        store.clear(institution)

        assertNull(store.load(institution))
    }

    @Test
    fun `save overwrites the previous value`() = runBlocking {
        val store = KeystoreCredentialStore(context)
        store.save(institution, creds)

        val updated = creds.copy(password = "newpass")
        store.save(institution, updated)

        assertEquals(updated, store.load(institution))
    }
}
