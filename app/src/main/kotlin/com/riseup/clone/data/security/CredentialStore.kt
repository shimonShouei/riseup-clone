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
 * - [save] then [load] for the same institution returns [CredentialLoad.Loaded]
 *   with equal credentials.
 * - [save] twice overwrites: the second value wins.
 * - [clear] removes the secret so a later [load] returns [CredentialLoad.Absent].
 *
 * ### Why [load] returns a typed [CredentialLoad] rather than `ScraperCredentials?`
 * The production key is bound to a recent device unlock (M2-8; see the
 * `setUserAuthenticationRequired` note in [KeystoreCredentialStore] and
 * `backend/SECURITY.md` §3 refinement 2). Decryption can therefore fail for
 * reasons that are *not* "nothing stored": the user hasn't unlocked recently
 * ([CredentialLoad.AuthRequired]) or the key was permanently invalidated because
 * the screen lock / biometric enrolment changed ([CredentialLoad.KeyInvalidated]).
 * Folding all of these into `null` would let callers mistake "locked" for
 * "disconnected" and silently drop the connection. The sealed result forces each
 * caller to react correctly (prompt to unlock vs. re-enter credentials vs. connect).
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
     * Return the outcome of loading the credentials stored for [institution]:
     * [CredentialLoad.Loaded] when present and decryptable, [CredentialLoad.Absent]
     * when none are stored, or a locked/invalidated outcome when a device-unlock
     * requirement or key invalidation blocked decryption.
     */
    suspend fun load(institution: String): CredentialLoad

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

/**
 * The outcome of a [CredentialStore.load]. A sealed result (rather than a nullable
 * credential or a raw thrown exception) so every caller must handle the
 * device-unlock states the hardened Keystore key can produce — see the class doc
 * on [CredentialStore].
 */
sealed interface CredentialLoad {

    /** Credentials were present and decrypted successfully. */
    data class Loaded(val credentials: ScraperCredentials) : CredentialLoad

    /** Nothing is stored for this institution (never saved, or [CredentialStore.clear]ed). */
    data object Absent : CredentialLoad

    /**
     * The ciphertext exists but the key requires a *recent* device unlock that has
     * not happened (Keystore threw `UserNotAuthenticatedException`). Recoverable:
     * the user should unlock the device and retry in the foreground. Never happens
     * with an unbound key (e.g. [InMemoryCredentialStore]).
     */
    data object AuthRequired : CredentialLoad

    /**
     * The key was permanently invalidated (Keystore threw
     * `KeyPermanentlyInvalidatedException`) because the secure lock screen was
     * removed or biometric enrolment changed. The stored ciphertext is now
     * unrecoverable — the user must re-enter their credentials via the connect flow.
     */
    data object KeyInvalidated : CredentialLoad
}

/**
 * Convenience for the callers that only care whether a usable credential is
 * present (e.g. the backend-token provider) and treat every non-[CredentialLoad.Loaded]
 * outcome the same. Callers that must distinguish "locked" from "disconnected"
 * (the syncer, the connect flow) branch on [CredentialLoad] directly instead.
 */
suspend fun CredentialStore.loadOrNull(institution: String): ScraperCredentials? =
    (load(institution) as? CredentialLoad.Loaded)?.credentials
