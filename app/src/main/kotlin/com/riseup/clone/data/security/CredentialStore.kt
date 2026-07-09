package com.riseup.clone.data.security

import com.riseup.clone.domain.scraper.ScraperCredentials

/**
 * Storage boundary for a bank provider's login material.
 *
 * The whole reason this is an interface: the credential blob a scraper needs
 * ([ScraperCredentials], reused verbatim so there is no parallel secret type to
 * keep in sync) is a *secret at rest*. The production implementation
 * ([KeystoreCredentialStore]) encrypts it under an Android Keystore key, but the
 * scraper wiring only ever sees this contract — save, load, clear, keyed by
 * institution — so it never learns whether it is talking to real hardware-backed
 * crypto or the in-memory [InMemoryCredentialStore] used in tests and previews.
 *
 * Contract (verified by [InMemoryCredentialStoreTest] on the JVM):
 * - [save] then [load] for the same institution returns equal credentials.
 * - [save] twice overwrites: the second value wins.
 * - [clear] removes the secret so a later [load] returns `null`.
 *
 * Implementations must never persist or log the plaintext credential; see the
 * redacting `toString()` on [ScraperCredentials] and the note in
 * [KeystoreCredentialStore].
 */
interface CredentialStore {

    /**
     * Persist [credentials] for [institution], replacing any previously stored
     * value for that institution (overwrite semantics).
     */
    suspend fun save(institution: String, credentials: ScraperCredentials)

    /**
     * Return the credentials stored for [institution], or `null` if none are
     * stored (never saved, or [clear]ed).
     */
    suspend fun load(institution: String): ScraperCredentials?

    /**
     * Remove the stored credentials for [institution]. Idempotent — clearing an
     * institution with nothing stored is a no-op. This is what logout calls to
     * guarantee the secret no longer exists at rest.
     */
    suspend fun clear(institution: String)

    /**
     * Remove every stored credential (and, where applicable, the backing key
     * material). Used for a full account reset / sign-out.
     */
    suspend fun clearAll()
}
