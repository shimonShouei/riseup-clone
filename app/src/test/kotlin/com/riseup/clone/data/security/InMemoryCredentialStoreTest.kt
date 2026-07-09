package com.riseup.clone.data.security

import com.riseup.clone.domain.scraper.ScraperCredentials
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Pure-JVM verification of the [CredentialStore] *contract* — save/load
 * round-trip, overwrite semantics, and clear — through the in-memory fake. The
 * Keystore-backed store cannot run off-device, but it shares this contract; the
 * fake proves the behaviour every implementation must honour, while the real
 * crypto path is covered by [AesGcmCipherTest] and [CredentialCodecTest].
 */
class InMemoryCredentialStoreTest {

    private val leumi = "Bank Leumi"
    private val creds = ScraperCredentials(
        username = "user",
        password = "p@ss",
        extra = mapOf("nationalId" to "123456789"),
    )

    @Test
    fun `save then load returns the same credentials`() = runBlocking {
        val store = InMemoryCredentialStore()

        store.save(leumi, creds)

        assertEquals(creds, store.load(leumi))
    }

    @Test
    fun `load returns null when nothing is stored`() = runBlocking {
        assertNull(InMemoryCredentialStore().load(leumi))
    }

    @Test
    fun `save overwrites the previous value for an institution`() = runBlocking {
        val store = InMemoryCredentialStore()
        store.save(leumi, creds)

        val updated = creds.copy(password = "newpass")
        store.save(leumi, updated)

        assertEquals(updated, store.load(leumi))
    }

    @Test
    fun `clear removes the stored credentials`() = runBlocking {
        val store = InMemoryCredentialStore()
        store.save(leumi, creds)

        store.clear(leumi)

        assertNull(store.load(leumi))
    }

    @Test
    fun `credentials are keyed per institution`() = runBlocking {
        val store = InMemoryCredentialStore()
        val hapoalim = "Bank Hapoalim"
        val other = creds.copy(username = "other")

        store.save(leumi, creds)
        store.save(hapoalim, other)
        store.clear(leumi)

        assertNull(store.load(leumi))
        assertEquals(other, store.load(hapoalim))
    }

    @Test
    fun `clearAll wipes every institution`() = runBlocking {
        val store = InMemoryCredentialStore()
        store.save(leumi, creds)
        store.save("Bank Hapoalim", creds)

        store.clearAll()

        assertNull(store.load(leumi))
        assertNull(store.load("Bank Hapoalim"))
    }
}
