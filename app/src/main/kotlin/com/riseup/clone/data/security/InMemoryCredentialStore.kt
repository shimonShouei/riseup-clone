package com.riseup.clone.data.security

import com.riseup.clone.domain.scraper.ScraperCredentials
import java.util.concurrent.ConcurrentHashMap

/**
 * Non-persistent [CredentialStore] holding secrets only in process memory.
 *
 * Two uses: it is the fake that lets the [CredentialStore] *contract*
 * (round-trip / overwrite / clear) be unit-tested on the JVM without a device
 * (the Keystore-backed [KeystoreCredentialStore] cannot run off-device), and it
 * is a safe stand-in for Compose previews and local wiring. Because nothing is
 * ever written to disk, there is no at-rest exposure — the secret lives only as
 * long as the process — but for the same reason it must never be used as the real
 * production store.
 */
class InMemoryCredentialStore : CredentialStore {

    private val store = ConcurrentHashMap<String, ScraperCredentials>()

    override suspend fun save(institution: String, credentials: ScraperCredentials) {
        store[institution] = credentials
    }

    override suspend fun load(institution: String): ScraperCredentials? = store[institution]

    override suspend fun clear(institution: String) {
        store.remove(institution)
    }

    override suspend fun clearAll() {
        store.clear()
    }
}
